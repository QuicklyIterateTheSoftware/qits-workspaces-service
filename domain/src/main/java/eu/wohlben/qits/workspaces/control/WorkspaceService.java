package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.IntegrateConflictException;
import eu.wohlben.qits.workspaces.error.InternalServerErrorException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.error.RunnerRefusals;
import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerRefDto;
import eu.wohlben.qits.workspaces.dto.WorkItemWorkspaceDto;
import eu.wohlben.qits.workspaces.dto.WorkspaceSubjectRefDto;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceEvent;
import eu.wohlben.qits.workspaces.entity.WorkspaceEventType;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.gitmirror.GitMirrorException;
import eu.wohlben.qits.workspaces.gitmirror.MergeOutcome;
import eu.wohlben.qits.workspaces.gitmirror.MirrorWorktree;
import eu.wohlben.qits.workspaces.gitmirror.PushOutcome;
import eu.wohlben.qits.workspaces.gitmirror.PushSpec;
import eu.wohlben.qits.workspaces.gitmirror.RepoMirror;
import eu.wohlben.qits.workspaces.persistence.WorkspaceEventRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkspaceService {

  private static final Logger LOG = Logger.getLogger(WorkspaceService.class);

  @Inject RepositoryLookup repositories;

  @Inject WorkspaceRepository workspaceRepository;

  /** The runners' names, for the workspaces placed on one. */
  @Inject WorkspaceRunnerRepository runnerRepository;

  @Inject WorkspaceEventRepository workspaceEventRepository;

  @Inject WorkspaceMetadataStore workspaceMetadata;

  /**
   * Fired when a workspace resolves, so other contexts can drop the rows that hang off it. The
   * delete is soft, so their FK cascade never fires; before the extraction this class reached
   * across and hard-deleted prompt drafts and attachments itself.
   */
  @Inject Event<WorkspaceResolved> workspaceResolvedEvent;

  @Inject ContainerRuntime containers;

  /**
   * Optional: where a per-container platform credential is commissioned and given back. Absent — no
   * implementation, or one with no issuer configured — means no credential is minted and no
   * container carries one, which is what every workspace did before this port existed. See {@link
   * CredentialCommissioner}.
   */
  @Inject Instance<CredentialCommissioner> commissioner;

  /** The Git refs a workspace may push, and their narrowing when another workspace takes a branch. */
  @Inject GitRefScopes gitRefScopes;

  @Inject WorkspaceContainerEventPublisher containerEvents;

  /**
   * Optional: the runners' sockets, for the RUNNER-placed rows (epic qits-624). Absent reads as no
   * runner connected — see {@link RunnerPlacement}. A DIRECT row never reaches it.
   */
  @Inject Instance<RunnerPlacement> runnerPlacement;

  /** The runner session's writes, and the start processes a runner's launch settles. */
  @Inject RunnerClaims runnerClaims;

  /**
   * The edge address plane a RUNNER row is composed from (qits-625): built by the RUNNER start
   * before it queues, so a deployment with no public domain fails the row rather than queueing it.
   */
  @Inject WorkspaceAddressPlanes addressPlanes;

  @Inject WorkspaceChangePublisher changePublisher;

  /**
   * Optional: the technical-process framework is a cross-context streaming primitive owned by the
   * application. Absent means the same work runs, unnarrated, with a null process id.
   */
  @Inject Instance<WorkspaceProcessTracker> processes;

  /**
   * The in-container workspace-daemon's liveness, observed alongside the docker reconciliation
   * ladder (docs/epics/qits-workspace-daemon/). {@code Instance<>} because apps without the backend
   * impl (cli, tests) have no {@link WorkspaceDaemonLiveness} bean — there it is simply empty.
   * <b>Part 1 is observational only</b>: {@link #ensureContainer} logs this signal but never
   * branches on it.
   */
  @Inject Instance<WorkspaceDaemonLiveness> clientLiveness;

  /**
   * The in-container workspace-daemon's last-reported working-tree cleanliness (clean/dirty),
   * surfaced as {@link WorkspaceDto#clean}. {@code Instance<>} for the same reason as {@link
   * #clientLiveness}: apps without the backend impl (cli, tests) have no {@link WorkspaceGitStatus}
   * bean and simply see it empty. Only consulted for RUNNING workspaces — the daemon reports only
   * while connected.
   */
  @Inject Instance<WorkspaceGitStatus> gitStatus;

  /**
   * The in-container workspace-daemon's last-reported coding-agent activity rollup, surfaced as
   * {@link WorkspaceDto#agentActivity}. {@code Instance<>} for the same reason as {@link
   * #gitStatus}: apps without the backend impl (cli, tests) have no {@link WorkspaceAgentActivity}
   * bean and simply see it empty. Only consulted for RUNNING workspaces — the daemon reports only
   * while connected.
   */
  @Inject Instance<WorkspaceAgentActivity> agentActivity;

  /**
   * The workspace registry's live view of each workspace's daemon — connected-since + the daemon
   * binary's build identity, surfaced as {@link WorkspaceDto#daemonConnectedAt}/{@code
   * daemonVersion}/{@code daemonBuildTime} (docs/epics/qits-workspace-registry/). {@code
   * Instance<>} for the same reason as {@link #gitStatus}: apps without the backend impl (cli,
   * tests) have no {@link WorkspaceDaemonInfo} bean and simply see it empty. Only consulted for
   * RUNNING workspaces.
   */
  @Inject Instance<WorkspaceDaemonInfo> daemonInfo;

  /**
   * Notifies a target workspace's in-container daemon to pull an incoming merge/integration this
   * host just pushed to its branch (docs/epics/qits-workspace-daemon/ bidirectional auto-sync).
   * {@code Instance<>} for the same reason as {@link #gitStatus}: apps without the backend impl
   * (cli, tests) have no {@link WorkspaceGitSync} bean and simply skip the notification — the
   * checkout then syncs on its next host git op, so nothing is lost.
   */
  @Inject Instance<WorkspaceGitSync> gitSync;

  /**
   * Awaits the in-container workspace-daemon's autonomous self-provision (clone + submodules on
   * boot) — the <b>sole</b> provisioning path (docs/epics/qits-workspace-daemon/ Part 2). {@code
   * Instance<>} because the real backend impl lives in {@code service}; apps without it (cli,
   * tests) supply a {@link WorkspaceDaemonProvisioner} test double that clones through the {@code
   * ContainerRuntime}. An empty {@code Instance<>} (or a daemon that never connects) fails the
   * provision — there is no host-driven fallback.
   */
  @Inject Instance<WorkspaceDaemonProvisioner> daemonProvisioner;

  /**
   * How long a fresh provision waits for a daemon to dial home before declaring the provision
   * FAILED. The daemon is the sole provisioner (there is no host-clone fallback), so this is the
   * stale-image discriminator: a modern image's daemon connects within ~a second, so a generous
   * default reliably distinguishes "no daemon here (rebuild the image)" from "slow daemon" — and
   * the former now fails loudly rather than degrading.
   */
  @ConfigProperty(name = "qits.workspace.provision.connect-timeout-ms", defaultValue = "30000")
  long provisionConnectTimeoutMs;

  /** How long, once a daemon is live, to wait for its terminal Provisioned/ProvisionFailed. */
  @ConfigProperty(name = "qits.workspace.provision.timeout-ms", defaultValue = "600000")
  long provisionTimeoutMs;

  /**
   * Runs {@link #beginEnsureContainer}'s provision work off the request thread — the HTTP call
   * returns the technical-process id immediately and the browser watches the work over SSE.
   */
  private final ExecutorService processExecutor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-provision");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    processExecutor.shutdownNow();
  }

  /**
   * The git substrate: a mirror per repository, the worktrees a merge runs in, and the pushes that
   * are now the only way a ref of a served repository moves.
   *
   * <p>Nothing in this class opens the shared volume of bare origins any more. Every branch create,
   * every branch delete and every merge used to write a ref there directly, and every one of them
   * fired no {@code post-receive} — so a workspace created, integrated or cleaned up produced no CI
   * run and no event. They are pushes now, and the chain downstream happens for the ordinary reason.
   */
  @Inject GitMirrorRegistry mirrors;

  /** How long an integrate waits for a busy repository before refusing — see {@link #acquireIntegrateLease}. */
  @ConfigProperty(name = "qits.workspace.integrate.lease-wait-ms", defaultValue = "60000")
  long integrateLeaseWaitMs;

  @Inject GitIdentity gitIdentity;

  /** The git half of {@link #integrateWorkspace}: worktree, merge, commit, push. */
  @Inject BranchIntegrator integrator;

  /**
   * The repository-scoped mutex integrate serializes on. The concrete registry rather than the
   * {@link WorkspaceProcessTracker} port, because what integrate wants is the port's
   * <em>lightweight</em> half — a reservation, not a streamed process with an SSE channel nobody
   * subscribes to. It is a bean of this module, so it is always present.
   */
  @Inject TechnicalProcessRegistry processRegistry;

  /**
   * Creates {@code branch} from {@code parentBranch} — <b>as a push</b>, through the git host.
   *
   * <p>This was {@code git branch} in the bare origin on the shared volume, and it is the plainest
   * example of what that cost: a filesystem ref update fires no {@code post-receive}, so <b>no
   * workspace anyone has ever created produced a CI run</b>. A push does, because it is a push.
   *
   * <p>An existing ref is a <em>client</em> error, not a server one: this is the normal-path guard
   * for "each workspace gets its own branch", and asking for a branch that is already there is a
   * 409 the caller can act on — a typo'd "branch off" name, or a branch created outside qits that
   * the caller meant to adopt. It is checked up front rather than inferred from the push's refusal,
   * which cannot distinguish "ref exists" from a genuinely unreachable host; the latter still 500s.
   */
  private void createBranchOnHost(RepoMirror mirror, String branch, String parentBranch) {
    if (mirror.remoteHasBranch(branch)) {
      throw new ConflictException("Branch already exists: " + branch);
    }
    mirror.refreshNow();
    PushOutcome pushed;
    try {
      pushed = mirror.createBranch(branch, parentBranch);
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Failed to create branch: " + e.getMessage());
    }
    if (!pushed.accepted()) {
      throw new InternalServerErrorException("Failed to create branch: " + pushed.output());
    }
  }

  /**
   * Materializes a workspace's container from its durable branch ref: runs the container and clones
   * {@code branch} into its {@code /workspace} (the commit identity arrives as container-level
   * {@code GIT_*} env via {@link WorkspaceContainerFactory}, so nothing is configured in the
   * clone). Removes the container again if the clone fails, so a retry can succeed. The branch ref
   * must already exist in the origin — this is the on-demand half of workspace creation, invoked
   * lazily by {@link #ensureContainer} for never-provisioned and pruned workspaces alike.
   *
   * <p>The clone is <b>autonomous and daemon-only</b>: the in-container workspace-daemon clones
   * {@code /workspace} and materializes submodules from its own boot-time env, then reports the
   * outcome up the control socket; qits only {@link #awaitDaemonProvision awaits} it (streaming the
   * daemon's output into the {@code clone} segment) and drives no git step. There is <b>no
   * host-driven fallback</b> (docs/epics/qits-workspace-daemon/ Part 2): a container with no live
   * daemon — a stale, pre-daemon image — fails to provision (rm + FAILED) rather than degrading.
   *
   * <p>A non-null {@code process} receives the provision as streamed segments: {@code container}
   * (the orchestrator putting the container at its place) and {@code clone} (the clone plus the
   * daemon's submodule materialization) — lines arrive live from the daemon over the socket, and
   * each segment settles when its step completes. A failure leaves the open segment for the caller
   * to settle {@code failed}.
   *
   * <p>Submodules resolve <b>natively</b> in the daemon's bounded {@code .gitmodules} walk: with
   * the repository addressed by its project-scoped name, a project's repos are siblings and a
   * committed relative submodule url ({@code ../<name>.git}) resolves against the origin to a
   * served sibling with no override (an absolute committed url is redirected explicitly). The
   * daemon sources the walk from the checkout's own {@code .gitmodules} (it has no DB), skipping
   * any submodule it can't resolve — so there is no import-scoping (an accepted trade-off; see
   * {@code Provisioner.materializeSubmodules}).
   */
  private void provisionContainer(
      String repoId,
      String workspaceId,
      Long rowId,
      String branch,
      String parentBranch,
      String entityId,
      WorkspaceProcessTracker.Handle process) {
    // A fresh container gets a fresh credential, and it is minted BEFORE anything is started: a
    // commissioning failure must cost a launch that has not happened yet, never leave a container
    // running with no identity. This is also the one place a recreate is covered — recreate rm's the
    // container and comes back through here — so no second seam has to remember.
    commissionFor(repoId, workspaceId, rowId);
    if (process != null) {
      process.openSegment("container");
    }
    Consumer<String> runLines =
        process == null ? null : line -> process.appendLine("container", line);
    String container =
        containers.run(repoId, workspaceId, rowId, branch, parentBranch, entityId, runLines);
    if (process != null) {
      process.settleSegment("container", true);
      process.openSegment("clone");
    }
    Consumer<String> cloneLines =
        process == null ? null : line -> process.appendLine("clone", line);

    // Provisioning is the daemon's job (docs/epics/qits-workspace-daemon/ Part 2): the in-container
    // workspace-daemon clones /workspace and materializes submodules from its own boot-time env,
    // then
    // reports Provisioned/ProvisionFailed over the control socket. qits only AWAITS that outcome,
    // feeding the clone segment from the daemon's streamed output — it drives no git step and no
    // longer falls back to a host-driven clone. A container with no live daemon (a stale,
    // pre-daemon
    // image) therefore cannot be provisioned: it fails loudly (rm + FAILED), recoverable by
    // rebuilding the image so the daemon is present.
    ProvisionResult outcome = awaitDaemonProvision(repoId, workspaceId, rowId, cloneLines);
    if (!outcome.ok()) {
      containers.rm(container);
      // The container this credential was minted for is gone again, so it goes back — the same rule
      // every teardown seam follows, applied to the teardown a failed provision is.
      decommissionFor(rowId);
      throw new InternalServerErrorException(
          "workspace-daemon self-provision failed: " + outcome.message());
    }
    if (process != null) {
      process.settleSegment("clone", true);
    }
  }

  /**
   * Await the in-container daemon's autonomous self-provision. The daemon is the <b>sole</b>
   * provisioner (docs/epics/qits-workspace-daemon/ Part 2) — there is no host-driven fallback — so
   * a missing provisioner bean or a daemon that never dials home within the connect window is a
   * provision FAILURE, not a degradation. In apps without the backend impl (cli, tests with no real
   * container), a {@link WorkspaceDaemonProvisioner} test double stands in for the daemon and
   * clones the checkout through the {@code ContainerRuntime}.
   */
  private ProvisionResult awaitDaemonProvision(
      String repoId, String workspaceId, Long rowId, Consumer<String> onLine) {
    if (!daemonProvisioner.isResolvable()) {
      return ProvisionResult.failed("no workspace-daemon provisioner is available");
    }
    return daemonProvisioner
        .get()
        .awaitProvision(
            rowId,
            Duration.ofMillis(provisionConnectTimeoutMs),
            Duration.ofMillis(provisionTimeoutMs),
            onLine)
        .orElseGet(
            () ->
                ProvisionResult.failed(
                    "no workspace-daemon dialed home within "
                        + provisionConnectTimeoutMs
                        + "ms — is the container running an image with the daemon?"));
  }

  /**
   * Commission the platform credential this workspace's next container will carry, and put it on the
   * row so every later ensure composes the same container spec.
   *
   * <p><b>It fails the provision.</b> A workspace launched with no identity would pull and push as
   * nobody, which is precisely the state this credential exists to end, and the failure would only
   * surface much later as a refused registry read. The launch already surfaces ensure failures, so
   * throwing here reports it where it happened. The implementation is patient first — see {@code
   * wiring/IdpCredentialCommissioner} — so what reaches this point is an issuer that stayed
   * unreachable, not a redeploy window.
   *
   * <p>Any credential already on the row is given back first. That is the recreate case: the
   * container it belonged to has just been removed, and a row can only carry one.
   *
   * <p>Its own transaction, and not the caller's: {@link #provisionContainer} runs outside one (each
   * status transition commits separately), and the pair must be committed before {@code
   * containers.run} asks the factory to read it back.
   *
   * <p><b>The commission says which project the credential is for</b>, resolved from the repository
   * the workspace branches. That is the whole of per-context scoping on this side: the issuer turns
   * it into a {@code project} claim, and a resource service then judges this container's token on the
   * project rather than on its platform role alone — a workspace agent can have its own project's
   * pipelines evaluated and nobody else's. The project the container is told about ({@code
   * QITS_WORKSPACE_DAEMON_PROJECT_ID}, put there by {@link WorkspaceContainerFactory}) and the one
   * the credential is scoped to are deliberately the same fact from the same registry.
   */
  private void commissionFor(String repoId, String workspaceId, Long rowId) {
    if (!commissioner.isResolvable()) {
      return;
    }
    decommissionFor(rowId);
    // THE EDITOR NAMES NO REPOSITORY, so none is asked for. Its row carries a sentinel id, and this
    // lookup is an HTTP call to qits-projects that could only ever answer "no such repository" for
    // it — once per provision, in a WARN-shaped log line, to learn what the row already says. What
    // it costs the editor is what an unresolvable repository costs any workspace and no more: the
    // `project` claim is absent, which is the credential's own documented "unscoped" answer.
    //
    // That absence is the accepted consequence the epic decided: ONE container, holding an ordinary
    // `qits:agent` workspace credential scoped to no project, now reaches every project on the
    // platform — see WorkspaceContainerFactory's editor block, which records the same decision from
    // the container's side.
    boolean editor =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findActiveById(rowId).map(wt -> wt.editor).orElse(false));
    // AN ADMIN ROW COMMISSIONS ITS OWN KIND (qits-628 follow-up): workspace-admin, which qits-idp
    // issues qits:agent and qits:admin-agent — the container holds the host's docker socket, so its
    // agent is admitted wherever qits:admin is. Every other row, the editor included, stays
    // `workspace`. The kind is the issuer's record and never the container's environment, so the
    // spec is unchanged by it.
    boolean admin =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findActiveById(rowId).map(wt -> wt.admin).orElse(false));
    RepositoryLookup.RepositoryView repository = editor ? null : repositoryOf(repoId);
    // The default branch, read the way a create reads it. An agent never pushes it, so it leaves
    // every list below. A registry that did not answer drops nothing: like the project scope, it
    // costs the scope and not the launch.
    String defaultBranch = repository == null ? null : defaultMainBranch(repository);
    // The Git refs this container may push (contract C5): the row's list, or its own branch for a
    // row that predates the column. Read as the stored form so the check below compares like with
    // like.
    String statedRefs =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository
                        .findActiveById(rowId)
                        .map(wt -> GitRefs.write(GitRefs.effective(wt, defaultBranch)))
                        .orElse(null));
    Optional<WorkspaceCredential> issued =
        commissioner
            .get()
            .commission(
                rowId,
                projectOf(repository),
                statedRefs == null ? null : GitRefs.read(statedRefs),
                admin);
    if (issued.isEmpty()) {
      // No issuer wired. Supported, and the same as no implementation at all.
      return;
    }
    WorkspaceCredential credential = issued.get();
    boolean narrowedMeanwhile =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository
                        .findActiveById(rowId)
                        .map(
                            wt -> {
                              wt.commissionedClientId = credential.clientId();
                              wt.commissionedClientSecret = credential.secret();
                              // A list stored before the default-branch rule may still name the
                              // default branch. Store it without, so a later narrowing sends what
                              // the commission stated and does not put the branch back.
                              List<String> allowed = GitRefs.effective(wt, defaultBranch);
                              if (wt.gitRefs != null
                                  && !GitRefs.read(wt.gitRefs).equals(allowed)) {
                                LOG.infof(
                                    "Workspace %s may not push the default branch %s; its stored"
                                        + " Git refs no longer name it",
                                    rowId, defaultBranch);
                                wt.gitRefs = GitRefs.write(allowed);
                              }
                              // The commission stated `statedRefs`. A narrowing that landed while it
                              // was being made found no client to update, so the list it left is
                              // still to be sent.
                              wt.gitRefsPending = !GitRefs.write(allowed).equals(statedRefs);
                              return wt.gitRefsPending;
                            })
                        .orElse(false));
    if (narrowedMeanwhile) {
      gitRefScopes.push(rowId);
    }
    LOG.debugf(
        "Commissioned %s for workspace %s/%s", credential.clientId(), repoId, workspaceId);
  }

  /**
   * The project a repository belongs to, or null when the registry cannot say.
   *
   * <p><b>Null is not a failure here.</b> Every caller is a launch, and the registry answering
   * nothing is a moment rather than a verdict — the same reading {@link WorkspaceContainerFactory}
   * already takes for the label and the daemon's env, where a project it cannot resolve costs a
   * label and never a workspace. So an unresolved project costs the credential its scope: it is
   * commissioned exactly as workspace credentials were before scoping existed, which is wider than
   * intended and still narrower than not starting.
   */
  private static String projectOf(RepositoryLookup.RepositoryView repository) {
    if (repository == null || repository.projectId() == null || repository.projectId().isBlank()) {
      return null;
    }
    return repository.projectId();
  }

  /**
   * The registry's view of a repository for a commission, or null when it cannot say — one lookup
   * for both the project scope and the default branch. Null for the reason {@link #projectOf}
   * gives.
   */
  private RepositoryLookup.RepositoryView repositoryOf(String repoId) {
    if (repoId == null || repoId.isBlank()) {
      return null;
    }
    try {
      return repositories.find(repoId).orElse(null);
    } catch (RuntimeException registryDidNotAnswer) {
      LOG.debugf(
          "Could not resolve repository %s to scope its workspace credential: %s",
          repoId, registryDidNotAnswer.toString());
      return null;
    }
  }

  /**
   * Give back the credential a workspace's container held, and clear the row.
   *
   * <p><b>Best-effort, and never in the way of a teardown.</b> Every caller runs after something
   * irreversible — a container removed, a branch deleted — so a revocation that fails is logged and
   * the teardown continues. What that leaves behind is a credential nothing can use to reach a
   * container that no longer exists, and the reconcile reaps it within the hour.
   *
   * <p>The row is cleared even when the revocation fails, and that order is deliberate: the clientId
   * is on the row so a teardown can find it, and a row still naming a client this service has
   * stopped tracking would make the reconcile spare an orphan forever.
   *
   * <p>Callers on a transactional path (the resolution verbs, {@code deleteContainer}) hold the
   * managed row already, so the clear rides their transaction; the row write here is for the paths
   * that do not.
   */
  private void decommissionFor(Long rowId) {
    if (!commissioner.isResolvable() || rowId == null) {
      return;
    }
    // [clientId, tokenId]: a DIRECT row holds the first, a RUNNER row the second (qits-625).
    String[] held =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository
                        .findByIdOptional(rowId)
                        .map(
                            wt -> {
                              String[] was = {wt.commissionedClientId, wt.commissionedTokenId};
                              wt.commissionedClientId = null;
                              wt.commissionedClientSecret = null;
                              clearToken(wt);
                              return was;
                            })
                        .orElse(new String[2]));
    decommission(held[0]);
    deleteToken(held[1]);
  }

  /**
   * Empties a row's three token columns (qits-625), in the caller's transaction, and answers the
   * token id they held so the caller can delete it once that transaction is out of the way.
   */
  private static String clearToken(Workspace wt) {
    String held = wt.commissionedTokenId;
    wt.commissionedTokenId = null;
    wt.commissionedTokenSubject = null;
    wt.commissionedToken = null;
    return held;
  }

  /**
   * The workspace token's deletion, for callers that already cleared the row. Best-effort, as
   * {@link #decommission} is; null or blank is a row that held none and is silent.
   */
  private void deleteToken(String tokenId) {
    if (!commissioner.isResolvable() || tokenId == null || tokenId.isBlank()) {
      return;
    }
    try {
      commissioner.get().deleteToken(tokenId);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not delete workspace token %s; the reconcile will reap it: %s",
          tokenId, e.toString());
    }
  }

  /**
   * The revocation itself, for callers that already hold the row and have cleared it themselves.
   * Null or blank is the ordinary case — a workspace that never held a credential — and is silent.
   */
  private void decommission(String clientId) {
    if (!commissioner.isResolvable() || clientId == null || clientId.isBlank()) {
      return;
    }
    try {
      commissioner.get().decommission(clientId);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not decommission %s; the reconcile will reap it: %s", clientId, e.toString());
    }
  }

  /** Appends a history event to a workspace's timeline. */
  private void recordEvent(
      Workspace workspace, WorkspaceEventType type, String branch, String target, String commit) {
    workspaceEventRepository.persist(
        WorkspaceEvent.builder()
            .workspace(workspace)
            .type(type)
            .branch(branch)
            .parent(workspace.parent)
            .target(target)
            .commit(commit)
            .at(Instant.now())
            .build());
  }

  public List<WorkspaceDto> listWorkspaces(String repoId) {
    // The existence check and the default branch are one answer: this call already had to be made,
    // and mainBranch rides on it. Carrying it into every row is what lets a client tell "Integrate"
    // from "Release" — only a release may write the default branch — without asking qits-projects
    // for one string.
    RepositoryLookup.RepositoryView repository = repositories.require(repoId);

    // One refresh for the whole listing, and a failure only costs the ahead/behind numbers: the
    // browser polls this route, so a git host that is briefly away must not 500 the page it is on.
    RepoMirror mirror = mirrors.of(repoId);
    refreshQuietly(mirror);
    // The branch tree shows only live workspaces; resolved ones live in the history view.
    List<Workspace> rows = workspaceRepository.findActiveByRepositoryId(repoId);
    // Live container set (one listing call to the orchestrator), so RUNNING stays accurate even
    // when container state changed out-of-band; the persisted column carries the
    // STOPPED/PROVISIONING/FAILED signal otherwise. Asked only when a row here is on the direct
    // path at all (qits-780: in practice an admin one) — a repository of RUNNER rows, which is every
    // regular workspace, costs qits-containers nothing.
    Set<String> runningIds =
        rows.stream().noneMatch(wt -> wt.placement == WorkspacePlacement.DIRECT)
            ? Set.of()
            : containers.listWorkspaceContainers(repoId).stream()
                // ps -a lists stopped containers too; only genuinely-running ones count as RUNNING
                // (a deliberately stopped or Exited container is present but must read as STOPPED).
                .filter(ContainerRuntime.ContainerInfo::running)
                .map(ContainerRuntime.ContainerInfo::workspaceId)
                .collect(Collectors.toSet());
    // The newest daemon build connected anywhere is the registry-only "latest agent version"
    // (docs/epics/qits-workspace-registry/): a RUNNING workspace whose build is strictly older is
    // flagged daemonOutdated so the UI can offer a Recreate. Computed once per list, across all
    // repos' live daemons — the notion is registry-wide, not per-repository.
    WorkspaceDaemonInfo.Info latestDaemon =
        daemonInfo.isResolvable() ? latestDaemon(daemonInfo.get().all()) : null;
    // The runners' names, in one read, and none at all when no row is on a runner.
    Map<UUID, String> runnerNames =
        runnerRepository.namesById(
            rows.stream().map(w -> w.runnerId).filter(Objects::nonNull).collect(Collectors.toSet()));
    return rows.stream()
        .map(
            wt -> {
              String branch = wt.branch;
              AheadBehind ab = aheadBehind(mirror, wt.parent, branch);
              // Only diverged branches (both ahead and behind) can't fast-forward and so risk a
              // conflict; everything else integrates cleanly, so skip the extra merge-tree probe.
              boolean conflicts =
                  ab.ahead() != null
                      && ab.behind() != null
                      && ab.ahead() > 0
                      && ab.behind() > 0
                      && wouldConflict(mirror, wt.parent, branch);
              // A RUNNER row's container is not qits-containers', so that listing says nothing
              // about it: its persisted status is the runner's word, under the UNAVAILABLE overlay.
              WorkspaceRuntimeStatus runtime =
                  wt.placement == WorkspacePlacement.RUNNER
                      ? runnerRuntime(wt)
                      : directRuntime(wt, runningIds);
              // Clean/dirty is only knowable while the daemon is connected (RUNNING); otherwise it
              // stays null (unknown ⇒ no badge). The daemon re-reports on reconnect.
              Boolean clean =
                  runtime == WorkspaceRuntimeStatus.RUNNING && gitStatus.isResolvable()
                      ? gitStatus.get().isClean(wt.id).orElse(null)
                      : null;
              // Agent activity shares clean/dirty's RUNNING-only, self-healing contract.
              AgentActivityState activity =
                  runtime == WorkspaceRuntimeStatus.RUNNING && agentActivity.isResolvable()
                      ? agentActivity.get().activityFor(wt.id).orElse(null)
                      : null;
              // Registry facts (connected-since + daemon build identity) share clean/dirty's
              // RUNNING-only, in-memory contract: known only while the daemon's socket is live.
              WorkspaceDaemonInfo.Info info =
                  runtime == WorkspaceRuntimeStatus.RUNNING && daemonInfo.isResolvable()
                      ? daemonInfo.get().lookup(wt.id).orElse(null)
                      : null;
              return new WorkspaceDto(
                  wt.id,
                  wt.workspaceId,
                  wt.parent,
                  branch,
                  repository.mainBranch(),
                  ab.ahead(),
                  ab.behind(),
                  conflicts,
                  wt.status,
                  runtime,
                  wt.runtimeError,
                  clean,
                  activity,
                  wt.preamble,
                  wt.ticketId,
                  wt.epicId,
                  wt.result,
                  wt.createdAt,
                  wt.resolvedAt,
                  info != null ? info.connectedAt() : null,
                  info != null ? info.version() : null,
                  info != null ? info.buildTime() : null,
                  daemonOutdated(info, latestDaemon),
                  wt.admin,
                  wt.placement,
                  runnerRef(wt.runnerId, runnerNames),
                  wt.queuedAt,
                  wt.editor);
            })
        .toList();
  }

  /**
   * The workspaces that name one of these qits-projects tickets or epics — what a ticket panel over
   * there asks so it can say "an agent is already on this one, here is the way in", or "the work on
   * this one was integrated".
   *
   * <p><b>Not {@link #listWorkspaces} with a filter, and the difference is the cost.</b> That
   * listing exists to draw the branch tree of <em>one</em> repository: it refreshes the mirror,
   * lists containers and computes ahead/behind per row. This question spans every repository, is
   * asked about a page full of rows at once, and needs none of that — a link and the row it belongs
   * to is the whole answer. So it is a straight query and a thin shape, and it costs one statement.
   *
   * <p>Which rows come back is {@code WorkspaceRepository.findBySubjects}' to say, and it says it
   * there rather than here: every workspace the subject names, whatever its status and whatever its
   * container is doing. So each row carries its own status and its {@code resolvedAt}, and what to
   * make of a resolved one is the reader's question — this service has no view on whether a landed
   * workspace is still worth showing beside a ticket.
   */
  public List<WorkspaceSubjectRefDto> workspacesReferencing(
      Collection<String> ticketIds, Collection<String> epicIds) {
    return workspacesReferencing(ticketIds, epicIds, List.of());
  }

  /**
   * {@link #workspacesReferencing(Collection, Collection)}, also matching {@code workIds} — the
   * work id of any archetype (qits-112). A row matched twice is answered once.
   */
  public List<WorkspaceSubjectRefDto> workspacesReferencing(
      Collection<String> ticketIds, Collection<String> epicIds, Collection<String> workIds) {
    java.util.Map<Long, Workspace> rows = new java.util.LinkedHashMap<>();
    workspaceRepository.findBySubjects(ticketIds, epicIds).forEach(w -> rows.put(w.id, w));
    workspaceRepository.findByWorkIds(workIds).forEach(w -> rows.putIfAbsent(w.id, w));
    return rows.values().stream()
        .map(
            w ->
                new WorkspaceSubjectRefDto(
                    w.id,
                    w.repositoryId,
                    w.workspaceId,
                    w.branch,
                    w.ticketId,
                    w.epicId,
                    w.status == null ? null : w.status.name(),
                    w.resolvedAt,
                    w.workId,
                    w.entityId))
        .toList();
  }

  /**
   * Every open workspace bound to a work item, oldest first (qits-112): ACTIVE only, so integrated
   * and abandoned ones are left out and a work item shows at most one here.
   */
  public List<WorkItemWorkspaceDto> openWorkspaces() {
    return workspaceRepository.findOpenBoundToWork().stream().map(WorkspaceService::workItemView).toList();
  }

  /**
   * Every workspace of one work item, whatever its status, newest first (qits-112). {@code workRef}
   * is the item's entity id or its qualified id; see {@code WorkspaceRepository.findByWorkRef}.
   */
  public List<WorkItemWorkspaceDto> workItemWorkspaces(String workRef) {
    return workspaceRepository.findByWorkRef(workRef).stream()
        .map(WorkspaceService::workItemView)
        .toList();
  }

  private static WorkItemWorkspaceDto workItemView(Workspace w) {
    return new WorkItemWorkspaceDto(
        w.id,
        w.workId,
        w.entityId,
        w.repositoryId,
        w.workspaceId,
        w.branch,
        w.status == null ? null : w.status.name(),
        w.runtimeStatus == null ? null : w.runtimeStatus.name(),
        w.createdAt,
        w.resolvedAt);
  }

  /**
   * Orders live daemon connections by build recency: build time first (the {@code -SNAPSHOT}
   * tiebreaker the registry epic is built on), then version as a stable last resort. Entries with
   * no reported build time are filtered out by {@link #latestDaemon} before this ever sees them.
   */
  private static final Comparator<WorkspaceDaemonInfo.Info> DAEMON_BUILD_ORDER =
      Comparator.comparing(WorkspaceDaemonInfo.Info::buildTime)
          .thenComparing(i -> i.version() == null ? "" : i.version());

  /**
   * The newest daemon build among live connections, or {@code null} when none reports a build time
   * (older images) — an unknowable build can't be "the latest", so it never wins.
   */
  private static WorkspaceDaemonInfo.Info latestDaemon(Collection<WorkspaceDaemonInfo.Info> all) {
    return all.stream().filter(i -> i.buildTime() != null).max(DAEMON_BUILD_ORDER).orElse(null);
  }

  /**
   * Whether {@code info}'s daemon build is strictly older than {@code latest} — {@code true} only
   * when both build times are known and this one precedes the newest. Returns {@code null} (not
   * {@code false}) whenever the two can't be compared (no live daemon, no reported build time on
   * either side, or no newer build exists), so an uncomparable or up-to-date workspace shows no
   * warning rather than a misleading verdict.
   */
  private static Boolean daemonOutdated(
      WorkspaceDaemonInfo.Info info, WorkspaceDaemonInfo.Info latest) {
    if (info == null || info.buildTime() == null || latest == null || latest.buildTime() == null) {
      return null;
    }
    return info.buildTime().isBefore(latest.buildTime()) ? Boolean.TRUE : null;
  }

  /**
   * A single active workspace's current DTO (runtime status computed live), or 404.
   *
   * <p><b>The editor is read directly and every other workspace through its repository's listing</b>,
   * which is not an optimisation but the only way the editor is readable at all. The listing is the
   * older path and stays exactly as it was: it opens with {@code repositories.require(repoId)} and
   * refreshes a git mirror, because ahead/behind against a parent branch is most of what it answers.
   * The editor's row belongs to no repository — it carries a sentinel id ({@code
   * EditorWorkspace.REPOSITORY_ID}) precisely because the column is not nullable — so that first
   * line could only ever 404, and a read that 404s for a row which exists is a defect rather than a
   * limitation. This is the regression the singleton editor introduced: the editor used to be a
   * per-project workspace the listing covered, and the same listing now misses it.
   *
   * <p><b>The difference is what cannot be known rather than what is skipped.</b> An editor has no
   * branch, no parent and no repository, so {@code ahead}, {@code behind} and the repository's main
   * branch are null and {@code conflictsWithParent} is false — not because they were too expensive
   * to compute, but because there is nothing to compute them against. Everything keyed by the row
   * itself is answered exactly as the listing answers it: the live container check, clean/dirty,
   * agent activity and the daemon's identity, all of them RUNNING-only on the same contract.
   *
   * <p>Five endpoints reach this method — the by-id read and the four container verbs that return
   * the refreshed workspace — so fixing it here is what makes all five answer for the editor rather
   * than each of them growing a special case.
   */
  public WorkspaceDto getWorkspace(Long id) {
    Workspace workspace = requireActive(id);
    if (workspace.editor) {
      return describeEditor(workspace);
    }
    return listWorkspaces(workspace.repositoryId).stream()
        .filter(w -> id.equals(w.id()))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + id));
  }

  /**
   * The editor row as a {@link WorkspaceDto}, built without touching a repository or a git mirror.
   *
   * <p>The live half is read the same way {@link #listWorkspaces} reads it and from the same ports,
   * so the two agree about a workspace that is up: the container is asked for BY NAME rather than by
   * listing a repository's containers, which is the one substitution the sentinel forces and the
   * same composition {@code EditorKeepalive} and the editor proxy already make.
   */
  private WorkspaceDto describeEditor(Workspace workspace) {
    WorkspacePlacements.requireDirectAllowed(workspace);
    WorkspaceRuntimeStatus runtime =
        containers.isRunning(containers.containerName(workspace.workspaceId, workspace.repositoryId))
            ? WorkspaceRuntimeStatus.RUNNING
            : workspace.runtimeStatus == WorkspaceRuntimeStatus.RUNNING
                ? WorkspaceRuntimeStatus.STOPPED
                : workspace.runtimeStatus;
    boolean live = runtime == WorkspaceRuntimeStatus.RUNNING;
    Boolean clean =
        live && gitStatus.isResolvable() ? gitStatus.get().isClean(workspace.id).orElse(null) : null;
    AgentActivityState activity =
        live && agentActivity.isResolvable()
            ? agentActivity.get().activityFor(workspace.id).orElse(null)
            : null;
    WorkspaceDaemonInfo.Info info =
        live && daemonInfo.isResolvable() ? daemonInfo.get().lookup(workspace.id).orElse(null) : null;
    WorkspaceDaemonInfo.Info latestDaemon =
        daemonInfo.isResolvable() ? latestDaemon(daemonInfo.get().all()) : null;
    return new WorkspaceDto(
        workspace.id,
        workspace.workspaceId,
        workspace.parent,
        workspace.branch,
        // No repository ⇒ no main branch to be ahead of, and no parent to conflict with. Null is the
        // honest answer here, and the same one every other unknown on this DTO uses.
        null,
        null,
        null,
        false,
        workspace.status,
        runtime,
        workspace.runtimeError,
        clean,
        activity,
        workspace.preamble,
        workspace.ticketId,
        workspace.epicId,
        workspace.result,
        workspace.createdAt,
        workspace.resolvedAt,
        info != null ? info.connectedAt() : null,
        info != null ? info.version() : null,
        info != null ? info.buildTime() : null,
        daemonOutdated(info, latestDaemon),
        workspace.admin,
        workspace.placement,
        // The editor is always DIRECT (ck_workspace_runner_posture), so it is on no runner.
        null,
        workspace.queuedAt,
        true);
  }

  /**
   * A DIRECT row's runtime status as listed: RUNNING when qits-containers' listing has it running,
   * a stale persisted RUNNING read as STOPPED, otherwise the persisted status. Behind the router's
   * refusal, like every other DIRECT read (qits-780).
   */
  private static WorkspaceRuntimeStatus directRuntime(Workspace wt, Set<String> runningIds) {
    WorkspacePlacements.requireDirectAllowed(wt);
    return runningIds.contains(wt.workspaceId)
        ? WorkspaceRuntimeStatus.RUNNING
        : wt.runtimeStatus == WorkspaceRuntimeStatus.RUNNING
            ? WorkspaceRuntimeStatus.STOPPED
            : wt.runtimeStatus;
  }

  /**
   * The runner a row is placed on, with its name from {@code names}; null when the row is on none.
   * A runner row that is gone leaves the id with no name.
   */
  private static WorkspaceRunnerRefDto runnerRef(UUID runnerId, Map<UUID, String> names) {
    return runnerId == null ? null : new WorkspaceRunnerRefDto(runnerId, names.get(runnerId));
  }

  /**
   * A RUNNER row's runtime status as read: the persisted one, with {@code UNAVAILABLE} laid over it
   * when the row has a runner and that runner is not there (offline past the grace). Never written:
   * a reconnect clears it with no write. A row with no runner waits for any and is never
   * UNAVAILABLE.
   */
  private WorkspaceRuntimeStatus runnerRuntime(Workspace row) {
    return row.runnerId != null && !runnerPresent(row.runnerId)
        ? WorkspaceRuntimeStatus.UNAVAILABLE
        : row.runtimeStatus;
  }

  /** Whether the runner is connected or within its grace; false with no {@link RunnerPlacement}. */
  private boolean runnerPresent(UUID runnerId) {
    return runnerPlacement.isResolvable() && runnerPlacement.get().presence(runnerId);
  }

  /**
   * The ACTIVE workspace with this id, or 404. One lookup: the id is the identity, so no repository
   * is needed to select the row — the repository is read <em>off</em> it, by the callers that build
   * container names and on-disk paths from the label.
   */
  private Workspace requireActive(Long id) {
    if (id == null) {
      throw new NotFoundException("Workspace not found: null");
    }
    return workspaceRepository
        .findActiveById(id)
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + id));
  }

  /**
   * Whether {@code branch} — workspace-backed or plain — can be removed with no data loss: it is
   * not its own parent (the main branch can't be cleaned up), has no unmerged commits ({@code ahead
   * == 0} against its parent), a clean working tree when workspace-backed, and no other workspace
   * forks from it. A plain branch's parent is the repository's main branch; a workspace's is its
   * fork point. This is the single criterion the UI, the cleanup endpoint and post-integrate
   * cleanup all use.
   */
  public boolean canCleanupBranch(String repoId, String branch, String mainBranch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      return false;
    }
    Workspace wt = findWorkspaceByBranch(repoId, branch);
    String parent =
        (wt != null && wt.parent != null && !wt.parent.isBlank()) ? wt.parent : mainBranch;
    // No usable parent, or the branch *is* its parent (e.g. main): nothing to merge into, never
    // safe.
    if (parent == null || parent.isBlank() || parent.equals(branch)) {
      return false;
    }
    RepoMirror mirror = mirrors.of(repoId);
    // Forced, not the freshness window: this decides whether a branch is deleted, and an
    // ahead/behind computed against a mirror that missed the last push would delete unmerged work.
    // A refresh that fails leaves the counts UNKNOWN, and unknown refuses — which is the direction
    // this question has to fail in.
    refreshNowQuietly(mirror);
    // ahead == null means git couldn't compare; ahead > 0 means commits not yet in the parent.
    Integer ahead = aheadBehind(mirror, parent, branch).ahead();
    if (ahead == null || ahead != 0) {
      return false;
    }
    if (wt != null) {
      // The working tree lives in the container; a dirty tree or unpushed commits (which the
      // origin-side ahead/behind above cannot see) both mean cleanup could destroy work.
      if (!isWorkspaceClean(repoId, wt) || !isFullyPushed(mirror, wt)) {
        return false;
      }
    }
    return !hasChildren(repoId, branch);
  }

  /**
   * Whether the container's HEAD equals the branch's ref in the origin — i.e. every commit made
   * inside the container has been pushed. The origin-side ahead/behind can't see container-local
   * commits, so without this a "safe" cleanup could delete unpushed work. A missing container means
   * nothing is left to lose, so treat it as pushed.
   */
  private boolean isFullyPushed(RepoMirror mirror, Workspace wt) {
    if (!directContainerExists(wt)) {
      return true;
    }
    String branch = wt.branch;
    Long rowId = wt.id;
    if (branch == null || branch.isBlank()) {
      return true;
    }
    // The daemon reports its head on every GitStatus frame and auto-pushes committed work, so
    // comparing that against the origin's ref answers this without reaching into the container.
    // Unknown — no live daemon, nothing reported yet, or no registry bean at all (cli, tests) — is
    // NOT "in sync": refuse, exactly as an unreadable container used to.
    Optional<String> reportedHead =
        gitStatus.isUnsatisfied() ? Optional.empty() : gitStatus.get().head(rowId);
    if (reportedHead.isEmpty()) {
      return false;
    }
    try {
      // ls-remote, not the mirror: this compares a container's HEAD against what the git host holds
      // right now, and a cached answer here would call unpushed work pushed.
      return mirror
          .remoteBranchSha(branch)
          .map(sha -> reportedHead.get().trim().equals(sha))
          .orElse(false);
    } catch (GitMirrorException e) {
      return false;
    }
  }

  /** The parent a branch is compared against and how far it is ahead/behind it. */
  public record BranchSummary(String parent, Integer ahead, Integer behind) {}

  /**
   * Resolves a branch's parent — its workspace's fork point when workspace-backed, otherwise the
   * repository's {@code mainBranch} — and how far it is ahead of and behind that parent. Returns a
   * {@code null} parent (and zero counts) for the main branch itself or when no parent resolves.
   * Used to drive the branch tree's ahead/behind connector and commits popover for every branch,
   * including those without a workspace.
   */
  public BranchSummary summarize(String repoId, String branch, String mainBranch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      return new BranchSummary(null, 0, 0);
    }
    Workspace wt = findWorkspaceByBranch(repoId, branch);
    String parent =
        (wt != null && wt.parent != null && !wt.parent.isBlank()) ? wt.parent : mainBranch;
    if (parent == null || parent.isBlank() || parent.equals(branch)) {
      return new BranchSummary(null, 0, 0);
    }
    RepoMirror mirror = mirrors.of(repoId);
    refreshQuietly(mirror);
    AheadBehind ab = aheadBehind(mirror, parent, branch);
    return new BranchSummary(parent, ab.ahead(), ab.behind());
  }

  /**
   * True when the workspace container's working tree has no staged or unstaged changes. The
   * container <em>is</em> the working tree, so no container means there is nothing uncommitted to
   * destroy — clean, symmetric with {@link #isFullyPushed}'s absent-means-pushed. A failed status
   * probe on a live container stays dirty: that state is genuinely unknown, never delete blindly.
   */
  private boolean isWorkspaceClean(String repoId, Workspace wt) {
    if (!directContainerExists(wt)) {
      return true;
    }
    // Only an explicit CLEAN from the daemon counts. Unknown — no live daemon, none reported yet,
    // or no registry bean at all — is treated as dirty and refuses the operation. The host has no
    // second opinion to fall back on any more: the `docker exec git status` this used to run went
    // into the daemon with the rest of the in-container git.
    return !gitStatus.isUnsatisfied() && gitStatus.get().isClean(wt.id).orElse(false);
  }

  /**
   * Refuses an operation that would clobber or silently discard uncommitted work: throws a 400 when
   * the workspace's container has a dirty working tree. The daemon-reported Clean/Dirty state
   * already hides/reroutes these actions in the UI; this is the matching server-side guard so a
   * direct API call can't bypass it. Symmetric with {@link #isWorkspaceClean} — an absent container
   * is clean (nothing uncommitted to lose), so a stopped workspace is never blocked here.
   */
  private void requireCleanWorkingTree(String repoId, Workspace wt, String operation) {
    if (!isWorkspaceClean(repoId, wt)) {
      throw new BadRequestException(
          "Cannot "
              + operation
              + " workspace '"
              + wt.workspaceId
              + "': it has uncommitted changes. Commit or discard them first.");
    }
  }

  /**
   * Pre-flight guard shared by branch integration ({@link #mergeBranch}) and workspace integration
   * ({@link #mergeWorkspace}) — and therefore by the MCP {@code integrateBranch} tool, which routes
   * through {@code mergeBranch}. Integration merges the source branch's <em>origin</em> ref (inside
   * the target's workspace), so before that ref is read this makes it faithful to the live source
   * workspace:
   *
   * <ol>
   *   <li>refuses a dirty working tree with a 400 — the origin-side merge would silently leave the
   *       workspace's uncommitted work behind; and
   *   <li>pushes the container's branch so every commit made inside the container reaches the
   *       origin ref the merge reads. The push is <em>not</em> swallowed: a failure aborts the
   *       whole integration, because a silently-skipped push would integrate a stale ref.
   * </ol>
   *
   * A {@code null} source workspace or one with no live container — a plain branch or a stopped
   * workspace — has nothing uncommitted to lose and a provably complete origin ref (nothing ever
   * ran to advance it beyond origin), so it is a no-op. This deliberately does <em>not</em> require
   * the source to be up to date with the target: integrating a diverged but cleanly-mergeable
   * branch (yielding a merge commit, or a reported conflict) is a supported flow.
   *
   * <p>Takes the already-resolved {@link Workspace} rather than re-looking-it-up by branch: {@code
   * mergeWorkspace} identifies its source by {@code workspaceId}, and two active workspaces could
   * in principle share a branch — re-resolving by branch could push/clean-check the wrong
   * container.
   */
  private void requireSyncedSourceForIntegration(String repoId, Workspace sourceWorkspace) {
    if (sourceWorkspace == null || !directContainerExists(sourceWorkspace)) {
      return;
    }
    // No push from here: requireCleanWorkingTree has just established the daemon reports CLEAN,
    // and the daemon auto-pushes committed work as it goes, so origin already has the source
    // branch. The `docker exec git push` this used to run was a host-side second opinion on state
    // the daemon owns.
    requireCleanWorkingTree(repoId, sourceWorkspace, "integrate");
  }

  /**
   * Whether a row's container exists on the platform host, asked of qits-containers only behind the
   * router's refusal (qits-780). A RUNNER row's container is on its runner's node, never there, so it
   * answers false with no call — what the by-name lookup on the platform host always answered for it
   * — and the absent-means-nothing-to-lose readings above carry on as they did.
   */
  private boolean directContainerExists(Workspace wt) {
    if (wt.placement == WorkspacePlacement.RUNNER) {
      return false;
    }
    WorkspacePlacements.requireDirectAllowed(wt);
    return containers.exists(containers.containerName(wt.workspaceId, wt.repositoryId));
  }

  /** True when another workspace forks from {@code branch} (i.e. lists it as its parent). */
  private boolean hasChildren(String repoId, String branch) {
    for (Workspace other : workspaceRepository.findActiveByRepositoryId(repoId)) {
      if (branch.equals(other.parent)) {
        return true;
      }
    }
    return false;
  }

  /**
   * After a host-side integration/merge advanced {@code targetBranch}'s origin ref, tell the
   * workspace that owns it (if any) to pull the update into its container — so its checkout catches
   * up right away instead of lagging until the next host git op (docs/epics/qits-workspace-daemon/
   * bidirectional auto-sync). Best-effort and fire-and-forget: no target workspace, no backend impl
   * (cli/tests), or no live daemon all short-circuit to a no-op. The daemon only fast-forwards, so
   * a target tree that turned dirty in the race window is left intact.
   */
  private void notifyIncomingMerge(String repoId, String targetBranch) {
    Workspace target = findWorkspaceByBranch(repoId, targetBranch);
    if (target == null || gitSync.isUnsatisfied()) {
      return;
    }
    gitSync.get().pullFromOrigin(target.id, targetBranch);
  }

  /** The workspace that owns {@code branch}, or null when none matches. */
  private Workspace findWorkspaceByBranch(String repoId, String branch) {
    if (branch == null) {
      return null;
    }
    for (Workspace wt : workspaceRepository.findActiveByRepositoryId(repoId)) {
      if (branch.equals(wt.branch)) {
        return wt;
      }
    }
    return null;
  }

  /**
   * Counts how far {@code branch} is ahead of and behind its {@code parent} branch. Runs in the
   * <b>mirror</b>, which holds every branch of the repository as a ref because it is a mirror.
   * Returns {@code (0, 0)} when the two names are the same or either is missing, and {@code (null,
   * null)} if git can't resolve a ref there — the caller refreshes first, so an unresolvable ref
   * means the git host does not have it either.
   */
  private AheadBehind aheadBehind(RepoMirror mirror, String parent, String branch) {
    if (parent == null
        || branch == null
        || parent.isBlank()
        || branch.isBlank()
        || parent.equals(branch)) {
      return new AheadBehind(0, 0);
    }
    try {
      var counts = mirror.aheadBehind("refs/heads/" + parent, "refs/heads/" + branch);
      return new AheadBehind(counts.ahead(), counts.behind());
    } catch (GitMirrorException e) {
      return new AheadBehind(null, null);
    }
  }

  private record AheadBehind(Integer ahead, Integer behind) {}

  /**
   * Whether merging {@code parent} into {@code branch} would produce conflicts, decided by a real
   * three-way merge in the mirror's object store via {@code git merge-tree --write-tree} (no working
   * tree touched). An unresolvable ref or any other error is treated as "no conflict" so we never
   * raise a false warning.
   */
  private boolean wouldConflict(RepoMirror mirror, String parent, String branch) {
    if (parent == null
        || branch == null
        || parent.isBlank()
        || branch.isBlank()
        || parent.equals(branch)
        || parent.startsWith("-")
        || branch.startsWith("-")) {
      return false;
    }
    try {
      return !mirror.previewMerge("refs/heads/" + branch, "refs/heads/" + parent).clean();
    } catch (GitMirrorException e) {
      return false;
    }
  }

  /**
   * Refresh a mirror inside the freshness window, and treat a git host that is briefly away as a
   * slightly stale number rather than as an error. Every caller of this reads counts for a screen;
   * nothing that <i>decides</i> anything comes through here.
   */
  private void refreshQuietly(RepoMirror mirror) {
    try {
      mirror.refresh();
    } catch (GitMirrorException e) {
      LOG.debugf(e, "could not refresh the mirror of %s", mirror.repoId());
    }
  }

  /** {@link #refreshQuietly} ignoring the freshness window — for the decisions, not the screens. */
  private void refreshNowQuietly(RepoMirror mirror) {
    try {
      mirror.refreshNow();
    } catch (GitMirrorException e) {
      LOG.debugf(e, "could not refresh the mirror of %s", mirror.repoId());
    }
  }

  public Workspace createWorkspace(
      String repoId, String workspaceId, String parent, String branch) {
    return createWorkspace(repoId, workspaceId, parent, branch, null, false);
  }

  public Workspace createWorkspace(
      String repoId, String workspaceId, String parent, String branch, String preamble) {
    return createWorkspace(repoId, workspaceId, parent, branch, preamble, false);
  }


  /**
   * Creates a workspace for a branch. Normally {@code branch} is a <em>new</em> branch this
   * workspace owns, forked off {@code parent} — a fresh ref is created in the origin. When {@code
   * adoptExisting} is set and {@code branch} already exists in the origin, the workspace instead
   * <em>adopts</em> that existing branch in place: no ref is created, the row is simply recorded
   * over it (the branch-list "Create workspace" button on a branch that has no workspace yet). The
   * container is provisioned lazily from the branch ref on first use either way.
   *
   * <p>An ordinary workspace, which is what every caller that says nothing about a posture gets:
   * {@code admin} is false, so its container is launched with no docker socket. The admin posture
   * arrives through the seven-argument form below and, from there, through {@link #recordWorkspace}
   * — there is deliberately no overload of this shape that takes it, because two adjacent booleans
   * a caller passes positionally is how {@code branchTree} would one day be read as {@code admin}.
   */
  @Transactional
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting) {
    return recordWorkspace(
        repoId,
        workspaceId,
        parent,
        branch,
        preamble,
        adoptExisting,
        false,
        WorkspaceSubject.none(),
        null,
        null);
  }

  /**
   * The row-writing core of every create: the guards, the branch push and the row itself, with the
   * posture the caller asked for.
   *
   * <p>Private, and it is the only writer of {@code Workspace.admin}. A public overload carrying the
   * flag would be one whose call sites are read positionally; keeping it here means the two callers
   * that can set it are both in this file and both visible in one screen.
   *
   * <p>It is also the only writer of the {@link WorkspaceSubject}, for the milder version of the
   * same reason: the two ids are same-typed and adjacent, so they travel as a record rather than as
   * a pair of positional strings.
   *
   * <p>And the only writer of the Git ref list a new workspace starts with: {@code gitRefs} as
   * stated (already validated), or the workspace's own branch when null. Never the repository's
   * default branch: a stated entry that covers it is dropped and logged, and a workspace on the
   * default branch gets an empty list. Writing the row is also what narrows every other open
   * workspace that could push this branch ({@link GitRefScopes}).
   */
  private Workspace recordWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean admin,
      WorkspaceSubject subject,
      List<String> gitRefs,
      WorkspacePlacement placement) {
    var repo = repositories.require(repoId);

    // `workspaceId` becomes a path segment under the repo's workspaces dir, so it must be a strict
    // slug: no slashes/dots/dashes-leading that could traverse out of the dir or smuggle a git
    // flag.
    if (!workspaceId.matches("[A-Za-z0-9_-]{1,64}") || workspaceId.startsWith("-")) {
      throw new BadRequestException("Invalid workspace id: " + workspaceId);
    }

    // Where the container runs, decided once and here (qits-837, qits-774): a regular workspace
    // always runs on a runner and admin stays DIRECT; whether any runner is eligible is not asked —
    // with none, the RUNNER row simply waits QUEUED. A stated placement that contradicts the rule is
    // refused before the branch is pushed, so it costs nothing. The editor is written by its own door.
    refuseUnplaceable(placement, admin);
    WorkspacePlacement placed = WorkspacePlacements.forNewRow(admin, false);
    LOG.infof("Workspace %s/%s placement=%s", repoId, workspaceId, placed);

    RepoMirror mirror = mirrors.of(repoId);

    // Resolved rows linger (soft delete), so only an ACTIVE workspace blocks the id — a resolved
    // one
    // can be reused.
    if (workspaceRepository.existsActiveByRepositoryAndWorkspaceId(repoId, workspaceId)) {
      throw new BadRequestException("Workspace already exists: " + workspaceId);
    }

    // `parent` is the branch to fork from; `branch` is the new branch the workspace owns.
    // Each workspace gets its own branch so two workspaces never commit to the same branch.
    String parentBranch = (parent == null || parent.isBlank()) ? defaultMainBranch(repo) : parent;
    String newBranch = (branch == null || branch.isBlank()) ? workspaceId : branch;
    // Both are user-supplied and passed to git: reject dash-leading names so they can't be smuggled
    // in as flags (argv flag injection).
    if (parentBranch.startsWith("-") || newBranch.startsWith("-")) {
      throw new BadRequestException("Invalid branch name");
    }

    // The branch is the resource being claimed, so the check belongs here — after `branch` and
    // `workspaceId` have been reconciled, not before. The id guard above is about the surrogate
    // label; it says nothing about the branch, because `branch` is an independent request field
    // that merely *defaults* to the id. Under the ordinary usage where the two coincide the two
    // guards agree, which is why the missing one went unnoticed; they stop coinciding the moment a
    // caller sets both, and `adoptExisting` then skips the ref creation that was the only thing
    // standing in the way. UQ_workspace_active_branch (V3) enforces the same rule structurally.
    if (workspaceRepository.existsActiveByRepositoryAndBranch(repoId, newBranch)) {
      throw new ConflictException("Branch already has an active workspace: " + newBranch);
    }
    // At most one ACTIVE workspace per work item (uq_workspace_active_work, V11). Checked here,
    // before the branch is pushed, so a refused create leaves no branch behind. The index is what
    // holds under a race.
    WorkspaceSubject named = subject == null ? WorkspaceSubject.none() : subject.normalized();
    refuseSecondActiveForWork(named.workId());

    // Only the durable state is created here: the branch, PUSHED to the git host (so ahead/behind
    // and the merge-tree conflict probe both have a ref to read, and so the ordinary post-receive
    // fires for it like every other push) plus the row below. No container, no clone — provisioning
    // is lazy: first use goes through ensureContainer, which materializes the container from this
    // branch. That keeps creation free of docker.
    //
    // Adoption: when asked to adopt and the branch already exists (e.g. a branch pushed or created
    // outside qits), skip the creation and record the workspace over the existing branch. The
    // normal path still creates it — and errors loudly if it is already there — so a typo'd
    // "branch off" name is never silently swallowed.
    if (!(adoptExisting && mirror.remoteHasBranch(newBranch))) {
      createBranchOnHost(mirror, newBranch, parentBranch);
    }

    Workspace workspace = new Workspace();
    workspace.workspaceId = workspaceId;
    workspace.repositoryId = repoId;
    workspace.parent = parentBranch;
    workspace.branch = newBranch;
    workspace.status = WorkspaceStatus.ACTIVE;
    // Every row is written STOPPED. A RUNNER row is still queued by its create — creating it IS the
    // request a runner takes (the qits-ci runner model) — but by the RUNNER start, once this
    // transaction committed (queuedOnRunner): that start is the one path to QUEUED, and it builds
    // the edge plane and mints the workspace token first (qits-625), so no row is ever claimable
    // without a token. A DIRECT row is started by whoever created it; see createAndStartWorkspace.
    workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
    workspace.preamble = preamble;
    // The posture, written once and never again: no verb promotes a workspace to admin later, so
    // the socket a container gets is the one the request that created it asked for. See
    // Workspace.admin.
    workspace.admin = admin;
    // Written once and never changed, like the posture above (Workspace.placement). A RUNNER row is
    // placed on no runner yet: a runner takes it from the queue at its first start.
    workspace.placement = placed;
    // What this workspace is for, where a dispatch said so. Blanks normalise to null: an empty id
    // is not a subject, and a row claiming one would render a link to nothing.
    workspace.ticketId = named.ticketId();
    workspace.epicId = named.epicId();
    workspace.entityId = named.entityId();
    workspace.workId = named.workId();
    // What the container may push (contract C4): the stated list, or its own branch. Never the
    // default branch: it moves only through a release request, never by an agent's push.
    String defaultBranch = defaultMainBranch(repo);
    List<String> allowed;
    if (gitRefs == null) {
      allowed = GitRefs.defaultFor(newBranch, defaultBranch);
    } else {
      allowed = GitRefs.withoutDefaultBranch(gitRefs, defaultBranch);
      if (allowed.size() < gitRefs.size()) {
        List<String> dropped = new ArrayList<>(gitRefs);
        dropped.removeAll(allowed);
        LOG.warnf(
            "Workspace %s/%s may not push the default branch %s; dropped %s from the stated Git refs",
            repoId, workspaceId, defaultBranch, dropped);
      }
    }
    workspace.gitRefs = GitRefs.write(allowed);
    workspaceRepository.persist(workspace);
    recordEvent(workspace, WorkspaceEventType.CREATED, newBranch, parentBranch, null);
    // The branch is this workspace's now, so no other open workspace in the project may push it.
    gitRefScopes.narrowFor(workspace, repo);

    WorkspaceMetadata metadata = new WorkspaceMetadata();
    metadata.workspaceId = workspaceId;
    metadata.parent = parentBranch;
    workspaceMetadata.write(repoId, metadata);

    return workspace;
  }

  /**
   * Refuses a stated placement that contradicts {@link WorkspacePlacements#forNewRow}: 400 for an
   * admin workspace asked onto a runner (it holds the host's docker socket and always runs DIRECT,
   * {@code ck_workspace_runner_posture}), and 400 {@code DIRECT_PLACEMENT_REFUSED} for a regular
   * workspace asked onto the direct path (qits-774: a regular workspace runs on a runner). The
   * agreeing statements — DIRECT for admin, RUNNER for a regular workspace — and a null placement
   * (nothing stated) pass. The editor is written by its own door, never through here.
   */
  private void refuseUnplaceable(WorkspacePlacement placement, boolean admin) {
    if (placement == WorkspacePlacement.RUNNER && admin) {
      throw new BadRequestException(
          "An admin workspace holds the host's docker socket and always runs DIRECT; it cannot be"
              + " placed on a runner");
    }
    if (placement == WorkspacePlacement.DIRECT && !admin) {
      throw RunnerRefusals.directPlacementRefused();
    }
  }

  /** 409 when the work item already has an ACTIVE workspace; nothing for a null work id. */
  private void refuseSecondActiveForWork(String workId) {
    if (workspaceRepository.existsActiveByWorkId(workId)) {
      throw new ConflictException("Work item already has an active workspace: " + workId);
    }
  }

  /**
   * Extended create form for aggregate repositories. A branch-tree workspace forks the wrapper and
   * every registered repository reachable from its committed submodule declarations, then adds the
   * workspace hand-off document to the wrapper before the ordinary workspace row is recorded.
   *
   * <p>Deliberately NOT one {@code @Transactional}, for the reason {@link #landWorkspace} spells
   * out: the tree is a fetch, a worktree and a push <em>per repository</em>, and a wrapper of twenty
   * submodules outlasts Narayana's transaction timeout — which would surface as a transaction
   * failure rather than as anything a caller could read. The guards read in one short transaction,
   * the git work runs outside every transaction, and the row is written in another.
   */
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree) {
    return createWorkspace(
        repoId, workspaceId, parent, branch, preamble, adoptExisting, branchTree, false);
  }

  /**
   * The same create, told what the workspace is <b>for</b> — the ticket or epic an agent dispatch
   * was about ({@link WorkspaceSubject}).
   *
   * <p>An overload of its own rather than a ninth positional argument on the admin form below, and
   * the two do not meet: a dispatch never asks for the docker socket, and the record's type is what
   * keeps this signature apart from the {@code boolean}-tailed one at every call site. The subject
   * is written once, by {@link #recordWorkspace}, exactly as the posture is.
   */
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree,
      WorkspaceSubject subject) {
    return createWorkspace(
        repoId, workspaceId, parent, branch, preamble, adoptExisting, branchTree, false, subject);
  }

  /**
   * The same create, told the <b>posture</b> the workspace is to run in.
   *
   * <p>{@code admin} is the request to bind the host's docker socket into this workspace's
   * container, so that platform administration can be done from inside it — the one privilege a
   * workspace can be granted, and a container holding it is root-equivalent on the host. It is a
   * property of the workspace from here on: it is written to the row (the only writer is {@link
   * #recordWorkspace}), read back at every ensure by {@link WorkspacePostures}, and no verb
   * promotes a workspace to it afterwards. Everything else about an admin workspace — its image,
   * its user, its limits, its mounts — is what every other workspace gets.
   *
   * <p>The eighth argument rather than a widened seventh: the seven-argument form above is what
   * every existing caller means, and it keeps meaning it. Who is <em>allowed</em> to ask for admin
   * is the API boundary's question, not this method's — {@code WorkspaceController} answers it.
   */
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree,
      boolean admin) {
    return createWorkspace(
        repoId,
        workspaceId,
        parent,
        branch,
        preamble,
        adoptExisting,
        branchTree,
        admin,
        WorkspaceSubject.none());
  }

  /** The posture and the subject together, with the default Git refs (the workspace's branch). */
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree,
      boolean admin,
      WorkspaceSubject subject) {
    return createWorkspace(
        repoId,
        workspaceId,
        parent,
        branch,
        preamble,
        adoptExisting,
        branchTree,
        admin,
        subject,
        null);
  }

  /**
   * The posture, the subject and the Git refs together, placed DIRECT.
   *
   * <p>{@code gitRefs} is what the workspace's container may push (contract C4) — exact refs and
   * trailing {@code /*} patterns. Null means the workspace's own branch. It is checked before any
   * ref is pushed, so a bad list costs nothing.
   */
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree,
      boolean admin,
      WorkspaceSubject subject,
      List<String> gitRefs) {
    return createWorkspace(
        repoId,
        workspaceId,
        parent,
        branch,
        preamble,
        adoptExisting,
        branchTree,
        admin,
        subject,
        gitRefs,
        null);
  }

  /**
   * The widest form, and the only one taking a stated {@code placement}: where the container runs,
   * {@code DIRECT} or {@code RUNNER} (epic qits-624). It is never a choice — admin is DIRECT and a
   * regular workspace RUNNER ({@link WorkspacePlacements#forNewRow}), null meaning "the rule" — so a
   * statement can only agree or be refused: RUNNER with {@code admin} is a 400, and DIRECT without it
   * a 400 {@code DIRECT_PLACEMENT_REFUSED}, both before any ref is pushed. No eligible runner is not
   * a refusal: the RUNNER row is written and waits QUEUED. Every other overload delegates here with
   * null.
   *
   * <p>A RUNNER row is queued by its create once the row committed, through the RUNNER start: the
   * edge plane is built and the workspace token minted first (qits-625), the row goes QUEUED with
   * {@code queued_at}, and the runners are told. A start that cannot do that leaves the row FAILED
   * saying why, and the create still stands. A DIRECT row is written STOPPED.
   */
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree,
      boolean admin,
      WorkspaceSubject subject,
      List<String> gitRefs,
      WorkspacePlacement placement) {
    return queuedOnRunner(
        recordPlaced(
            repoId,
            workspaceId,
            parent,
            branch,
            preamble,
            adoptExisting,
            branchTree,
            admin,
            subject,
            gitRefs,
            placement));
  }

  /**
   * Queues a RUNNER row its create just committed, through the RUNNER start and nothing beside it
   * ({@link #beginRunnerStart}, unnarrated: the row's status is its progress), so the create path
   * gets the same edge-plane check and the same token mint every start gets (qits-625). Answers the
   * row as the start left it: QUEUED, or FAILED with the reason.
   *
   * <p><b>A start refused after the row exists does not undo the create</b>, for the reason {@link
   * #createAndStartWorkspace} gives; it is logged here, and the row says what happened.
   */
  private Workspace queuedOnRunner(Workspace created) {
    if (created.placement != WorkspacePlacement.RUNNER) {
      return created;
    }
    try {
      beginRunnerStart(created, false);
    } catch (RuntimeException refused) {
      LOG.warnf(
          refused,
          "Workspace %s/%s was created but could not be queued for a runner",
          created.repositoryId,
          created.workspaceId);
    }
    return QuarkusTransaction.requiringNew()
        .call(() -> workspaceRepository.findByIdOptional(created.id).orElse(created));
  }

  /** {@link #createWorkspace}'s widest form up to the commit: the row as written, not yet signalled. */
  private Workspace recordPlaced(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree,
      boolean admin,
      WorkspaceSubject subject,
      List<String> gitRefs,
      WorkspacePlacement placement) {
    List<String> stated = gitRefs == null ? null : GitRefs.validated(gitRefs);
    // A call on `this` never reaches the interceptor, so each delegation below opens its own
    // transaction explicitly rather than relying on the annotation of the method it calls.
    if (!branchTree) {
      return QuarkusTransaction.requiringNew()
          .call(
              () ->
                  recordWorkspace(
                      repoId,
                      workspaceId,
                      parent,
                      branch,
                      preamble,
                      adoptExisting,
                      admin,
                      subject,
                      stated,
                      placement));
    }
    if (adoptExisting) {
      throw new BadRequestException("A branch-tree workspace cannot adopt an existing branch");
    }
    if (workspaceId == null
        || !workspaceId.matches("[A-Za-z0-9_-]{1,64}")
        || workspaceId.startsWith("-")) {
      throw new BadRequestException("Invalid workspace id: " + workspaceId);
    }
    var root = repositories.require(repoId);
    String parentBranch = (parent == null || parent.isBlank()) ? defaultMainBranch(root) : parent;
    String newBranch = (branch == null || branch.isBlank()) ? workspaceId : branch;
    if (parentBranch.startsWith("-") || newBranch.startsWith("-")) {
      throw new BadRequestException("Invalid branch name");
    }
    // The same two guards the ordinary form opens with, run before any ref is pushed: a tree is
    // expensive to create and worse to undo, so a workspace that could never be recorded must fail
    // before the first push rather than after the last one.
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (workspaceRepository.existsActiveByRepositoryAndWorkspaceId(repoId, workspaceId)) {
                throw new BadRequestException("Workspace already exists: " + workspaceId);
              }
              if (workspaceRepository.existsActiveByRepositoryAndBranch(repoId, newBranch)) {
                throw new ConflictException("Branch already has an active workspace: " + newBranch);
              }
              refuseSecondActiveForWork(
                  subject == null ? null : subject.normalized().workId());
              refuseUnplaceable(placement, admin);
            });
    createBranchTree(root, newBranch, parentBranch);
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                recordWorkspace(
                    repoId,
                    workspaceId,
                    parentBranch,
                    newBranch,
                    preamble,
                    true,
                    admin,
                    subject,
                    stated,
                    placement));
  }

  /**
   * What {@link #createAndStartWorkspace} answers: the row it recorded, and the start it ran on it —
   * the process {@link #beginEnsureContainer} answered, or why that start was refused.
   */
  public record CreatedWorkspace(Workspace workspace, String technicalProcessId, String startError) {}

  /**
   * <b>Creating a workspace starts it</b> (qits-853, owner decision 2026-10-05: "the frontend
   * requests a workspace — why is there a second REST call?"). The create door's method.
   *
   * <ul>
   *   <li>A RUNNER row needs nothing more: {@link #createWorkspace} queued it through the RUNNER
   *       start (token minted, runners told), and a runner's reserve takes it from there. No
   *       process is answered — the row's runtime status is its progress.
   *   <li>A DIRECT row is started by {@link #beginEnsureContainer}, the start the {@code
   *       ensure-container} door runs: the three-rung ladder, behind the process it answers.
   * </ul>
   *
   * <p>Either way a later ensure-container is the no-op it is on a row already starting, so a client
   * that still makes the second call keeps working.
   *
   * <p>Only the create door calls this. The domain's other creators decide for themselves whether
   * and when to start — dispatch and the editor start the row they wrote once, themselves, and
   * capture leaves it stopped — so the start is not folded into {@link #createWorkspace}, where it
   * would double theirs.
   *
   * <p><b>A start refused after the row exists does not undo the create.</b> The refusal is answered
   * in {@link CreatedWorkspace#startError} beside the row instead of thrown: the row is committed and
   * real, and an error status would tell the caller the create failed when it is the start that
   * did. A refusal before the row exists — every guard {@link #createWorkspace} runs — still throws
   * as it always did. A start that fails later, on the worker, fails its process as an
   * ensure-container's does.
   */
  public CreatedWorkspace createAndStartWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree,
      boolean admin,
      WorkspaceSubject subject,
      List<String> gitRefs,
      WorkspacePlacement placement) {
    Workspace created =
        createWorkspace(
            repoId,
            workspaceId,
            parent,
            branch,
            preamble,
            adoptExisting,
            branchTree,
            admin,
            subject,
            gitRefs,
            placement);
    if (created.placement == WorkspacePlacement.RUNNER) {
      return new CreatedWorkspace(created, null, null);
    }
    try {
      return new CreatedWorkspace(created, beginEnsureContainer(created.id), null);
    } catch (RuntimeException refused) {
      LOG.warnf(
          refused,
          "Workspace %s/%s was created but its start was refused",
          repoId,
          created.workspaceId);
      String message = refused.getMessage();
      return new CreatedWorkspace(
          created,
          null,
          message == null || message.isBlank() ? refused.getClass().getSimpleName() : message);
    }
  }

  /**
   * Creates {@code branch} in the wrapper and in every registered repository its committed
   * submodule declarations reach, then publishes the hand-off document on the wrapper's copy.
   *
   * <p>Three phases, in this order for a reason: the closure is discovered first (no ref is written
   * while it is being read), then <em>every</em> repository is checked for a colliding branch, and
   * only then does the first push happen. A collision therefore refuses the whole request while it
   * still costs nothing.
   */
  private void createBranchTree(
      RepositoryLookup.RepositoryView root, String branch, String rootParent) {
    Collection<RepositoryLookup.RepositoryView> closure = submoduleClosure(root, rootParent);

    for (var repository : closure) {
      if (mirrors.of(repository.id()).remoteHasBranch(branch)) {
        throw new ConflictException("Branch already exists in " + repository.name() + ": " + branch);
      }
    }

    List<RepositoryLookup.RepositoryView> created = new ArrayList<>();
    try {
      for (var repository : closure) {
        createBranchOnHost(
            mirrors.of(repository.id()), branch, sourceOf(repository, root, rootParent));
        created.add(repository);
      }
      writeWorkspaceGuide(mirrors.of(root.id()), branch);
    } catch (RuntimeException failure) {
      undoBranchTree(created, branch);
      throw failure;
    }
  }

  /**
   * Removes what a failed tree already created. Without it a tree that broke half way through would
   * fail its own collision check on every retry, and the branch name would be spent for good — the
   * caller cannot tell a ref this service left behind from one somebody else owns.
   *
   * <p>Best-effort by necessity: what brings us here is usually the git host refusing or being
   * unreachable, and a deletion it will not take leaves a ref to remove by hand. The original
   * failure is what the caller is told about either way.
   */
  private void undoBranchTree(List<RepositoryLookup.RepositoryView> created, String branch) {
    for (var repository : created) {
      try {
        PushOutcome deleted = mirrors.of(repository.id()).deleteBranch(branch);
        if (!deleted.accepted()) {
          LOG.warnf(
              "the git host refused to roll back branch '%s' of %s: %s",
              branch, repository.name(), deleted.output());
        }
      } catch (RuntimeException e) {
        LOG.warnf(e, "failed to roll back branch '%s' of %s", branch, repository.name());
      }
    }
  }

  /**
   * The wrapper and every registered repository reachable from committed {@code .gitmodules} urls,
   * wrapper first and each repository once.
   *
   * <p>Registered is the whole rule: a submodule url resolves through the project's repository list
   * by the name it addresses ({@code ../<name>.git}) and, for an adopted repository that owns no
   * alias row, by its id — the same two spellings qits-projects resolves a name through. A
   * submodule naming nothing registered is skipped rather than guessed at.
   */
  private Collection<RepositoryLookup.RepositoryView> submoduleClosure(
      RepositoryLookup.RepositoryView root, String rootParent) {
    List<RepositoryLookup.RepositoryView> registered =
        repositories.listByProject(root.projectId());
    if (registered.isEmpty()) {
      // The project holds at least this wrapper, so an empty answer is a registry that did not
      // answer. Read as "no submodules" it would branch the wrapper alone and call that a tree.
      throw new InternalServerErrorException(
          "The repository registry listed no repositories in project " + root.projectId());
    }
    Map<String, RepositoryLookup.RepositoryView> byName = new LinkedHashMap<>();
    Map<String, RepositoryLookup.RepositoryView> byId = new LinkedHashMap<>();
    for (var repository : registered) {
      if (repository.name() != null) {
        byName.put(repository.name(), repository);
      }
      byId.put(repository.id(), repository);
    }
    byName.put(root.name(), root);
    byId.put(root.id(), root);

    Map<String, RepositoryLookup.RepositoryView> closure = new LinkedHashMap<>();
    ArrayDeque<RepositoryLookup.RepositoryView> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      var repository = pending.removeFirst();
      if (closure.putIfAbsent(repository.id(), repository) != null) {
        continue;
      }
      RepoMirror mirror = mirrors.of(repository.id());
      mirror.refreshNow();
      String source = sourceOf(repository, root, rootParent);
      try (MirrorWorktree worktree =
          mirror.worktree("branch-tree-discovery", "refs/heads/" + source)) {
        Path modules = worktree.path().resolve(".gitmodules");
        if (!Files.isRegularFile(modules)) {
          continue;
        }
        for (String line : Files.readAllLines(modules)) {
          String trimmed = line.trim();
          if (!trimmed.startsWith("url") || !trimmed.contains("=")) {
            continue;
          }
          String name = repositoryName(trimmed.substring(trimmed.indexOf('=') + 1));
          var child = byName.containsKey(name) ? byName.get(name) : byId.get(name);
          if (child != null && !closure.containsKey(child.id())) {
            pending.addLast(child);
          }
        }
      } catch (IOException failure) {
        throw new InternalServerErrorException(
            "Could not read the submodule tree of "
                + repository.name()
                + ": "
                + failure.getMessage());
      }
    }
    return closure.values();
  }

  /** What a repository's copy of the workspace branch forks from: the wrapper's parent, or main. */
  private static String sourceOf(
      RepositoryLookup.RepositoryView repository,
      RepositoryLookup.RepositoryView root,
      String rootParent) {
    return repository.id().equals(root.id()) ? rootParent : defaultMainBranch(repository);
  }

  private static String repositoryName(String url) {
    String value = url.trim().replaceAll("/+$", "");
    value = value.substring(Math.max(value.lastIndexOf('/'), value.lastIndexOf(':')) + 1);
    return value.endsWith(".git") ? value.substring(0, value.length() - 4) : value;
  }

  /**
   * Publishes the hand-off document on the wrapper's copy of the workspace branch — a commit and a
   * push like any other write here, so the branch a workspace opens on already explains itself.
   */
  private void writeWorkspaceGuide(RepoMirror mirror, String branch) {
    mirror.refreshNow();
    try (MirrorWorktree worktree = mirror.worktree("workspace-guide", "refs/heads/" + branch)) {
      Path guide = worktree.path().resolve("WORKSPACE.md");
      // A parent that already carries the guide verbatim leaves nothing to commit — and a commit
      // with a clean index fails, which used to fail the whole creation once a released guide had
      // reached the fork point.
      if (Files.exists(guide) && WORKSPACE_GUIDE.equals(Files.readString(guide))) {
        return;
      }
      Files.writeString(guide, WORKSPACE_GUIDE);
      worktree.stage(List.of(Path.of("WORKSPACE.md")));
      worktree.commit(
          "docs: add workspace development flow",
          "Record how changes from this aggregate workspace reach a running environment.",
          gitIdentity.forMirror());
      PushOutcome pushed = worktree.push(PushSpec.of(PushSpec.Ref.branch("HEAD", branch)));
      if (!pushed.accepted()) {
        throw new InternalServerErrorException(
            "Failed to publish WORKSPACE.md: " + pushed.output());
      }
    } catch (IOException failure) {
      throw new InternalServerErrorException(
          "Could not write WORKSPACE.md: " + failure.getMessage());
    }
  }

  static final String WORKSPACE_GUIDE = """
      # Workspace development flow

      This checkout is an aggregate workspace. The wrapper and every checked-out submodule use the same workspace branch. Commit and push changes in the repository where they belong; the workspace credential has normal Git push access so each repository can move independently.

      A local commit is not automatically part of the running environment. Changes have to be orchestrated by **releasing** them: a release request folds your branch with `main` (and with every released tag not yet merged back), the quality gate builds that fold, and a green gate turns it into a version **tag**. A service's deployment follows from that release; `main` is merged only once the deployment is live.

      Release dependencies before their consumers, then let the affected application or service release carry the new versions into the environment. Keep the wrapper branch as the map of the workspace, but treat each submodule's own release as the unit that promotes code.

      ## Releasing from inside this container

      **Branch → release request.** Never push `main` yourself. Push your branch, then ask **qits-projects** to release it. Use `qits` first (next section): `qits release-request create --project <project> --repository <repository> --branch <your branch> --summary '<what this release is>'` is the door with the credential handled for you. The fallback is a hand-written `curl` against the service's public name, `https://<app>.qits.$QITS_DOMAIN/…`, with the bearer `qits-token qits-platform` prints — it works from the platform network and from a runner node alike, because the public names answer from both:

          PROJECTS=https://projects.qits.$QITS_DOMAIN/projects/api

          curl -sS -X POST -H "Authorization: Bearer $(qits-token qits-platform)" -H 'Content-Type: application/json' "$PROJECTS/repositories/<repository>/release-requests" -d '{"branch":"<your branch>","summary":"<what this release is>"}'

      Nothing has merged when that answers. The request folds `main`, your branch and every released tag still in flight onto its own `release/<id>` branch, the QA pipeline builds that fold, and a green gate releases it: the manifests are stamped, the fold is tagged with the version, and the source branches are deleted. Poll the request (`GET $PROJECTS/repositories/<repository>/release-requests/<id>`) until it reads `RELEASED` — `CONFLICTED` means the fold does not merge and is yours to resolve, `FAILED` and `REJECTED` say why in `detail`. Watch the build behind it: `curl -sS -H "Authorization: Bearer $(qits-token qits-platform)" https://ci.qits.$QITS_DOMAIN/ci/api/runs/active` (and `/ci/api/runs/finished?limit=10`).

      **Trains.** Releasing an SPA or a library deploys nothing by itself: the service that embeds or depends on it follows by event — CI commits a `bump(...)` onto that service's `maintenance/<dependency>` branch and releases it on its own. To ship a service change together with its SPA, release the SPA first and the service once the bump has reached the service's `main`; the service branch then merges cleanly on top of the new pin. Never move a submodule gitlink (`service/src/main/webui`) by hand to follow a release you made — the train owns that pin, and `git add -A` would stage it silently (`.gitmodules` says `ignore = all`); confirm with `git ls-tree HEAD <path>` before committing.

      **After a release the source branch is gone in that repository** (the release deletes it). Your local checkout still holds it; `git fetch && git switch main` there before the next change. Note `main` catches up only after the deployment, so a freshly released repository can sit at the tag for a while. The wrapper's branch and this workspace are untouched by a submodule's release.

      ## The qits CLI

      `qits` is on PATH and already signed in by this container's credential, so there is no `qits login` to run in here. That credential is one of two: `QITS_TOKEN` on a runner-placed workspace — one opaque token, used as it is — or the commissioned pair `QITS_COMMISSIONED_CLIENT_ID` / `QITS_COMMISSIONED_CLIENT_SECRET`, from which a bearer is minted once per process and kept in memory, never written to disk. It finds each service by itself, so none of the addressing above has to be composed by hand.

          qits work list --project qits
          qits ci runs --project qits --repository <repository> --limit 3
          qits release-request --project qits --repository <repository> list

      `qits events` and `qits observe` are the other two an agent reaches for; `qits --help` lists everything, and `qits help skill` prints the whole surface as a SKILL.md. The credential is `qits:agent`: reads answer, and an operator write comes back `403 - this credential is qits:agent, which reads but does not write`. That is the credential doing its job, not a misconfiguration — a write that matters goes through the release request above, or through a person.

      The two shell helpers keep their jobs and carry whichever credential this container holds: `qits-git-credential` is git's credential helper, and `qits-token qits-platform` prints the bearer for a hand-written `curl`.

      ## Toolchain notes

      - Run builds in a login shell (`bash -lc '...'`): `/etc/profile.d/qits-workspace.sh` gives the container uid a passwd entry (embedded-postgres suites need it) and adds `-s /etc/qits/maven-settings.xml` to `MAVEN_ARGS`. The local repository is `/caches/m2` (`MAVEN_OPTS`).
      - Package registries are derived from `QITS_DOMAIN` and nothing else: the platform's own packages (the `@qits` npm scope, the hosted Maven repository) at `https://registry.qits.<domain>`, npmjs and Maven Central through the caches at `https://mirror.qits.<domain>`. The `npm` shim on PATH and the Maven settings file authenticate both with this container's credential, so no `.npmrc` token and no `-D` repository override is needed — plain `npm ci` / `npm install` and `mvn` just work. A lockfile committed from here only ever names those public https hosts; never rewrite its `resolved` URLs. A service's `mvn verify` runs the same install inside `service/src/main/webui` (Quinoa).
      - qits-projects, CI and every other platform API answer at their public names, `https://<app>.qits.<domain>`: the public edge accepts this container's bearer on every service vhost, so the same `curl` works wherever this container runs.
      """;

  /**
   * Find or write <b>the</b> editor workspace — the single row the platform's one shared editor
   * container runs as. Idempotent, and the second caller gets the first caller's row.
   *
   * <p><b>A row and not a derivation, and that is the whole of what changed.</b> The editor used to
   * be a project's wrapper repository's main workspace: one per project, found by recognising the
   * wrapper among the repositories somebody had opened a main workspace for, and launched from the
   * editor image because of what that workspace <em>was</em>. There is one editor now, for the whole
   * platform, so there is no project to derive it from — and what is left is a decision, which is a
   * column ({@code Workspace.editor}, {@code V7}).
   *
   * <p>What that row deliberately does not have: <b>no branch and no parent</b>, because the editor
   * checks nothing out of its own; and a <b>sentinel repository id</b> ({@link
   * EditorWorkspace#REPOSITORY_ID}), because the column is not nullable and the shared editor belongs
   * to no repository. Every path that would resolve that id — the container factory's project and
   * name lookups, {@link #ensureContainer}'s branch-still-exists check, the mirror — is skipped for
   * an editor row rather than asked and failed. The credential is the ordinary one: nothing here
   * commissions anything, {@link #provisionContainer} does it for every workspace alike, and an
   * unresolvable repository costs the {@code project} scope exactly as an unreachable registry does
   * for any other workspace.
   *
   * <p><b>No lock, and the race is settled by the index.</b> Two callers can find nothing at the same
   * moment and both try to write; {@code uq_workspace_active_editor} makes the loser's insert fail
   * rather than making a second editor, which is the arrangement every create has with {@code
   * uq_workspace_active_branch} and for the same reason — a constraint holds under a race and an
   * agreement does not. The loser's caller retries by polling the door, which it is doing anyway.
   *
   * <p>It writes the same {@code CREATED} history entry every other creation writes, because the one
   * thing this row shares with every workspace is that somebody asked for it and it now exists.
   */
  @Transactional
  public Workspace createEditorWorkspace() {
    Optional<Workspace> existing = workspaceRepository.findActiveEditor();
    if (existing.isPresent()) {
      return existing.get();
    }

    Workspace workspace = new Workspace();
    workspace.workspaceId = EditorWorkspace.WORKSPACE_ID;
    workspace.repositoryId = EditorWorkspace.REPOSITORY_ID;
    workspace.parent = null;
    workspace.branch = null; // nothing is checked out: the editor is not a place work lands
    workspace.status = WorkspaceStatus.ACTIVE;
    workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
    workspace.editor = true;
    // The editor never consults a runner: stated explicitly rather than left to the entity's own
    // DIRECT default, so this stays true even if that default ever changes (qits-837).
    workspace.placement = WorkspacePlacement.DIRECT;
    // The editor pushes nothing today. It has no branch of its own to push, and the repositories it
    // will one day hold side by side are not cloned into it yet — so the honest list is the empty
    // one, which is also what GitRefs.effective would answer for a branchless row. Widening it is
    // part of the task that clones those repositories, not of this one.
    workspace.gitRefs = GitRefs.write(List.of());
    workspaceRepository.persist(workspace);
    recordEvent(workspace, WorkspaceEventType.CREATED, null, null, null);

    return workspace;
  }

  /**
   * Sanitizes a branch name into a workspace-id slug ([A-Za-z0-9_-], ≤64 chars, not dash-leading).
   * Public because the capture ingest derives workspace ids from its generated branch names through
   * the same rule.
   */
  public static String toWorkspaceSlug(String branch) {
    String slug = branch.replaceAll("[^A-Za-z0-9_-]", "-");
    if (slug.length() > 64) {
      slug = slug.substring(0, 64);
    }
    if (slug.isBlank() || slug.startsWith("-")) {
      slug = "main";
    }
    return slug;
  }

  /**
   * The branch a blank parent/target defaults to: the repository's configured main branch (set at
   * clone time from the remote's default), with "master" as the last-resort fallback for a row
   * whose {@code mainBranch} was never populated.
   */
  private static String defaultMainBranch(RepositoryLookup.RepositoryView repo) {
    return (repo.mainBranch() == null || repo.mainBranch().isBlank())
        ? "master"
        : repo.mainBranch();
  }

  /**
   * What an ensure needs off the row: the branch to check and provision from, the parent, whether
   * this is the editor's row — the one workspace that has no branch and must not be read as having
   * lost one — and the subject's entity id, carried along so the daemon's env is reproducible on the
   * resume path exactly as {@code branch}/{@code parent} already are. See {@link
   * #ensureContainer(String, String, Long, WorkspaceProcessTracker.Handle)}.
   */
  private record BranchParent(String branch, String parent, boolean editor, String entityId) {}

  /**
   * Guarantees a running container for an ACTIVE workspace whose branch still exists, provisioning
   * one on demand — the container is a recreatable cache of the durable branch, so losing it is a
   * non-event. Idempotent:
   *
   * <ul>
   *   <li>container already running → stamp {@code RUNNING}, no-op. A live container is
   *       <em>never</em> re-cloned over, so unpushed {@code /workspace} commits are safe.
   *   <li>container present but stopped (e.g. a host/docker restart left it {@code Exited}) →
   *       {@code docker start} it in place. This keeps the {@code /workspace} clone and any
   *       unpushed commits — the lossless recovery a re-clone can't give — so it wins over
   *       re-provisioning.
   *   <li>container absent but the branch ref survives in origin → materialize a fresh container
   *       from that branch via {@link #provisionContainer} — the single provisioning path, for
   *       never-provisioned and pruned workspaces alike.
   *   <li>branch ref gone from origin → the work no longer exists anywhere: the workspace is
   *       ABANDONED here (now the <em>only</em> path to abandonment) and a 404 is thrown.
   * </ul>
   *
   * <p><strong>Loss window:</strong> recreation restores <em>origin</em> state only. Commits made
   * in a container but never pushed die with it; the live-container guard protects the graceful
   * case, but an unexpected container death is still lossy (see {@link #stopContainer} for the
   * lossless stop). Not {@code @Transactional}: each status transition commits in its own
   * transaction so a FAILED/ABANDONED outcome is persisted even though the method then throws, and
   * so it is safe to call from non-request threads (like {@code CommandService.prepare}).
   */
  public void ensureContainer(Long id) {
    // Its own transaction: this is called from worker threads (the bootstrap runner's manual-run
    // executor) where no session is open, and the rest of ensureContainer already brackets each of
    // its own reads the same way.
    Workspace row = QuarkusTransaction.requiringNew().call(() -> requireActive(id));
    // A runner-placed row's container is the runner's, never the direct ladder's (qits-780): the
    // bootstrap's manual run needs it up, so a RUNNING one goes ahead and anything else is asked to
    // start first rather than provisioned on the platform host.
    if (row.placement == WorkspacePlacement.RUNNER) {
      if (row.runtimeStatus != WorkspaceRuntimeStatus.RUNNING) {
        throw new BadRequestException(
            "Workspace '"
                + row.workspaceId
                + "' runs on a workspace runner and is not running: start it, then run again.");
      }
      return;
    }
    ensureContainer(row.repositoryId, row.workspaceId, id, null);
  }

  /**
   * The streaming Start: registers a {@link WorkspaceProcessTracker.Handle} for the workspace <em>before</em> any
   * work runs (so the very first {@code docker run} line is captured), spawns {@link
   * #ensureContainer(String, String, WorkspaceProcessTracker.Handle)} on a worker thread, and returns the process
   * id immediately. The browser watches the work — including the asynchronous service auto-start
   * phase — over the process's SSE stream; failures surface there (and in {@code
   * workspace.runtimeError}), not as an HTTP error. Throws 404 in-request when the workspace
   * doesn't exist, so a bad id still fails fast.
   */
  public String beginEnsureContainer(Long id) {
    Workspace resolved = QuarkusTransaction.requiringNew().call(() -> requireActive(id));
    if (resolved.placement == WorkspacePlacement.RUNNER) {
      return beginRunnerStart(resolved);
    }
    WorkspacePlacements.requireDirectAllowed(resolved);
    // A start already under way is joined, not doubled. Creating a workspace starts it (qits-853),
    // so a client that still presses ensure-container straight after the create lands here while
    // that start is cloning — before its container exists, when the ladder below would provision a
    // second one beside it. A FAILED row is the exception: its process is the one that failed.
    if (resolved.runtimeStatus != WorkspaceRuntimeStatus.FAILED) {
      Optional<String> running =
          processes.isResolvable() ? processes.get().activeFor(resolved.id) : Optional.empty();
      if (running.isPresent()) {
        return running.get();
      }
    }
    String repoId = resolved.repositoryId;
    String workspaceId = resolved.workspaceId;
    Long rowId = resolved.id;
    WorkspaceProcessTracker.Handle process = tracker(repoId, workspaceId, rowId);
    processExecutor.submit(
        () -> {
          try {
            ensureContainer(repoId, workspaceId, rowId, process);
          } catch (RuntimeException e) {
            // Surface the failure in the stream: settle the open segment failed (appending the
            // message) and emit done. Idempotent — a no-op if the process already ended.
            if (process != null) {
              process.failProvision(e.getMessage());
            }
            LOG.debugf(
                e, "Streamed ensure-container failed for workspace %s/%s", repoId, workspaceId);
          }
        });
    return process == null ? null : process.id();
  }

  /**
   * Recreate a workspace's container on the current image — the way to roll a workspace onto a
   * newer {@code workspace-daemon} build (docs/epics/qits-workspace-registry/). Unlike {@link
   * #beginEnsureContainer} (which resumes an existing container in place), this deliberately tears
   * the old container down and provisions a fresh one, so a {@code docker run} picks up whatever
   * {@code qits.workspace.image-repo}:{@code qits.workspace.image-version} now resolves to.
   *
   * <p><b>That now resolves to a pinned, published release</b> rather than a local {@code :latest}
   * tag, which narrows what a recreate can reach. It rolls a workspace forward exactly as far as
   * the version this deployment carries, and no further — and what that version is has moved: it is
   * {@code WorkspaceImage.VERSION}, the version of the {@code qits-workspace-daemon-protocol}
   * dependency this build was compiled against, not an environment entry qits-configuration rewrote
   * underneath the running process. So reaching a newer daemon takes a bump of that pom line, a
   * release of this service and a deploy — three reviewed steps where there used to be a listener.
   * Recreate is still the operation that applies a new image; it just applies the one this release
   * was tested with.
   *
   * <p><b>Requires a provably clean working tree.</b> Recreating is lossy for uncommitted work, so
   * the gate is stricter than {@link #requireCleanWorkingTree}: it consults the daemon-reported
   * tri-state ({@link WorkspaceGitStatus#isClean}) and admits only an explicit clean. A dirty tree
   * and an UNKNOWN state (no live daemon, or none has reported yet) are <em>both</em> rejected with
   * a 400 — an unknowable tree is not a safe basis to destroy a container. The gate runs
   * synchronously so a bad request fails fast; the teardown+reprovision then streams like {@link
   * #beginEnsureContainer}: best-effort push (preserve committed work) → settle services gracefully
   * → {@code rm} the old container → {@link #ensureContainer} provisions a fresh one from the
   * durable branch (whose absent-container path re-runs {@link #provisionContainer}).
   */
  public String beginRecreateContainer(Long id) {
    Workspace resolved = QuarkusTransaction.requiringNew().call(() -> requireActive(id));
    if (resolved.placement == WorkspacePlacement.RUNNER) {
      return beginRunnerRecreate(resolved);
    }
    WorkspacePlacements.requireDirectAllowed(resolved);
    String repoId = resolved.repositoryId;
    String workspaceId = resolved.workspaceId;
    Long rowId = resolved.id;
    requireCleanForRecreate(workspaceId, rowId);
    WorkspaceProcessTracker.Handle process = tracker(repoId, workspaceId, rowId);
    processExecutor.submit(
        () -> {
          try {
            // No backup push: requireCleanForRecreate established the daemon reports CLEAN, and
            // the daemon pushes committed work as it lands, so origin is already current. There is
            // nothing for the host to preserve that the daemon has not already sent.
            // Settle live services gracefully so their disappearance reads as deliberate, not a
            // crash
            // the restart policy would resurrect — the same courtesy stopContainer/discard extend.
            containerEvents.fireStopping(repoId, workspaceId, rowId, true);
            stopBeforeRemove(containers.containerName(workspaceId, repoId));
            containers.rm(containers.containerName(workspaceId, repoId));
            // Container now absent → ensureContainer's provision path re-clones on the current
            // image.
            ensureContainer(repoId, workspaceId, rowId, process);
          } catch (RuntimeException e) {
            if (process != null) {
              process.failProvision(e.getMessage());
            }
            LOG.debugf(
                e, "Streamed recreate-container failed for workspace %s/%s", repoId, workspaceId);
          }
        });
    return process == null ? null : process.id();
  }

  /**
   * Recreate's registry-only clean gate: the daemon-reported tri-state must be an <em>explicit</em>
   * clean. Unlike {@link #requireCleanWorkingTree} (which execs git and folds unknown→dirty and
   * absent→clean), recreate must reject UNKNOWN in its own right — a workspace with no live daemon
   * reporting has an unknowable tree, and destroying its container could silently lose work — so
   * both dirty ({@code Optional.of(false)}) and unknown ({@code Optional.empty()}) throw 400; only
   * {@code Optional.of(true)} passes.
   */
  private void requireCleanForRecreate(String workspaceId, Long rowId) {
    Optional<Boolean> clean =
        gitStatus.isResolvable() ? gitStatus.get().isClean(rowId) : Optional.empty();
    if (!clean.equals(Optional.of(Boolean.TRUE))) {
      String state = clean.map(c -> c ? "clean" : "dirty").orElse("unknown");
      throw new BadRequestException(
          "Cannot recreate workspace '"
              + workspaceId
              + "': its working tree must be clean, but its reported state is "
              + state
              + ". Commit or discard changes, and ensure its daemon is connected, first.");
    }
  }

  /**
   * {@link #ensureContainer(String, String)} with an optional {@link WorkspaceProcessTracker.Handle} receiving
   * the work as streamed segments. With a process attached, every outcome also ends the process:
   * the already-running short-circuit completes it as a no-op, a provision failure fails it, and a
   * successful start hands the process id to the async bootstrap-then-service phase via {@link
   * WorkspaceContainerEventPublisher#fireStarted(String, String, String, boolean)} — the process
   * then reaches {@code done} only once the bootstrap chain and the auto-started services settle.
   */
  private void ensureContainer(
      String repoId, String workspaceId, Long rowId, WorkspaceProcessTracker.Handle process) {
    String container = containers.containerName(workspaceId, repoId);

    // Load branch/parent and short-circuit a live container, in its own transaction.
    BranchParent snapshot =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace wt =
                      workspaceRepository
                          .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
                          .orElseThrow(
                              () -> new NotFoundException("Workspace not found: " + workspaceId));
                  // The ladder's one gate (qits-780): every qits-containers call below — isRunning,
                  // exists/start, the provision's run and its rm, the branch-gone volume reap — is
                  // behind it. It is also what refuses a RUNNER row that reached this ladder through
                  // ensureContainer(Long), which does not branch on placement.
                  WorkspacePlacements.requireDirectAllowed(wt);
                  if (containers.isRunning(container)) {
                    wt.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
                    wt.runtimeError = null;
                    return null; // already running — nothing to provision
                  }
                  return new BranchParent(wt.branch, wt.parent, wt.editor, wt.entityId);
                });
    if (snapshot == null) {
      observeClientLiveness(repoId, workspaceId, rowId);
      if (process != null) {
        process.completeNoOp("container-start", "Container is already running — nothing to do.");
      }
      return;
    }

    // Present but not running — a container that died out-of-band (classically a host/docker
    // restart leaving it stopped). `isRunning` is false but the container and its /workspace volume
    // still exist, so start it back up rather than re-cloning: this keeps unpushed commits, the
    // lossless recovery the graceful-stop path can't offer once the container died unexpectedly.
    // The branch-gone abandonment below deliberately doesn't apply here — the work lives on the
    // volume, not just origin.
    //
    // `start` takes the workspace's identity rather than the container name, and the snapshot is
    // where branch and parent come from: the orchestrator has no start verb, so a stopped place is
    // started by asking for it again with its spec. See ContainerRuntime.start.
    if (containers.exists(container)) {
      QuarkusTransaction.requiringNew()
          .run(() -> markRuntime(repoId, workspaceId, WorkspaceRuntimeStatus.PROVISIONING, null));
      try {
        if (process != null) {
          process.openSegment("container-start");
        }
        containers.start(
            repoId, workspaceId, rowId, snapshot.branch(), snapshot.parent(), snapshot.entityId());
        if (process != null) {
          process.appendLine(
              "container-start",
              "Started the existing container again (its /workspace volume is preserved).");
          process.settleSegment("container-start", true);
          process.finishProvision(true);
        }
        QuarkusTransaction.requiringNew()
            .run(() -> markRuntime(repoId, workspaceId, WorkspaceRuntimeStatus.RUNNING, null));
        // Cold -> RUNNING, but not a fresh provision: the clone (and its bootstrap state) survived,
        // so the bootstrap runner passes straight through to service auto-start (async).
        containerEvents.fireStarted(
            repoId, workspaceId, rowId, process == null ? null : process.id(), false);
        observeClientLiveness(repoId, workspaceId, rowId);
        return;
      } catch (RuntimeException e) {
        QuarkusTransaction.requiringNew()
            .run(
                () ->
                    markRuntime(
                        repoId,
                        workspaceId,
                        WorkspaceRuntimeStatus.FAILED,
                        truncate(e.getMessage())));
        throw e;
      }
    }

    // THE EDITOR IS EXEMPT, and it is the branchless case rather than an exception to the rule. The
    // abandonment below fires when a workspace's durable branch has gone — the ref it exists to hold
    // a checkout of was deleted, so the workspace is genuine dead weight. The editor's row claims no
    // branch and never did: there is nothing to have lost, no repository to ask, and asking anyway
    // would put a wire read against a sentinel id in front of every editor ensure. Falling through
    // to the branch check would abandon the editor on its first launch.
    if (!snapshot.editor()
        && (snapshot.branch() == null
            || snapshot.branch().isBlank()
            || !branchExists(repoId, snapshot.branch()))) {
      // The durable branch is gone: this is genuine death, so abandon (persisted before we throw).
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                Workspace wt =
                    workspaceRepository
                        .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
                        .orElseThrow(
                            () -> new NotFoundException("Workspace not found: " + workspaceId));
                wt.status = WorkspaceStatus.ABANDONED;
                wt.resolvedAt = Instant.now();
                wt.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                recordEvent(wt, WorkspaceEventType.ABANDONED, wt.branch, null, null);
                // The second termination path (beside doDiscard). The workspace is only
                // soft-deleted, so no FK cascade fires for the rows other contexts hang off it —
                // they clean up on this event, inside this transaction.
                workspaceResolvedEvent.fire(
                    new WorkspaceResolved(
                        repoId, workspaceId, wt.id, WorkspaceStatus.ABANDONED));
              });
      // The branch is gone, so any persisted /workspace volume is orphaned work — reap it. The
      // container is already absent on this path (we passed the isRunning/exists branches), so no
      // prior rm is needed. Best-effort.
      containers.removeWorkspaceVolume(workspaceId);
      // And so is the credential that container held. Outside the transaction above, beside the
      // volume reap, for the reason doDiscard states at length: the resolution transaction is where
      // observers join and is not where an HTTP call belongs.
      decommissionFor(rowId);
      throw new NotFoundException(
          "Workspace '" + workspaceId + "' has no branch to recreate from; abandoned");
    }

    QuarkusTransaction.requiringNew()
        .run(() -> markRuntime(repoId, workspaceId, WorkspaceRuntimeStatus.PROVISIONING, null));
    try {
      provisionContainer(
          repoId,
          workspaceId,
          rowId,
          snapshot.branch(),
          snapshot.parent(),
          snapshot.entityId(),
          process);
      if (process != null) {
        process.finishProvision(true);
      }
      QuarkusTransaction.requiringNew()
          .run(() -> markRuntime(repoId, workspaceId, WorkspaceRuntimeStatus.RUNNING, null));
      // Cold -> RUNNING off a fresh provision (bare clone): run the bootstrap chain, then service
      // auto-start (async; the runner passes straight through when the chain is empty).
      containerEvents.fireStarted(
          repoId, workspaceId, rowId, process == null ? null : process.id(), true);
      observeClientLiveness(repoId, workspaceId, rowId);
    } catch (RuntimeException e) {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  markRuntime(
                      repoId,
                      workspaceId,
                      WorkspaceRuntimeStatus.FAILED,
                      truncate(e.getMessage())));
      throw e;
    }
  }

  /**
   * Record the in-container workspace-daemon's handshake liveness alongside the reconciliation
   * ladder (docs/epics/qits-workspace-daemon/) — <b>informational only</b>. It never gates a status
   * transition: a running container is decided by docker run-state and the branch ref exactly as
   * before, so a missing/broken socket (older images, a crashed binary, cli/tests with no backend
   * impl) degrades to today's behaviour. Later parts consult this once the socket drives behaviour.
   */
  private void observeClientLiveness(String repoId, String workspaceId, Long rowId) {
    if (clientLiveness.isResolvable()) {
      boolean live = clientLiveness.get().isDaemonLive(rowId);
      LOG.debugf(
          "workspace-daemon control socket for %s/%s: %s (informational; reconciliation unaffected)",
          repoId, workspaceId, live ? "present" : "not yet observed");
    }
  }

  private void markRuntime(
      String repoId, String workspaceId, WorkspaceRuntimeStatus status, String error) {
    workspaceRepository
        .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
        .ifPresent(
            wt -> {
              wt.runtimeStatus = status;
              wt.runtimeError = error;
            });
  }

  private static String truncate(String s) {
    if (s == null) {
      return null;
    }
    return s.length() <= 2000 ? s : s.substring(0, 2000);
  }

  /**
   * Whether {@code branch} still exists in the repository — asked of the <b>git host</b>, with
   * {@code ls-remote}, never of the mirror.
   *
   * <p>That distinction is load-bearing rather than tidy. This answer is what {@link
   * #ensureContainer} abandons a workspace on, and a cache that is one fetch behind would report a
   * live branch as gone and destroy a workspace over it. A read against the repository of record
   * costs one round trip and cannot be wrong.
   *
   * <p>An unreachable git host <b>throws</b> rather than answering "gone", for the same reason: "I
   * could not ask" and "it is not there" were one value while the origin was a local directory, and
   * over the wire they must not be. Abandoning a workspace because a service was restarting would be
   * the worst possible reading of a transient failure.
   */
  public boolean branchExists(String repoId, String branch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      return false;
    }
    try {
      return mirrors.of(repoId).remoteHasBranch(branch);
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException(
          "Could not ask the git host about '" + branch + "': " + e.getMessage());
    }
  }


  /**
   * Gracefully pauses a workspace's container: pushes its branch to origin first (a durability
   * backstop for committed work), {@code docker stop}s the container <em>in place</em> — keeping it
   * and its {@code /workspace} clone — and marks the workspace {@code STOPPED} while leaving it
   * ACTIVE. On next access {@link #ensureContainer} resumes the same container via {@code
   * containers.start} (its {@code exists()} → {@code start()} branch), so the working tree survives
   * intact: uncommitted/untracked files and unpushed commits alike. This is a true pause, not a
   * teardown — the lossy {@link #rm} is reserved for discard (which deletes the branch afterward).
   */
  public void stopContainer(Long id) {
    // Not @Transactional any more, and only so the RUNNER branch can wait for its runner outside a
    // transaction: the DIRECT body below runs in the transaction it always ran in, joined or begun.
    Workspace placed = QuarkusTransaction.joiningExisting().call(() -> requireActive(id));
    if (placed.placement == WorkspacePlacement.RUNNER) {
      stopOnRunner(placed);
      return;
    }
    WorkspacePlacements.requireDirectAllowed(placed);
    QuarkusTransaction.joiningExisting().run(() -> stopDirectContainer(id));
  }

  /** {@link #stopContainer} for a DIRECT row: the method body as it was before placement. */
  private void stopDirectContainer(Long id) {
    Workspace workspace = requireActive(id);
    String repoId = workspace.repositoryId;
    String workspaceId = workspace.workspaceId;
    // No durability push before the stop: the daemon pushes committed work as it lands, so origin
    // is current by the time we get here. A stop is a pause anyway — the container and its
    // /workspace clone survive it, so uncommitted work is not at risk either.
    // Settle the workspace's services before the container stops, so a live service's disappearance
    // reads as a deliberate STOPPED (graceful: signal + grace) instead of a crash the restart
    // policy
    // would resurrect. Synchronous — completes while the container is still running.
    containerEvents.fireStopping(repoId, workspaceId, workspace.id, true);
    containers.stop(containers.containerName(workspaceId, repoId));
    workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
  }

  /**
   * Best-effort graceful stop ahead of a {@link ContainerRuntime#rm}, used at every call site that
   * tears a container down. {@code containers.stop} is documented best-effort and never throws on
   * its own, but a fake or a future implementation might, and removal must never get stuck or fail
   * on account of it — so any {@link RuntimeException} is logged and swallowed here, and the caller
   * always proceeds to {@code rm}. The point is giving whatever is running inside (notably a coding
   * agent started with {@code claude --remote-control}) a SIGTERM instead of the SIGKILL a straight
   * {@code rm} delivers, since that agent only archives its claude.ai session on SIGTERM.
   */
  private void stopBeforeRemove(String container) {
    try {
      containers.stop(container);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Graceful stop before removal failed for container %s; removing anyway",
          container);
    }
  }

  /**
   * Deletes a workspace's container outright ({@code docker rm}) while keeping its durable branch
   * and the ACTIVE workspace row. Where {@link #stopContainer} pauses in place (keeping the
   * container and its {@code /workspace} volume for a lossless resume) and a plain recreate now
   * <em>preserves</em> that volume, this is the one deliberate reset: it tears the container down
   * <em>and removes the persistent {@code /workspace} volume</em>, so the next {@link
   * #ensureContainer} re-creates an empty volume and re-clones a fresh checkout from the branch —
   * losing every uncommitted working-tree change and any unpushed commit, as its Shift-guarded
   * "loses uncommitted changes" contract promises. Distinct from {@link #discardWorkspace}
   * (Abandon), which additionally deletes the branch and soft-deletes the row. Settles any live
   * services first (immediate — the container is being torn down) and leaves the workspace {@code
   * STOPPED} with no runtime error. No-op-safe if the container/volume are already gone (both
   * best-effort). The container is removed before the volume (docker refuses an in-use volume).
   */
  public void deleteContainer(Long id) {
    // Not @Transactional any more, for stopContainer's reason; the DIRECT body is unchanged.
    Workspace placed = QuarkusTransaction.joiningExisting().call(() -> requireActive(id));
    if (placed.placement == WorkspacePlacement.RUNNER) {
      deleteOnRunner(placed);
      return;
    }
    WorkspacePlacements.requireDirectAllowed(placed);
    QuarkusTransaction.joiningExisting().run(() -> deleteDirectContainer(id));
  }

  /**
   * The DIRECT container's teardown, delete-container's order: settle its services (immediate), a
   * graceful stop, the {@code rm}, then the per-workspace volume. The container is removed before
   * the volume (docker refuses an in-use volume).
   */
  private void tearDownDirect(String repoId, String workspaceId, Long rowId) {
    String container = containers.containerName(workspaceId, repoId);
    containerEvents.fireStopping(repoId, workspaceId, rowId, false);
    stopBeforeRemove(container);
    containers.rm(container);
    containers.removeWorkspaceVolume(workspaceId);
  }

  /** {@link #deleteContainer} for a DIRECT row: the method body as it was before placement. */
  private void deleteDirectContainer(Long id) {
    Workspace workspace = requireActive(id);
    String repoId = workspace.repositoryId;
    String workspaceId = workspace.workspaceId;
    tearDownDirect(repoId, workspaceId, workspace.id);
    // The row stays ACTIVE and fires no WorkspaceResolved, but the CONTAINER is gone — and the
    // credential's lifetime is the container's, not the row's. So it goes back here too, and the
    // next ensure commissions a fresh one for the container it provisions. This is the path an
    // observer on the resolution event could never have covered.
    workspace.commissionedClientSecret = null;
    String commissioned = workspace.commissionedClientId;
    workspace.commissionedClientId = null;
    decommission(commissioned);
    workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
    workspace.runtimeError = null;
  }

  @Transactional
  public MergeResult mergeWorkspace(Long id, String target) {
    Workspace workspace = requireActive(id);
    String repoId = workspace.repositoryId;
    var repo = repositories.require(repoId);

    String resolvedTarget = (target == null || target.isBlank()) ? defaultMainBranch(repo) : target;

    // `target` names a BRANCH. It is still accepted as a workspace label, because that is what the
    // branch-tree UI has always sent and the two coincide for an ordinary workspace; the lookup is
    // a convenience, not an identity claim. It is unambiguous now in a way it was not before: at
    // most one ACTIVE workspace owns a branch, so at most one row can answer.
    Workspace targetWorkspace =
        workspaceRepository
            .findActiveByRepositoryAndWorkspaceId(repoId, resolvedTarget)
            .orElse(null);
    if (targetWorkspace != null && targetWorkspace.branch != null) {
      resolvedTarget = targetWorkspace.branch;
    }

    refuseMainAsMergeTarget(repo, resolvedTarget, workspace.id);

    String currentBranch = workspace.branch;
    // Same pre-integration guard as branch integration: refuse a dirty working tree and push the
    // container's unpushed commits so the origin ref this merge reads is complete (a swallowed push
    // would silently integrate a stale ref). Pass the workspace resolved by id (not its branch) so
    // the guard acts on exactly this container. A stopped/absent container is a no-op.
    requireSyncedSourceForIntegration(repoId, workspace);
    MergeResult result = mergeIntoTarget(repoId, currentBranch, resolvedTarget);
    if (!result.hasConflicts()) {
      recordEvent(
          workspace, WorkspaceEventType.MERGED, currentBranch, resolvedTarget, result.commitHash());
      // The merge advanced the target's origin ref; if a live workspace owns it, pull it in now.
      notifyIncomingMerge(repoId, resolvedTarget);
    }
    return result;
  }

  /**
   * The workspace door into git, and it never reaches the default branch: merge this workspace's
   * branch into <b>its parent</b> — a {@code task/…} landing on the {@code epic/…} it forked from —
   * as a single pushed commit that stamps nothing.
   *
   * <p><b>It is not a release and there is no longer a door here that is.</b> A release is a release
   * request in qits-projects: the sources are octopus-merged on a backing branch through
   * qits-githost, the version stamp and the manifest bump happen there, the tag is what a release
   * <em>is</em>, and {@code main} is merged only after the deployment. A workspace whose parent
   * <em>is</em> the default branch is therefore refused here and sent to that flow, rather than
   * quietly writing the one branch this service does not own.
   *
   * <p>The work is in the parent afterwards, so the workspace resolves: the container, the volume,
   * the branch and the ACTIVE row all go.
   *
   * @throws eu.wohlben.qits.workspaces.error.IntegrateConflictException for every refusal the caller
   *     can act on
   */
  public IntegrateResult integrateWorkspace(Long id, String summary) {
    BranchIntegrator.Landed landed = landWorkspace(id, summary);
    return new IntegrateResult(landed.commitSha(), landed.branch(), landed.targetBranch());
  }

  /**
   * The body of {@link #integrateWorkspace}: the guards, the lease, the git flow and the
   * resolution.
   */
  private BranchIntegrator.Landed landWorkspace(Long id, String summary) {
    // Deliberately NOT one @Transactional. Between the guards and the row work sit two waits a
    // transaction has no business holding open: the repository lease (up to a minute) and the push
    // (up to two). Narayana's default transaction timeout is shorter than their sum, so a busy
    // repository would fail as a transaction timeout rather than as anything a caller could read.
    // The class already answers this the same way everywhere else — read in one short transaction,
    // do the slow thing outside, write in another (see beginEnsureContainer and its siblings).
    Workspace workspace = QuarkusTransaction.requiringNew().call(() -> requireActive(id));
    String repoId = workspace.repositoryId;
    var repo = repositories.require(repoId);
    String mainBranch = defaultMainBranch(repo);
    // An integrate lands on the branch this workspace forked from — and on the default branch
    // never, which is the next guard.
    String target = parentBranchOf(workspace, mainBranch);
    String source = workspace.branch;

    if (summary == null || summary.isBlank()) {
      throw new BadRequestException("An integrate needs a summary for its commit");
    }
    if (source == null || source.isBlank() || source.startsWith("-")) {
      throw new BadRequestException(
          "Workspace '" + workspace.workspaceId + "' has no branch to integrate");
    }
    // The rule that keeps this door off the one branch this service does not write. A workspace
    // forked straight off the default branch has nothing to integrate into: its parent IS that
    // branch, and only a release request lands there.
    refuseMainAsMergeTarget(repo, target, workspace.id);
    if (source.equals(target)) {
      throw new BadRequestException(
          "Workspace '"
              + workspace.workspaceId
              + "' is on '"
              + target
              + "', which is already the branch it would land on");
    }
    // The source's container may hold uncommitted work the origin-side merge would silently leave
    // behind. Same guard the merge endpoints open with, for the same reason.
    requireSyncedSourceForIntegration(repoId, workspace);

    BranchIntegrator.Landed landed = landOnBranch(repo, source, target, summary);

    String subject = "integrate(" + source + "): " + summary;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // The push advanced the target's origin ref; if a live workspace owns it, pull it in.
              notifyIncomingMerge(repoId, target);
              // The work is in the target branch, so the workspace resolves: container and volume
              // gone, branch deleted, row INTEGRATED, WorkspaceEventType.INTEGRATED recorded — the
              // same mechanics branch cleanup has always used, now carrying this flow's own target
              // and sha. Re-read inside this transaction: the row above is detached, and
              // WorkspaceResolved observers join here.
              doDiscard(
                  repoId,
                  requireActive(id),
                  WorkspaceStatus.INTEGRATED,
                  subject,
                  target,
                  landed.commitSha());
            });
    return landed;
  }

  /** The git half, under the repository lease, once the source and the target are known. */
  private BranchIntegrator.Landed landOnBranch(
      RepositoryLookup.RepositoryView repo, String source, String target, String summary) {
    String repoId = repo.id();
    String leaseToken = acquireIntegrateLease(repoId);
    try {
      return integrator.land(new BranchIntegrator.Run(repoId, source, target, summary));
    } finally {
      processRegistry.releaseRepository(repoId, leaseToken);
    }
  }

  /** The branch a workspace forked from; the default branch for one that records none. */
  private static String parentBranchOf(Workspace workspace, String mainBranch) {
    return (workspace.parent == null || workspace.parent.isBlank())
        ? mainBranch
        : workspace.parent;
  }

  /**
   * The repository lease integrate serializes on.
   *
   * <p><b>Not a correctness requirement.</b> The push is a compare-and-swap and git settles a
   * genuine race by rejecting the loser, so two integrates are already safe without this. What the
   * lease buys is that the common case — two workspaces of one repository integrated seconds apart —
   * is <b>one waits</b> rather than <b>one fails</b>, and that two flows never build worktrees in one
   * bare origin at the same time.
   *
   * <p>The registry's reservation is fail-fast by design (its other users, a pull and an interactive
   * sign-in, want an immediate answer), so the waiting is here: a short poll under a hard cap. The
   * cap is what keeps this from being the unbounded wait the push timeout exists to forbid — past it
   * the caller is told the repository is busy, which is a sentence a person can act on.
   */
  private String acquireIntegrateLease(String repoId) {
    long deadline = System.currentTimeMillis() + integrateLeaseWaitMs;
    String lastKind = null;
    while (true) {
      RepoReservation lease = processRegistry.reserveRepository(repoId, "integrate");
      if (lease instanceof RepoReservation.Acquired acquired) {
        return acquired.token();
      }
      lastKind = ((RepoReservation.Conflict) lease).runningKind();
      if (System.currentTimeMillis() >= deadline) {
        throw new ConflictException(
            "Repository is busy with '" + lastKind + "'; nothing landed — try again.");
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ConflictException("Interrupted while waiting for the repository to be free");
      }
    }
  }

  /**
   * The rule that makes "this service does not write the default branch" true in the API and not
   * only at the git host.
   *
   * <p>{@code merge} and {@code integrate} keep working for every other target — landing on a
   * <em>parent</em> branch is what stacked workspaces do all day — but any of them whose target
   * resolves to the default branch is refused here, naming the flow that does write it. The default
   * branch is qits-projects' now: a release request folds its sources, releases a tag, and {@code
   * main} is finalized after the deployment. Without this guard the claim would be false in the API
   * even while true at the git host, and the git host's own hook would then refuse the write anyway:
   * a worse error, later, instead of a clear one now.
   *
   * <p>It carries {@code RELEASE_REQUIRED} rather than a bare 409, so a client can offer the right
   * button instead of word-matching prose for an endpoint name. Nothing was attempted, which is what
   * separates this from every other value in the enum.
   */
  private void refuseMainAsMergeTarget(
      RepositoryLookup.RepositoryView repo, String resolvedTarget, Long workspaceId) {
    if (!defaultMainBranch(repo).equals(resolvedTarget)) {
      return;
    }
    String id = workspaceId == null ? "{id}" : String.valueOf(workspaceId);
    throw new IntegrateConflictException(
        IntegrateConflictException.Reason.RELEASE_REQUIRED,
        "'"
            + resolvedTarget
            + "' is the repository's default branch and is written by the release flow alone. Ask"
            + " qits-projects for a release request naming this branch; it lands the release as a"
            + " tag and merges the default branch once the deployment is live. POST"
            + " /workspaces/api/workspaces/"
            + id
            + "/integrate merges into this branch's parent instead.");
  }

  /** The id of the ACTIVE workspace owning {@code branch}, or null — for an error message only. */
  private Long findWorkspaceIdForBranch(String repoId, String branch) {
    Workspace wt = findWorkspaceByBranch(repoId, branch);
    return wt == null ? null : wt.id;
  }

  /**
   * Integrates an arbitrary branch into a target branch, defaulting to the repository's configured
   * main branch when {@code target} is blank. Unlike {@link #mergeWorkspace}, the source needs no
   * workspace of its own — its branch ref is merged into the target's workspace (a temporary one is
   * created and removed when the target isn't checked out anywhere).
   */
  public MergeResult mergeBranch(String repoId, String source, String target) {
    return mergeBranch(repoId, source, target, null);
  }

  @Transactional
  public MergeResult mergeBranch(String repoId, String source, String target, String result) {
    // `source`/`target` are user-supplied: reject blank or dash-leading names so a value like
    // "-D" can't be smuggled to git as a flag (argv flag injection).
    if (source == null || source.isBlank() || source.startsWith("-")) {
      throw new BadRequestException("Invalid source branch: " + source);
    }

    var repo = repositories.require(repoId);

    String resolvedTarget = (target == null || target.isBlank()) ? repo.mainBranch() : target;
    if (resolvedTarget == null || resolvedTarget.isBlank() || resolvedTarget.startsWith("-")) {
      throw new BadRequestException("Invalid target branch: " + target);
    }
    if (source.equals(resolvedTarget)) {
      throw new BadRequestException("Cannot integrate '" + source + "' into itself");
    }
    // Checked after the self-merge guard, which is a plainer error about the same request: merging
    // a branch into itself is malformed whatever the branch is, while the rule below is about which
    // door writes the default branch.
    refuseMainAsMergeTarget(repo, resolvedTarget, findWorkspaceIdForBranch(repoId, source));
    // Guard and complete the source before the origin-side merge reads its ref: refuse a dirty
    // working tree (a plain branch has none, so it is never blocked) and push any commits that live
    // only inside the source container so they aren't silently dropped from the integration.
    requireSyncedSourceForIntegration(repoId, findWorkspaceByBranch(repoId, source));

    MergeResult merged = mergeIntoTarget(repoId, source, resolvedTarget);

    // After a clean integration the source branch's commits live in the target, so when the source
    // is now safe to remove (fully merged, clean if workspace-backed, no dependents) we clean it up
    // —
    // whether it is a workspace or a plain branch.
    boolean cleanedUp = false;
    if (!merged.hasConflicts()) {
      // The integration advanced the target's origin ref; if a live workspace owns it, pull it in
      // now (before any source cleanup — target and source are distinct branches).
      notifyIncomingMerge(repoId, resolvedTarget);
      if (canCleanupBranch(repoId, source, repo.mainBranch())) {
        doCleanupBranch(repoId, source, result);
        cleanedUp = true;
      }
    }

    return new MergeResult(merged.commitHash(), merged.hasConflicts(), merged.output(), cleanedUp);
  }

  /**
   * Removes a branch (and its workspace, if any) only when it is safe to do so — fully merged,
   * clean working tree when workspace-backed, no dependent workspaces (see {@link
   * #canCleanupBranch}). Because the UI performs this without a confirmation, the safety is
   * enforced here: an ineligible branch yields a 400 and is left untouched.
   */
  public void cleanupBranch(String repoId, String branch) {
    cleanupBranch(repoId, branch, null);
  }

  @Transactional
  public void cleanupBranch(String repoId, String branch, String result) {
    var repo = repositories.require(repoId);

    if (!canCleanupBranch(repoId, branch, repo.mainBranch())) {
      throw new BadRequestException(
          "Branch '"
              + branch
              + "' cannot be cleaned up: it has uncommitted changes, unmerged commits, or dependent"
              + " workspaces");
    }

    doCleanupBranch(repoId, branch, result);
  }

  /**
   * Deletes a branch: resolves its workspace as INTEGRATED when one is checked out (reusing the
   * discard mechanics), otherwise pushes the deletion to the git host. Callers gate on {@link
   * #canCleanupBranch}.
   */
  private void doCleanupBranch(String repoId, String branch, String result) {
    Workspace wt = findWorkspaceByBranch(repoId, branch);
    if (wt != null) {
      doDiscard(repoId, wt, WorkspaceStatus.INTEGRATED, result);
      return;
    }
    try {
      PushOutcome deleted = mirrors.of(repoId).deleteBranch(branch);
      if (!deleted.accepted()) {
        throw new InternalServerErrorException(
            "Failed to delete branch '" + branch + "': " + deleted.output());
      }
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException(
          "Failed to delete branch '" + branch + "': " + e.getMessage());
    }
  }

  /** One repository the sweep is asked to examine — the caller's catalogue row, not a lookup. */
  public record SweepRepository(String id, String name, String mainBranch) {}

  /** One branch the sweep removed (or, on a dry run, would remove). */
  public record SweptBranch(String repositoryId, String repositoryName, String branch) {}

  /** One branch (or whole repository) the sweep could not judge; the sweep went on without it. */
  public record SweepError(String repositoryId, String branch, String error) {}

  /**
   * @param removed what was deleted — or, on a dry run, what would have been
   * @param errors what could not be judged or deleted; never a reason to stop the rest
   */
  public record BranchSweepReport(
      boolean dryRun,
      int repositoriesExamined,
      int branchesExamined,
      List<SweptBranch> removed,
      List<SweepError> errors) {}

  /**
   * The nightly half of branch cleanup: remove every <b>plain</b> branch that is fully merged —
   * the refs the branch dropdowns collect after a release deletes a workspace but an ad-hoc push,
   * an abandoned epic or a typo left its branch behind.
   *
   * <p><b>What it never touches, and why each refusal is hard-coded here</b> rather than left to
   * configuration:
   *
   * <ul>
   *   <li><b>The repository's main branch</b> — {@link #canCleanupBranch} refuses it too; belt and
   *       braces for the ref everything else forks from.
   *   <li><b>Workspace-backed branches</b> — {@link #cleanupBranch} deletes the workspace along
   *       with the branch, which is right under a human's click and wrong overnight: a freshly
   *       provisioned workspace with nothing committed yet is "fully merged", and its owner would
   *       find their container gone by morning. An automated run removes only what no active
   *       workspace stands on.
   * </ul>
   *
   * <p>Everything that survives those refusals still goes through {@link #canCleanupBranch} — the
   * same single criterion the UI and the endpoint use, mirror-refreshed per question — and then
   * {@link #cleanupBranch}, which re-checks under its transaction. A branch or repository this
   * cannot judge (an unreachable mirror, a race with a concurrent delete) is recorded and skipped:
   * a sweep is a best-effort pass over the whole estate, and one broken repository must not keep
   * every other one's refs.
   */
  public BranchSweepReport sweepMergedBranches(
      List<SweepRepository> repositories, Set<String> keepPrefixes, boolean dryRun) {
    List<SweptBranch> removed = new ArrayList<>();
    List<SweepError> errors = new ArrayList<>();
    // Every prefix kept is the caller's now. `environment/` used to be hard-coded here because a
    // release promoted its sha onto the tier's deploy ref, which made those branches fully merged
    // by construction — exactly the shape this sweep condemns. Nothing writes them any more:
    // deployment follows the release event, not a ref, so a surviving environment/* branch is a
    // leftover and the sweep may take it. A deployment that still wants one kept passes it.
    Set<String> protectedPrefixes = new java.util.HashSet<>(keepPrefixes == null ? Set.of() : keepPrefixes);
    int repositoriesExamined = 0;
    int branchesExamined = 0;

    for (SweepRepository repository : repositories == null ? List.<SweepRepository>of() : repositories) {
      if (repository == null || repository.id() == null || repository.id().isBlank()) {
        continue;
      }
      List<String> branches;
      try {
        branches = mirrors.of(repository.id()).remoteBranches();
      } catch (RuntimeException e) {
        errors.add(new SweepError(repository.id(), null, e.getMessage()));
        continue;
      }
      repositoriesExamined += 1;
      for (String branch : branches) {
        branchesExamined += 1;
        if (branch.isBlank()
            || branch.equals(repository.mainBranch())
            || protectedPrefixes.stream().anyMatch(branch::startsWith)
            || findWorkspaceByBranch(repository.id(), branch) != null) {
          continue;
        }
        try {
          if (!canCleanupBranch(repository.id(), branch, repository.mainBranch())) {
            continue;
          }
          if (!dryRun) {
            cleanupBranch(repository.id(), branch);
          }
          removed.add(new SweptBranch(repository.id(), repository.name(), branch));
        } catch (RuntimeException e) {
          errors.add(new SweepError(repository.id(), branch, e.getMessage()));
        }
      }
    }
    return new BranchSweepReport(dryRun, repositoriesExamined, branchesExamined, removed, errors);
  }

  /**
   * Merges {@code sourceBranch} into {@code resolvedTarget}, in a detached worktree on the
   * repository's <b>mirror</b>, and <b>pushes</b> the result.
   *
   * <p>This is the call site the whole de-filesystem change is about. It used to add a worktree on
   * the bare origin qits-artifacts serves and let the merge advance the target's ref there, with no
   * push — which is why <b>no merge this service ever performed produced a CI run</b>. Nothing about
   * the merge changed; where it happens and how the result arrives did.
   *
   * <p>No push option: {@code refuseMainAsMergeTarget} has already established that the target is
   * not the default branch, so this is an ordinary push through an unguarded ref. It is still a
   * compare-and-swap — fast-forward-only belongs to receive-pack, not to an option.
   */
  private MergeResult mergeIntoTarget(String repoId, String sourceBranch, String resolvedTarget) {
    RepoMirror mirror = mirrors.of(repoId);
    try {
      mirror.refreshNow();
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException(
          "Could not read the repository from the git host: " + e.getMessage());
    }
    // Named after the source branch, which is unique per repository by construction — the
    // `.tmp-merge-<currentTimeMillis>` this replaces collided within a millisecond.
    try (MirrorWorktree worktree =
        mirror.worktree(sourceBranch, "refs/heads/" + resolvedTarget)) {
      // The one host-spawned synthetic commit. Identity is delivered both as -c (explicit in the
      // argv) AND as GIT_AUTHOR_*/GIT_COMMITTER_* env scoped to this invocation — the env form is
      // what actually guarantees attribution, because an ambient identity env inherited from the
      // host would otherwise outrank the -c config.
      MergeOutcome merged =
          worktree.mergeAndCommit(
              "refs/heads/" + sourceBranch,
              "Merge " + sourceBranch + " into " + resolvedTarget,
              gitIdentity.forMirror());
      if (!merged.clean()) {
        // Answered rather than thrown, which is this surface's whole difference from /integrate.
        return new MergeResult(null, true, merged.output(), false);
      }
      String commitHash = worktree.headSha();
      PushOutcome pushed =
          worktree.push(PushSpec.of(PushSpec.Ref.branch("HEAD", resolvedTarget)));
      if (!pushed.accepted()) {
        throw new InternalServerErrorException(
            "The merge was built but the push was refused: " + pushed.output());
      }
      return new MergeResult(commitHash, false, merged.output(), false);
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Git merge failed: " + e.getMessage());
    }
  }


  public void discardWorkspace(Long id) {
    discardWorkspace(id, null);
  }

  public void discardWorkspace(Long id, String result) {
    discardWorkspace(id, result, false);
  }

  /**
   * Abandon the workspace. The clean-tree guard is the default and stays the API's posture; {@code
   * force} is the person-in-front-of-a-dialog override — the discard UI shows what will be thrown
   * away and asks twice, and only that confirmed press sends {@code true}. Discard is already
   * deliberately lossy (container, volume and branch all go); what force skips is only the refusal
   * to lose work the daemon still reports as uncommitted.
   */
  @Transactional
  public void discardWorkspace(Long id, String result, boolean force) {
    Workspace workspace = requireActive(id);
    String repoId = workspace.repositoryId;
    repositories.require(repoId);

    if (!force) {
      requireCleanWorkingTree(repoId, workspace, "abandon");
    }
    doDiscard(repoId, workspace, WorkspaceStatus.ABANDONED, result);
  }

  /**
   * What {@link #resolveReleasedBranch} answers: whether a workspace was standing on the released
   * branch, and the row id of the one that was. {@code resolved:false} with no id is the ordinary
   * answer — most released branches never carried a workspace — and it is the answer a second call
   * gets too, which is what makes a best-effort caller free to retry.
   */
  public record BranchResolution(boolean resolved, Long workspaceId) {}

  /**
   * Resolve the workspace standing on a branch a <b>release has just deleted</b> on the git host, as
   * {@code INTEGRATED}.
   *
   * <p><b>Why this door exists.</b> qits-projects' Auto Release deletes each released branch through
   * a qits-githost primitive that writes the ref in core and fires no event at all, so nothing here
   * ever learns the branch is gone. The workspace standing on it therefore stays ACTIVE forever,
   * holding a container, a volume and a commissioned credential for a branch that no longer exists,
   * until somebody notices and abandons it by hand — measured live on 2026-09-05 on the
   * storage-creep wrapper workspace. So the release says so, beside the deletion it just made.
   *
   * <p><b>This is a workspace-lifecycle door that a release happens to call, and it is deliberately
   * not a release door.</b> The release flow left this service on 2026-09-03 and stays gone
   * (AGENTS.md, "The release door left, and what stayed"): nothing here merges, stamps, bumps, tags,
   * pushes or announces, and no events module comes back for it. What arrives is a fact about a
   * <em>branch</em> — it is gone, and this version consumed it — and what happens is the resolution
   * this service already performs whenever a workspace's branch stops existing.
   *
   * <p><b>No workspace is the normal answer, not an error.</b> Most released branches carry none, so
   * an absent one answers {@code resolved:false} and touches nothing. That also makes a second call
   * after a resolution a no-op, which the caller — best-effort, and free to retry — needs.
   *
   * <p><b>The main workspace is refused on both belts.</b> A row whose {@code parent} is null (what
   * the retired per-project editor's main workspaces carry, and nothing else does), and a branch
   * equal to the repository's default branch, are two independent readings of the same fact: the
   * first is this service's own record, the second is qits-projects'. Both are checked, because a
   * main branch renamed between the two would leave exactly one of them right, and the cost of being
   * wrong is the workspace a whole repository is worked in. An ordinary discard still resolves such
   * a row, but {@link #doDiscard} keeps its branch on the same two belts (qits-780).
   *
   * <p><b>A dirty or unpushed container is LOGGED and never refused.</b> This is the one place this
   * service knowingly discards uncommitted work, and it is still the better answer: the branch is
   * gone, so there is nothing left to push to and no ref the work could be recovered from, while the
   * alternative is an immortal ACTIVE workspace on a deleted branch holding a container and a
   * credential nobody will reclaim. The WARN names the workspace and says a release consumed its
   * branch, so the loss is at least on the record. The <em>unpushed</em> half is not probed
   * separately for the reason it cannot be: {@link #isFullyPushed} compares the container's head
   * against the branch's ref on the git host, and that ref is precisely what the release deleted —
   * it would answer "gone" and be read as "unpushed" every time. The clean probe, which already
   * treats unknown as dirty, is the whole of it.
   *
   * <p>The teardown is {@link #doDiscard}'s with the branch deletion skipped, and it is deliberately
   * <b>not</b> routed through {@link #cleanupBranch}/{@link #canCleanupBranch}/{@link
   * #sweepMergedBranches}: those decide whether a branch <em>may</em> be deleted and fail closed on
   * a ref they cannot resolve. Here the deletion has already happened, by somebody entitled to make
   * it, so every premise they hold is inverted.
   *
   * @param target the release version the branch was consumed by, recorded on the history event
   * @param commit the released sha, recorded on the same event
   */
  @Transactional
  public BranchResolution resolveReleasedBranch(
      String repoId, String branch, String target, String commit, String result) {
    if (branch == null || branch.isBlank()) {
      throw new BadRequestException("A branch is required to resolve a released branch.");
    }
    Optional<Workspace> standing =
        workspaceRepository.findActiveByRepositoryAndBranch(repoId, branch);
    if (standing.isEmpty()) {
      // The registry is not asked at all on this path, and that is deliberate: a release calls this
      // once per branch it deletes, and the answer for most of them is "nothing here" — which must
      // not cost a qits-projects round trip, nor fail when qits-projects is away.
      return new BranchResolution(false, null);
    }
    Workspace workspace = standing.get();

    // Belt one: this service's own record of what a main workspace is.
    if (workspace.parent == null || workspace.parent.isBlank()) {
      throw new BadRequestException(
          "Workspace '"
              + workspace.workspaceId
              + "' has no parent branch, which is what makes it the repository's main workspace: a"
              + " release does not resolve it.");
    }
    // Belt two: qits-projects' record of the same thing, read independently.
    RepositoryLookup.RepositoryView repo = repositories.require(repoId);
    if (branch.equals(defaultMainBranch(repo))) {
      throw new BadRequestException(
          "Branch '"
              + branch
              + "' is the repository's default branch, so the workspace on it is the main workspace:"
              + " a release does not resolve it.");
    }

    if (!isWorkspaceClean(repoId, workspace)) {
      LOG.warnf(
          "Resolving workspace %s (row %s, branch '%s') although its container reports uncommitted"
              + " or unreported work: a release consumed the branch, so there is nothing left to"
              + " push to.",
          workspace.workspaceId, workspace.id, branch);
    }

    doDiscard(repoId, workspace, WorkspaceStatus.INTEGRATED, result, target, commit, false);
    return new BranchResolution(true, workspace.id);
  }

  /**
   * Removes a workspace from disk and deletes its branch, then <em>soft-deletes</em> the row: it is
   * marked with its {@code resolution} status ({@code INTEGRATED} for cleanup, {@code ABANDONED}
   * for discard) and kept as a persistent record (with its history events and the commands that ran
   * in it) rather than deleted. The on-disk metadata file is removed so discovery won't re-process
   * it.
   */
  private void doDiscard(
      String repoId, Workspace workspace, WorkspaceStatus resolution, String result) {
    doDiscard(repoId, workspace, resolution, result, null, null);
  }

  /**
   * {@link #doDiscard(String, Workspace, WorkspaceStatus, String)} with the branch and commit the
   * resolution refers to, recorded on the history event. Integrate is the caller that has both — the
   * default branch it released into and the merge commit's sha — and a resolution event that names
   * neither is a timeline entry a person cannot follow back to the release.
   */
  private void doDiscard(
      String repoId,
      Workspace workspace,
      WorkspaceStatus resolution,
      String result,
      String target,
      String commit) {
    doDiscard(repoId, workspace, resolution, result, target, commit, true);
  }

  /**
   * The teardown itself, with one thing made optional: whether the branch is <b>deleted</b> as part
   * of it.
   *
   * <p>{@code deleteBranch} is false for exactly one caller — {@link #resolveReleasedBranch}, where
   * a release has already deleted the ref on the git host — and the flag exists rather than a second
   * copy of this sequence because the ORDER is the part worth having one of. {@code fireStopping}
   * before {@code containers.rm} is pinned by an observer (a service settling after its container is
   * gone reads as a crash to be resurrected), the container goes before its volume (docker refuses
   * an in-use volume), and the credential goes back beside the {@code rm} rather than on the event
   * this method fires. A duplicate would be free to drift on every one of them.
   *
   * <p>Skipping the deletion is not merely an optimisation. The push would be a wire round trip
   * whose only possible outcome on an absent ref is the failure the catch below swallows — so the
   * flag turns a silent no-op into a stated one, and the same reading {@link #ensureContainer}'s
   * branch-gone abandon already makes one hop earlier.
   */
  private void doDiscard(
      String repoId,
      Workspace workspace,
      WorkspaceStatus resolution,
      String result,
      String target,
      String commit,
      boolean deleteBranch) {
    boolean deletesBranch = deleteBranch && !keepsBranch(repoId, workspace);
    if (workspace.placement == WorkspacePlacement.RUNNER) {
      discardOnRunner(repoId, workspace, resolution, result, target, commit, deletesBranch);
      return;
    }
    WorkspacePlacements.requireDirectAllowed(workspace);
    try {
      String branch = workspace.branch;

      // Remove the workspace's container AND its persistent /workspace volume. Discard is
      // intentionally lossy: unlike the graceful stopContainer (which docker-stops in place so the
      // container and its /workspace volume survive), here we delete the container, its volume, AND
      // the branch right after, so preserving /workspace would be pointless — the operator asked to
      // throw this work away. Container first, then the volume (docker refuses an in-use volume).
      // Settle any live services first (immediate — no graceful signal, the work is being
      // discarded)
      // so their disappearance doesn't read as a crash to be resurrected.
      //
      // The container itself still gets a best-effort docker stop ahead of the rm, though, and for
      // a reason that has nothing to do with services: a coding agent running inside (claude
      // --remote-control) only archives its claude.ai session on SIGTERM, and an rm straight to
      // SIGKILL leaves that session dangling. stopBeforeRemove gives it the chance; the rm follows
      // regardless of whether the stop lands.
      containerEvents.fireStopping(repoId, workspace.workspaceId, workspace.id, false);
      stopBeforeRemove(containers.containerName(workspace.workspaceId, repoId));
      containers.rm(containers.containerName(workspace.workspaceId, repoId));
      containers.removeWorkspaceVolume(workspace.workspaceId);
      // The credential dies with the container, so it goes back here — beside the rm, not on the
      // WorkspaceResolved event this method fires below.
      //
      // An observer on that event would be the tidier-looking seam and would be wrong twice. It
      // covers the resolution paths and NOT deleteContainer, which removes a container while the row
      // stays ACTIVE and fires nothing — so one mechanism would still need a second call site, and
      // two mechanisms for one rule is how they drift apart. And the event is fired synchronously
      // inside the resolving transaction so observers can join it, which is the one place this HTTP
      // call must not be. Beside containers.rm — itself an HTTP call, best-effort for the same
      // reason — is where the container's other teardown already sits.
      workspace.commissionedClientSecret = null;
      String commissioned = workspace.commissionedClientId;
      workspace.commissionedClientId = null;
      decommission(commissioned);

      if (deletesBranch && branch != null && !branch.isBlank()) {
        try {
          mirrors.of(repoId).deleteBranch(branch);
        } catch (GitMirrorException ignored) {
          // the branch may already be gone, and the resolution is not conditional on the ref
        }
      }

      workspace.status = resolution;
      workspace.resolvedAt = Instant.now();
      if (result != null && !result.isBlank()) {
        workspace.result = result;
      }
      recordEvent(
          workspace,
          resolution == WorkspaceStatus.INTEGRATED
              ? WorkspaceEventType.INTEGRATED
              : WorkspaceEventType.ABANDONED,
          branch,
          target,
          commit);
      // Pre-launch composition state (prompt drafts, their attachments) is not a durable record
      // like the history events, and its FK cascade never fires because the workspace row is only
      // soft-deleted. Whoever owns those tables drops them on this event, in this transaction.
      workspaceResolvedEvent.fire(
          new WorkspaceResolved(repoId, workspace.workspaceId, workspace.id, resolution));
      workspaceMetadata.delete(repoId, workspace.workspaceId);
    } catch (InternalServerErrorException e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git discard failed: " + e.getMessage());
    }
  }

  /**
   * Whether a resolution must leave this workspace's branch on the git host: <b>a main workspace's
   * branch is never deleted</b> (qits-780). Two independent readings, as {@link
   * #resolveReleasedBranch} makes them: a row with no {@code parent} (what the retired per-project
   * editor's main workspaces carry, and nothing else), and a branch equal to the repository's
   * default branch. A repository the registry cannot name keeps the branch too, since deleting is
   * the direction this has to fail away from. Each keep is logged.
   */
  private boolean keepsBranch(String repoId, Workspace workspace) {
    String branch = workspace.branch;
    if (branch == null || branch.isBlank()) {
      return false;
    }
    if (workspace.parent == null || workspace.parent.isBlank()) {
      LOG.infof(
          "Workspace %s (row %s) has no parent branch, so it is a main workspace: its branch '%s'"
              + " is kept on the git host",
          workspace.workspaceId, workspace.id, branch);
      return true;
    }
    Optional<RepositoryLookup.RepositoryView> repo;
    try {
      repo = repositories.find(repoId);
    } catch (RuntimeException e) {
      repo = Optional.empty();
    }
    if (repo.isEmpty()) {
      LOG.warnf(
          "Repository %s could not be read, so workspace %s (row %s) keeps its branch '%s' on the"
              + " git host",
          repoId, workspace.workspaceId, workspace.id, branch);
      return true;
    }
    if (branch.equals(defaultMainBranch(repo.get()))) {
      LOG.infof(
          "Branch '%s' of workspace %s (row %s) is the repository's default branch: it is kept on"
              + " the git host",
          branch, workspace.workspaceId, workspace.id);
      return true;
    }
    return false;
  }

  // --- RUNNER placement (epic qits-624, qits-853) ----------------------------------------------
  //
  // Each container verb above branches ONCE, at its top, on Workspace.placement, and a RUNNER row
  // comes here. Nothing below calls qits-containers: a RUNNER row's container is on a runner's node,
  // reached through RunnerPlacement, and its persisted runtime status is the runner's word. Its
  // start builds the edge address plane (qits-799) and mints the workspace token (qits-802) before
  // queueing, and fails the row when it can have neither; RunnerClaims waits for its daemon after
  // the runner launched it. No bootstrap chain is run.

  /**
   * Start, for a RUNNER row: <b>start is pull</b>. The row is marked QUEUED and waits for a runner's
   * reserve to take it ({@link RunnerClaims#reserveFor}); nothing is pushed to any runner.
   *
   * <ul>
   *   <li>a row whose runner is offline past the grace (UNAVAILABLE) → 409 {@code
   *       RUNNER_UNAVAILABLE}: it is sticky to that runner and nothing else can start it;
   *   <li>already RUNNING, PROVISIONING or QUEUED → nothing to do. The start process still open
   *       for it is answered, so a second press joins the first;
   *   <li>the durable branch is gone → abandoned and 404, as rung 3 of the DIRECT ladder does;
   *   <li>no edge address plane ({@link EdgePlaneUnconfigured}: no public {@code QITS_DOMAIN}) →
   *       FAILED with {@code EDGE_PLANE_UNCONFIGURED} as its runtime error, and nothing queued;
   *   <li>no workspace token and none could be minted → FAILED with {@code
   *       WORKSPACE_TOKEN_UNAVAILABLE} and the reason, and nothing queued ({@link
   *       #ensureRunnerToken}): a RUNNER container reaches every hop with that token, so a row
   *       never becomes claimable without one;
   *   <li>STOPPED or FAILED → the compare-and-swap to QUEUED, {@code queuedAt} now, the runner
   *       kept, and the backlog told.
   * </ul>
   *
   * <p>The process it answers opens segment {@code queued} ("waiting for a slot on &lt;runner&gt;",
   * "waiting for a runner" for a row on none, or "no enabled workspace runner" when no runner could
   * take it at all — the same line a re-press on such a queued row answers); the claim settles it
   * and opens {@code container}, and the runner's {@code launched} or {@code launchFailed} settles
   * that.
   */
  private String beginRunnerStart(Workspace row) {
    return beginRunnerStart(row, true);
  }

  /**
   * {@link #beginRunnerStart(Workspace)}, with or without its technical process. A create queues
   * its row through here unnarrated ({@code narrate} false): no process is opened and none is
   * answered, and the row's runtime status is the whole of its progress (qits-853).
   */
  private String beginRunnerStart(Workspace row, boolean narrate) {
    if (row.runnerId != null && !runnerPresent(row.runnerId)) {
      throw RunnerRefusals.unavailable(row.id, "start");
    }
    if (row.runtimeStatus == WorkspaceRuntimeStatus.RUNNING
        || row.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING
        || row.runtimeStatus == WorkspaceRuntimeStatus.QUEUED) {
      Optional<WorkspaceProcessTracker.Handle> open = runnerClaims.trackedStart(row.id);
      if (open.isPresent()) {
        return narrate ? open.get().id() : null;
      }
      WorkspaceProcessTracker.Handle process =
          narrate ? tracker(row.repositoryId, row.workspaceId, row.id) : null;
      if (process != null) {
        // A queued row nothing could take says why it waits (qits-774): it is not an error, the
        // row is simply parked until a runner is enabled.
        process.completeNoOp(
            RunnerClaims.QUEUED_SEGMENT,
            row.runtimeStatus == WorkspaceRuntimeStatus.QUEUED && noRunnerCanTake(row.runnerId)
                ? NO_ENABLED_RUNNER
                : "Already " + row.runtimeStatus.name().toLowerCase() + " — nothing to do.");
      }
      return process == null ? null : process.id();
    }
    if (row.branch == null || row.branch.isBlank() || !branchExists(row.repositoryId, row.branch)) {
      abandonRunnerRow(row);
    }
    // The edge plane, BEFORE the row is queued: a deployment with no public domain has no address a
    // runner's container could reach home by, so the row goes FAILED saying so and no runner is
    // ever sent it. Nothing else is read off the plane here; the claim composes the spec from it.
    try {
      addressPlanes.plane();
    } catch (EdgePlaneUnconfigured refused) {
      return failUnplaceable(row, refused.getMessage(), narrate);
    }
    // The workspace token, after the plane and BEFORE the queue, outside every transaction: the
    // mint is an HTTP call to qits-idp. A row that already holds one keeps it — a STOPPED row's
    // container carries it in its spec, and the start re-presents that spec unchanged.
    String unminted = ensureRunnerToken(row);
    if (unminted != null) {
      return failUnplaceable(row, unminted, narrate);
    }

    WorkspaceProcessTracker.Handle process =
        narrate ? tracker(row.repositoryId, row.workspaceId, row.id) : null;
    if (process != null) {
      process.openSegment(RunnerClaims.QUEUED_SEGMENT);
      process.appendLine(RunnerClaims.QUEUED_SEGMENT, waitingFor(row.runnerId));
      // Carried BEFORE the swap, so a runner that takes the row the instant it is queued finds the
      // process to settle.
      runnerClaims.track(row.id, process);
    }
    int queued =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.queueForRunner(row.id, Instant.now()));
    if (queued == 0) {
      // Something else moved the row between the read and the swap: a second start, or a stop.
      runnerClaims.forget(row.id, process);
      if (process != null) {
        process.completeNoOp(
            RunnerClaims.QUEUED_SEGMENT, "The workspace is not stopped any more — nothing to queue.");
      }
      return process == null ? null : process.id();
    }
    changePublisher.runtimeChanged(row.repositoryId, row.id);
    if (runnerPlacement.isResolvable()) {
      runnerPlacement.get().backlogChanged(row);
    }
    return process == null ? null : process.id();
  }

  /**
   * A RUNNER start refused before the queue: the row FAILED with the refusal as its runtime error
   * ({@code EDGE_PLANE_UNCONFIGURED: QITS_DOMAIN '<v>' is not a public domain}, or {@code
   * WORKSPACE_TOKEN_UNAVAILABLE: …}), the start's process — when it is narrated — failed with one
   * {@code container} segment saying the same, and nothing queued.
   */
  private String failUnplaceable(Workspace row, String reason, boolean narrate) {
    LOG.errorf(
        "Workspace %s/%s cannot be placed on a runner: %s",
        row.repositoryId, row.workspaceId, reason);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaceRepository
                    .findByIdOptional(row.id)
                    .ifPresent(
                        w -> {
                          w.runtimeStatus = WorkspaceRuntimeStatus.FAILED;
                          w.runtimeError = truncate(reason);
                          w.queuedAt = null;
                        }));
    changePublisher.runtimeChanged(row.repositoryId, row.id);
    WorkspaceProcessTracker.Handle process =
        narrate ? tracker(row.repositoryId, row.workspaceId, row.id) : null;
    if (process == null) {
      return null;
    }
    process.openSegment(RunnerClaims.CONTAINER_SEGMENT);
    process.appendLine(RunnerClaims.CONTAINER_SEGMENT, reason);
    process.settleSegment(RunnerClaims.CONTAINER_SEGMENT, false);
    process.failProvision(reason);
    return process.id();
  }

  /** The code a RUNNER row's runtime error starts with when no workspace token could be had. */
  static final String TOKEN_UNAVAILABLE = "WORKSPACE_TOKEN_UNAVAILABLE";

  /**
   * Makes sure {@code row} holds its workspace token (qits-625, qits-802), minting one when it holds
   * none; answers null when it does, else the reason it does not, as a runtime error.
   *
   * <p>The RUNNER counterpart of {@link #commissionFor}: the same context ({@code workspace}, the
   * row id), the same {@code project} claim and the same Git refs, read and stored the same way —
   * but a {@code qits_tok_} rather than a client pair, and never both. Called outside every
   * transaction; each row read and write is its own.
   *
   * <p><b>Reused, not replaced.</b> A row that holds a token keeps it: the token lives as long as
   * the container (delete-container, recreate and resolution delete it), and a stop does not
   * revoke it, because the start re-presents the spec that carries it.
   *
   * <p><b>Refused, not degraded.</b> A RUNNER container has no other credential, so no issuer, an
   * issuer that stayed unreachable through the commissioner's patience, or an empty answer all fail
   * the start — unlike a DIRECT row, which launches with no credential when no issuer is wired.
   *
   * <p>Two starts racing both mint; the second to store finds the first's token and deletes its own.
   */
  private String ensureRunnerToken(Workspace row) {
    Long rowId = row.id;
    boolean held =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository
                        .findActiveById(rowId)
                        .map(wt -> wt.commissionedTokenId != null)
                        .orElse(false));
    if (held) {
      return null;
    }
    if (!commissioner.isResolvable()) {
      return TOKEN_UNAVAILABLE + ": no issuer is wired to mint the workspace token";
    }
    RepositoryLookup.RepositoryView repository = repositoryOf(row.repositoryId);
    String defaultBranch = repository == null ? null : defaultMainBranch(repository);
    String statedRefs =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository
                        .findActiveById(rowId)
                        .map(wt -> GitRefs.write(GitRefs.effective(wt, defaultBranch)))
                        .orElse(null));
    Optional<WorkspaceToken> issued;
    try {
      issued =
          commissioner
              .get()
              .commissionToken(
                  rowId,
                  projectOf(repository),
                  statedRefs == null ? null : GitRefs.read(statedRefs));
    } catch (RuntimeException failed) {
      return TOKEN_UNAVAILABLE + ": " + failed.getMessage();
    }
    if (issued.isEmpty()) {
      return TOKEN_UNAVAILABLE + ": no issuer is configured to mint the workspace token";
    }
    WorkspaceToken token = issued.get();
    // [stored, narrowedMeanwhile]
    boolean[] outcome =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository
                        .findActiveById(rowId)
                        .map(
                            wt -> {
                              if (wt.commissionedTokenId != null) {
                                return new boolean[] {false, false};
                              }
                              wt.commissionedTokenId = token.tokenId();
                              wt.commissionedTokenSubject = token.subject();
                              wt.commissionedToken = token.token();
                              // commissionFor's two rules, for the same reasons: a stored list
                              // never names the default branch, and a narrowing that landed while
                              // the mint was made is still to be sent.
                              List<String> allowed = GitRefs.effective(wt, defaultBranch);
                              if (wt.gitRefs != null
                                  && !GitRefs.read(wt.gitRefs).equals(allowed)) {
                                wt.gitRefs = GitRefs.write(allowed);
                              }
                              wt.gitRefsPending = !GitRefs.write(allowed).equals(statedRefs);
                              return new boolean[] {true, wt.gitRefsPending};
                            })
                        .orElse(new boolean[] {false, false}));
    if (!outcome[0]) {
      // Another start stored a token first, or the row resolved meanwhile: this one is nobody's.
      deleteToken(token.tokenId());
      boolean nowHeld =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      workspaceRepository
                          .findActiveById(rowId)
                          .map(wt -> wt.commissionedTokenId != null)
                          .orElse(false));
      return nowHeld ? null : TOKEN_UNAVAILABLE + ": the workspace is no longer active";
    }
    if (outcome[1]) {
      gitRefScopes.push(rowId);
    }
    LOG.debugf(
        "Minted workspace token %s for workspace %s/%s",
        token.tokenId(), row.repositoryId, row.workspaceId);
    return null;
  }

  /**
   * Why a QUEUED RUNNER row on no runner is waiting, when it is because no runner could take it at
   * all (qits-774): none registered, in service and with slots. A regular workspace is written
   * RUNNER whatever the estate holds, so this is a waiting state, never a refusal.
   */
  static final String NO_ENABLED_RUNNER = "no enabled workspace runner";

  /** Whether {@code runnerId} is none and no runner is eligible to take the row at all. */
  private boolean noRunnerCanTake(UUID runnerId) {
    return runnerId == null
        && !QuarkusTransaction.requiringNew().call(() -> runnerRepository.existsEligible());
  }

  /**
   * The {@code queued} segment's line: which runner the row waits for, that it waits for any, or
   * {@link #NO_ENABLED_RUNNER} when there is none it could wait for.
   */
  private String waitingFor(UUID runnerId) {
    if (runnerId == null) {
      return noRunnerCanTake(null) ? NO_ENABLED_RUNNER : "waiting for a runner";
    }
    String name =
        QuarkusTransaction.requiringNew()
            .call(() -> runnerRepository.namesById(Set.of(runnerId)).get(runnerId));
    return "waiting for a slot on " + (name == null ? runnerId.toString() : name);
  }

  /**
   * Rung 3 of the ladder for a RUNNER row: its durable branch is gone, so it is abandoned and 404s,
   * as a DIRECT row is. What differs is the teardown: the volume is on the runner's node, so a
   * runner holding the row is told it is released instead of a volume being removed here.
   */
  private void abandonRunnerRow(Workspace row) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Workspace wt = requireActive(row.id);
              wt.status = WorkspaceStatus.ABANDONED;
              wt.resolvedAt = Instant.now();
              wt.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
              wt.queuedAt = null;
              recordEvent(wt, WorkspaceEventType.ABANDONED, wt.branch, null, null);
              workspaceResolvedEvent.fire(
                  new WorkspaceResolved(
                      wt.repositoryId, wt.workspaceId, wt.id, WorkspaceStatus.ABANDONED));
            });
    releaseFromRunner(row);
    decommissionFor(row.id);
    throw new NotFoundException(
        "Workspace '" + row.workspaceId + "' has no branch to recreate from; abandoned");
  }

  /**
   * Stop, for a RUNNER row. A QUEUED row just goes back to STOPPED with no frame — nothing runs
   * anywhere yet — unless a runner took it in the meantime, when it is stopped like a placed row. A
   * row on no runner has nothing to stop. Otherwise {@code stop} is routed to its runner and awaited
   * ({@link RunnerPlacement#stop}: 409 {@code RUNNER_UNAVAILABLE} offline, 504 {@code
   * RUNNER_TIMEOUT} with the row unchanged), then the row is STOPPED.
   */
  private void stopOnRunner(Workspace row) {
    Workspace current = row;
    if (current.runtimeStatus == WorkspaceRuntimeStatus.QUEUED) {
      int unqueued =
          QuarkusTransaction.requiringNew().call(() -> workspaceRepository.unqueueForRunner(row.id));
      if (unqueued == 1) {
        runnerClaims.abandonStart(row.id, "Stopped before a runner took it.");
        changePublisher.runtimeChanged(row.repositoryId, row.id);
        if (runnerPlacement.isResolvable()) {
          runnerPlacement.get().backlogChanged(row);
        }
        return;
      }
      current = QuarkusTransaction.requiringNew().call(() -> requireActive(row.id));
    }
    if (current.runnerId == null) {
      return;
    }
    UUID runnerId = current.runnerId;
    placement(current.id, "stop").stop(current);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaceRepository
                    .findActiveById(row.id)
                    .filter(wt -> runnerId.equals(wt.runnerId))
                    .ifPresent(
                        wt -> {
                          wt.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                          wt.queuedAt = null;
                        }));
    runnerClaims.abandonStart(row.id, "Stopped.");
    changePublisher.runtimeChanged(row.repositoryId, row.id);
  }

  /**
   * Delete-container, for a RUNNER row: {@code delete} is routed to its runner and awaited (container
   * and {@code -ws-<rowId>} volume), then the row is STOPPED with <b>no runner</b>. Nothing of it is
   * left on that node, so stickiness ends and the next start may be taken by any runner. The row
   * stays ACTIVE. A row on no runner has nothing on any node, and just goes STOPPED.
   *
   * <p>The workspace token goes with the container (qits-625): its columns are cleared and it is
   * deleted at qits-idp, and the next start mints a fresh one for the container it launches.
   */
  private void deleteOnRunner(Workspace row) {
    UUID runnerId = row.runnerId;
    if (runnerId != null) {
      placement(row.id, "delete its container").delete(row);
    }
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaceRepository
                    .findActiveById(row.id)
                    .filter(wt -> Objects.equals(runnerId, wt.runnerId))
                    .ifPresent(
                        wt -> {
                          wt.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                          wt.runtimeError = null;
                          wt.runnerId = null;
                          wt.queuedAt = null;
                        }));
    runnerClaims.abandonStart(row.id, "The container was deleted.");
    decommissionFor(row.id);
    changePublisher.runtimeChanged(row.repositoryId, row.id);
    if (runnerPlacement.isResolvable()) {
      if (row.runtimeStatus == WorkspaceRuntimeStatus.QUEUED) {
        runnerPlacement.get().backlogChanged(row);
      }
      if (runnerId != null) {
        runnerPlacement.get().estateChanged(runnerId);
      }
    }
  }

  /**
   * Recreate, for a RUNNER row: the clean-tree gate first, exactly the DIRECT one (an unknown tree —
   * no daemon reporting — refuses with 400), then {@link #deleteOnRunner} (container and volume
   * gone, the workspace token deleted, the runner cleared), then the start, which mints a fresh
   * token and queues the row for any runner (qits-625).
   */
  private String beginRunnerRecreate(Workspace row) {
    requireCleanForRecreate(row.workspaceId, row.id);
    deleteOnRunner(row);
    return beginRunnerStart(QuarkusTransaction.requiringNew().call(() -> requireActive(row.id)));
  }

  /** The port for a routed verb, or 409 {@code RUNNER_UNAVAILABLE} when there is none. */
  private RunnerPlacement placement(Long rowId, String verb) {
    if (!runnerPlacement.isResolvable()) {
      throw RunnerRefusals.unavailable(rowId, verb);
    }
    return runnerPlacement.get();
  }

  /**
   * A RUNNER row left the estate: a connected runner holding it is told to delete it, without
   * waiting; an offline one drops it at its next estate. Never in the way of the resolution.
   */
  private void releaseFromRunner(Workspace row) {
    runnerClaims.abandonStart(row.id, "The workspace was resolved.");
    if (!runnerPlacement.isResolvable()) {
      return;
    }
    try {
      if (row.runnerId != null) {
        runnerPlacement.get().released(row);
        runnerPlacement.get().estateChanged(row.runnerId);
      } else if (row.runtimeStatus == WorkspaceRuntimeStatus.QUEUED) {
        runnerPlacement.get().backlogChanged(row);
      }
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not tell a runner workspace %s resolved; its estate will", row.id);
    }
  }

  /**
   * {@link #doDiscard}'s teardown for a RUNNER row. The row resolves exactly as a DIRECT one does
   * (the branch deletion, the status, the history event, the resolved event, the metadata) — the
   * tail below is that method's, kept in step with it — and what differs is the container half:
   * nothing here calls qits-containers, and resolution never waits on a runner ({@link
   * #releaseFromRunner}).
   */
  private void discardOnRunner(
      String repoId,
      Workspace workspace,
      WorkspaceStatus resolution,
      String result,
      String target,
      String commit,
      boolean deleteBranch) {
    try {
      String branch = workspace.branch;
      releaseFromRunner(workspace);
      workspace.commissionedClientSecret = null;
      String commissioned = workspace.commissionedClientId;
      workspace.commissionedClientId = null;
      decommission(commissioned);
      // The workspace token goes with the container it was minted for (qits-625), beside the
      // release and for doDiscard's reason not on the resolved event.
      deleteToken(clearToken(workspace));

      if (deleteBranch && branch != null && !branch.isBlank()) {
        try {
          mirrors.of(repoId).deleteBranch(branch);
        } catch (GitMirrorException ignored) {
          // the branch may already be gone, and the resolution is not conditional on the ref
        }
      }

      workspace.status = resolution;
      workspace.resolvedAt = Instant.now();
      workspace.queuedAt = null;
      if (result != null && !result.isBlank()) {
        workspace.result = result;
      }
      recordEvent(
          workspace,
          resolution == WorkspaceStatus.INTEGRATED
              ? WorkspaceEventType.INTEGRATED
              : WorkspaceEventType.ABANDONED,
          branch,
          target,
          commit);
      workspaceResolvedEvent.fire(
          new WorkspaceResolved(repoId, workspace.workspaceId, workspace.id, resolution));
      workspaceMetadata.delete(repoId, workspace.workspaceId);
    } catch (InternalServerErrorException e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git discard failed: " + e.getMessage());
    }
  }

  /**
   * A tracking handle for a streamed operation, or {@code null} when no {@link
   * WorkspaceProcessTracker} is installed — every call site already treats null as "run it
   * unnarrated", which is the pre-streaming behaviour.
   */
  private WorkspaceProcessTracker.Handle tracker(String repoId, String workspaceId, Long rowId) {
    return processes.isResolvable() ? processes.get().begin(repoId, workspaceId, rowId) : null;
  }

  public record MergeResult(
      String commitHash, boolean hasConflicts, String output, boolean cleanedUp) {}

  /**
   * What a successful integrate answers: the merge commit, the source branch — which the merge's
   * parents record as a sha but never as a name — and the target it landed on. <b>No version</b>,
   * because this service mints none: a version belongs to a release, and a release is a release
   * request in qits-projects.
   */
  public record IntegrateResult(String commitSha, String branch, String targetBranch) {}
}
