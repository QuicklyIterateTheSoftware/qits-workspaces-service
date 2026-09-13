package eu.wohlben.qits.workspaces.containershost;

import eu.wohlben.qits.containers.client.ContainersAnswer;
import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.DeleteOutcome;
import eu.wohlben.qits.containers.client.ContainersWire.Envelope;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Observed;
import eu.wohlben.qits.containers.client.ContainersWire.Policy;
import eu.wohlben.qits.containers.client.ContainersWire.PullPolicy;
import eu.wohlben.qits.containers.client.ContainersWire.Recreate;
import eu.wohlben.qits.containers.client.ContainersWire.Security;
import eu.wohlben.qits.containers.client.ContainersWire.SharedMount;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeEnvelope;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeMount;
import eu.wohlben.qits.workspaces.control.ContainerRuntime;
import eu.wohlben.qits.workspaces.control.ProxyOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceContainer;
import eu.wohlben.qits.workspaces.control.WorkspaceContainerFactory;
import eu.wohlben.qits.workspaces.error.InternalServerErrorException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link ContainerRuntime} backed by qits-containers. <b>This process holds no docker socket and
 * spawns no process at all.</b>
 *
 * <p>Every line of docker vocabulary that used to live in {@code DockerExecutor} — {@code run},
 * {@code start}, {@code stop}, {@code rm}, {@code ps}, {@code inspect}, {@code pull}, {@code volume
 * create}/{@code rm}/{@code ls}, {@code network inspect}/{@code create} — is one HTTP call to the
 * orchestrator, which owns the daemon. Three of those verbs did not survive the move and say so
 * where they are declared: {@code network create} is the bootstrap's job now (the orchestrator only
 * probes), {@code pull} is the orchestrator's (pull policy {@link PullPolicy#MISSING}), and {@code
 * exec} is not on the wire and cannot be — running a command inside a container someone else owns is
 * exactly the privilege this cutover gave up.
 *
 * <p><b>The client never throws and its four answers are the whole vocabulary.</b> A refusal and an
 * unreachable service mean opposite things — one is evidence about the request, the other about
 * nothing at all — so a caller that collapsed them would read "nothing was learned" as "the
 * container was refused" and start a second workspace. Do not add a fifth outcome by catching
 * something.
 *
 * <p><b>What each method is allowed to do about a failure is not uniform, and that is the seam's
 * own contract rather than this class's preference.</b> {@link #run} and {@link #start} throw,
 * because a workspace that did not come up must fail loudly and be recorded {@code FAILED}; {@link
 * #stop}, {@link #rm}, {@link #ensureWorkspaceVolume} and {@link #removeWorkspaceVolume} are
 * best-effort and never throw, because every one of them runs after something irreversible has
 * already happened; and the two reads answer the safe value when nothing could be learned.
 */
@ApplicationScoped
public class WorkspaceContainers implements ContainerRuntime {

  private static final Logger LOG = Logger.getLogger(WorkspaceContainers.class);

  /**
   * The workload every place this class addresses belongs to. One word, this consumer's own: the
   * registry's identity is {@code owner/workload/ref}, so this is what tells a workspace container
   * from anything else qits-workspaces might one day ask the orchestrator for.
   */
  static final String WORKLOAD = "workspace";

  /** The prefix every workspace container's name carries. Also what {@link #listWorkspaceContainers} reads back. */
  static final String NAME_PREFIX = "qits-ws-";

  /**
   * How long a launch waits between two attempts at the same place. Five seconds because what it
   * holds through is a token/JWKS window of tens of seconds — see {@link #holdThrough} — so a
   * shorter pause only spends the caller's thread on refusals nobody has fixed yet, and a longer one
   * spends the window itself.
   */
  private static final Duration RETRY_PAUSE = Duration.ofSeconds(5);

  /** How long a read or a teardown may take. Bounded, because several callers hold a monitor. */
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

  private static final Duration TEARDOWN_TIMEOUT = Duration.ofSeconds(30);

  /**
   * The one client, produced by {@code containers/ContainersClientProducer}. Every call it makes is
   * synchronous and bounded, and none of them throws.
   */
  @Inject ContainersClient containers;

  /**
   * Assembles what a workspace container is made of — the image, the shared credential and cache
   * volumes, the {@code qits.*} labels, the host alias, the host uid, the daemon's dial-home
   * environment and the resource caps. Unchanged by this cutover: it describes a container and never
   * rendered a request, so what moved is only who reads it.
   */
  @Inject WorkspaceContainerFactory containerFactory;

  /**
   * Who this process <b>is</b> to the orchestrator, and the second half of every place it addresses.
   *
   * <p>It must equal the {@code sub} of the machine token this service presents once the gate is on,
   * because the orchestrator's {@code OwnerGuard} compares them — so the shipped default reads
   * {@code quarkus.oidc-client.qits.client-id} (service-client-identity-plan.md, C4) and the coupling
   * lives in one place, the key's own comment in {@code application.properties}. It is also the
   * scope: two environments sharing one docker daemon are {@code dev-qits-workspaces} and {@code
   * prod-qits-workspaces}, and neither one's rows name the other's containers.
   */
  @ConfigProperty(name = "qits.workspace.containers.owner")
  String owner;

  /**
   * How long a launch holds through an orchestrator that cannot authorize it yet, or cannot be
   * reached at all. The measured window is the trailing edge of a qits-platform-idp cutover — see
   * {@link #holdThrough} and the key's own comment.
   */
  @ConfigProperty(name = "qits.workspace.containers.launch-patience")
  Duration launchPatience;

  /**
   * How long the EDITOR's container may sit idle before qits-containers' own {@code IdleSweep} stops
   * it. Blank — the shipped value — leaves every workspace on the EXPLICIT lifetime it has always
   * had, so this key is a switch that is off rather than a default. See {@link #lifetime}.
   */
  @ConfigProperty(name = "qits.editor.idle-stop-after")
  Optional<Duration> editorIdleStopAfter;

  // --- naming -----------------------------------------------------------------------------------

  @Override
  public String containerName(String workspaceId, String repoId) {
    // Mirrors WorkspaceContainerFactory's own derivation. The short repo prefix keeps the name
    // readable and well under the length cap while staying effectively unique per repo.
    return NAME_PREFIX + workspaceId + "-" + shortRepo(repoId);
  }

  /** The leading characters of a repository id that ride in a container name. */
  static String shortRepo(String repoId) {
    return repoId.length() > 8 ? repoId.substring(0, 8) : repoId;
  }

  /**
   * The {@code ref} a container name addresses, and the one place that translation happens.
   *
   * <p><b>The name would be the place, and here it cannot always be.</b> qits-ci's refs are its
   * container names verbatim, because everything in them is already lowercase alphanumerics and
   * dashes. A workspace container's name is not: {@code workspaceId} is a branch slug over {@code
   * [A-Za-z0-9_-]}, so a branch called {@code feature/Login_V2} yields {@code Login_V2} in the name,
   * and the orchestrator's ref charset is {@code [a-z0-9][a-z0-9-]*}. The real name still travels,
   * as {@code explicitName}, so {@code docker ps} reads exactly as it did.
   *
   * <p><b>The disambiguator is what keeps normalization from merging two places.</b> Lowercasing and
   * folding {@code _} to {@code -} maps {@code Login_V2} and {@code login-v2} onto one string, and
   * two workspaces that shared a ref would share a container. So a name that is <em>not already a
   * legal ref</em> carries a short hash of its whole self; a name that is one is left exactly alone,
   * which is every ordinary lowercase workspace and keeps the common case readable.
   *
   * <p>Deterministic either way: the same name always names the same place, which is what lets
   * {@link #rm} and {@link #stop} address what {@link #run} put there.
   */
  static String refOf(String containerName) {
    StringBuilder normalized = new StringBuilder(containerName.length());
    boolean changed = false;
    for (int i = 0; i < containerName.length(); i++) {
      char c = containerName.charAt(i);
      char lower = Character.toLowerCase(c);
      boolean legal = (lower >= 'a' && lower <= 'z') || (lower >= '0' && lower <= '9') || lower == '-';
      normalized.append(legal ? lower : '-');
      if (lower != c || !legal) {
        changed = true;
      }
    }
    if (!changed) {
      return normalized.toString();
    }
    return normalized + "-" + Integer.toHexString(containerName.hashCode());
  }

  private String ref(String containerName) {
    return refOf(containerName);
  }

  // --- the one write that starts something ------------------------------------------------------

  @Override
  public String run(String repoId, String workspaceId, Long rowId, String branch, String parent) {
    return run(repoId, workspaceId, rowId, branch, parent, null);
  }

  @Override
  public String run(
      String repoId,
      String workspaceId,
      Long rowId,
      String branch,
      String parent,
      Consumer<String> onLine) {
    String name = containerName(workspaceId, repoId);
    EnsureRequest request = ensureRequest(repoId, workspaceId, rowId, branch, parent);
    // The image the SPEC carries, not the factory's plain pin: a wrapper-main workspace runs the
    // editor image, and a provision log that named the other one would be the one line a reader
    // trusts to say which image a container is coming up on.
    say(onLine, "Asking qits-containers for " + name + " (" + request.spec().image() + ")");
    Envelope envelope = ensure(name, request, onLine);
    say(onLine, "The container is up.");
    LOG.debugf("Started workspace container %s (%s)", name, envelope.state().observed());
    return name;
  }

  /**
   * Ask the orchestrator to put a workspace container at its place, holding through the answers that
   * are about the moment rather than about the request.
   *
   * <p><b>One attempt per answer about the request, and a patient loop for the two answers that are
   * about nothing but the moment.</b> The classification is {@link #holdThrough}, copied from
   * qits-ci where the 2026-08-12 rebootstrap measured what the alternative costs: the deploy train
   * replaced qits-platform-idp and the next three launches died with {@code refused 401} while the
   * following ones passed. Retrying is safe for a reason a bare {@code docker run} never had —
   * {@code ensure} is a PUT per {@code (owner, workload, ref)}, so a second attempt addresses the
   * same place and a container the first attempt created but could not report is adopted rather
   * than duplicated.
   *
   * <p><b>A 2xx whose container is not there is a failed launch.</b> The wire contract is explicit
   * that an {@code ensure} whose container did not start is a true answer rather than a failed
   * request — the row exists, it says {@code MISSING}, and it carries what docker said — so the
   * status alone does not answer this method's question. It is not retried either: something
   * answered about this very container.
   */
  private Envelope ensure(String name, EnsureRequest request, Consumer<String> onLine) {
    Instant giveUpAt = Instant.now().plus(launchPatience);
    // Never pause past the window itself: a pause longer than the patience would make a short
    // patience mean one attempt while looking like a window, which is the shape a test cannot see.
    Duration pause = RETRY_PAUSE.compareTo(launchPatience) > 0 ? launchPatience : RETRY_PAUSE;
    int attempts = 0;
    while (true) {
      attempts++;
      ContainersAnswer<Envelope> answer = containers.ensure(owner, WORKLOAD, ref(name), request);
      if (answer.succeeded()) {
        Envelope envelope = answer.value();
        Observed observed =
            envelope == null || envelope.state() == null ? null : envelope.state().observed();
        if (observed == Observed.MISSING || observed == Observed.GONE) {
          String detail = envelope.detail() == null ? "" : envelope.detail();
          say(onLine, "The container did not start: " + detail);
          throw new InternalServerErrorException(
              "Failed to start container " + name + ": the container did not start: " + detail);
        }
        return envelope;
      }
      if (holdThrough(answer) && Instant.now().isBefore(giveUpAt) && sleep(pause)) {
        LOG.infof(
            "Attempt %d to start workspace container %s did not land (%s) — asking again, holding"
                + " through the window",
            attempts, name, answer.detail());
        say(onLine, "qits-containers did not answer yet (" + answer.detail() + ") — asking again");
        continue;
      }
      say(onLine, "qits-containers could not start the container: " + answer.detail());
      throw new InternalServerErrorException(
          "Failed to start container "
              + name
              + " after "
              + attempts
              + " attempt(s): "
              + answer.detail());
    }
  }

  /**
   * The two answers another attempt could change, and the one place that decision is made.
   *
   * <p><b>401 and 403 are in it, and that is qits-ci's 2026-08-12 lesson applied here.</b> They read
   * like statements about the request — the owner guard said no — and for a stable deployment they
   * are. Across an idp cutover they are a statement about the moment instead: the same call with the
   * same owner succeeds a minute later, because the token or the key that validates it has been
   * replaced. There is no way to tell the two apart from here, so the patient reading is the safe
   * one — every call this predicate governs is idempotent, so a retry that was never needed costs
   * one request.
   *
   * <p>Everything else is an answer about the request and is taken at its word: {@code
   * SPEC_CONFLICT}, {@code IMAGE_MISSING}, a 400 on a value, a 404 saying the place is already gone.
   */
  static boolean holdThrough(ContainersAnswer<?> answer) {
    if (answer.unreachable()) {
      return true;
    }
    return answer instanceof ContainersAnswer.Refused<?> refused
        && (refused.status() == 401 || refused.status() == 403);
  }

  /**
   * Everything one workspace container is started with, as the orchestrator's wire spells it.
   *
   * <p>It is asserted literally by {@code WorkspaceContainersTest}, because several fields below are
   * load-bearing enough that one lost in a refactor is a behaviour change nobody would see until a
   * workspace misbehaved.
   *
   * <p><b>The sandbox is deliberately NOT qits-ci's.</b> {@code capDropAll} and {@code
   * noNewPrivileges} are both false here, and that is a decision rather than an omission: a step
   * container runs a repository's script and must lose every privilege it can, while a workspace
   * container is a <em>development environment</em> a person works in — it has to be able to {@code
   * su}, {@code chown} its own checkout and install a toolchain, and cap-dropping it would break the
   * thing it exists to be. The caps that are kept are bounded by the resource limits beside them
   * (memory, swap, pids, cpus), which are what actually stop a dev server from taking the host down.
   *
   * <p><b>{@code hostDockerSocket} is the one field here that is not the same for every
   * workspace.</b> It is true exactly for a workspace whose row carries {@code admin} — the posture
   * asked for in the request that created it — and false for every other, which is what this service
   * sent unconditionally until admin workspaces existed. It is derived from nothing a repository, an
   * image or a branch name says: a container holding that socket is root-equivalent on the host, and
   * a privilege that can be derived is a privilege nobody granted.
   *
   * <p><b>The three shared volumes are {@link SharedMount}s and the workspace's own is a {@link
   * VolumeMount}, and the difference is ownership.</b> The shared ones — the agent credential store
   * and the Maven/pnpm caches — are the platform's: the orchestrator creates exactly those three at
   * boot from its own {@code qits.containers.shared-volumes} default, they are claimed by no row and
   * no delete may ever take them. The per-workspace {@code /workspace} volume is this workload's
   * own, so it is created with the container and could be removed with it — which this service
   * deliberately never asks for on a delete; see {@link #rm}.
   *
   * <p><b>{@code init} is true.</b> The container hosts a long-lived daemon that spawns processes of
   * its own — bootstrap steps, dev servers, agent sessions — and PID 1 inherits every orphan and
   * reaps none unless it was written to, so without tini a workspace collects zombies for as long as
   * it runs. This is the {@code --init} the run argv used to carry.
   *
   * <p><b>The pull policy is {@code MISSING}.</b> The pin is a version, so what is local under that
   * name IS the release and a launch is not the place to re-litigate it — the same rule the deleted
   * {@code ensureImage} followed with an inspect-then-pull, now enforced one hop further out where
   * the daemon actually is.
   */
  EnsureRequest ensureRequest(
      String repoId, String workspaceId, Long rowId, String branch, String parent) {
    WorkspaceContainer described =
        containerFactory.forWorkspace(repoId, workspaceId, rowId, branch, parent);

    Set<String> shared = sharedVolumeNames();
    List<VolumeMount> own = new ArrayList<>();
    List<SharedMount> platform = new ArrayList<>();
    for (WorkspaceContainer.Mount mount : described.volumes()) {
      if (shared.contains(mount.volumeName())) {
        platform.add(new SharedMount(mount.volumeName(), mount.containerPath()));
      } else {
        own.add(new VolumeMount(mount.volumeName(), mount.containerPath()));
      }
    }

    Spec spec =
        new Spec(
            described.image(),
            // The image's qits-workspace-daemon ENTRYPOINT is the whole process. There is
            // deliberately no `sleep infinity` fallback: a container that cannot run the daemon must
            // FAIL to start rather than linger as an unmanaged shadow holding qits' uid and mounts.
            null,
            null,
            described.env(),
            // The qits.* identity labels. They select nothing any more — the orchestrator's registry
            // is what answers "whose container is this" — and they stay because they are what a
            // person reading `docker ps` or `docker inspect` has to go on.
            described.labels(),
            described.network(),
            null,
            described.addHosts(),
            own,
            platform,
            // The host's docker socket, and ONLY for a workspace whose row says admin. Nothing
            // else can put it here: the factory reads the posture off the row, no config key
            // widens it, and every failure direction of that read is false. An ordinary workspace
            // renders this exactly as it always did.
            described.hostDockerSocket(),
            new Security(
                false,
                false,
                described.memory(),
                // The configured memory+swap total when the factory carries one (the shipped
                // default: 4g/8g, so 4G of swap). Without one, the same value as the memory cap,
                // so the cap stays hard and the container cannot spill into host swap.
                memorySwap(described),
                pids(described.pidsLimit()),
                described.cpus(),
                described.oomScoreAdj()),
            PullPolicy.MISSING,
            described.name(),
            described.user(),
            true);
    // EXPLICIT: a workspace lives until somebody says otherwise, only a delete ends it, and the
    // orchestrator keeps it restarted. It is also what makes a spec change recreatable rather than a
    // SPEC_CONFLICT, which is what Recreate.ifChanged below relies on.
    //
    // ifChanged rather than the safer never, and the image pin is why: the deployer moves
    // qits.workspace.image-version (from qits-configuration), and a workspace whose spec no longer
    // matches what is running must be
    // replaced rather than silently left on the old image with a 200 saying so. Every path that
    // reaches here has already established that nothing is running at this place.
    return new EnsureRequest(spec, lifetime(described), Recreate.ifChanged);
  }

  /**
   * How long this container is allowed to sit there.
   *
   * <p><b>EXPLICIT for every workspace, and IDLE_STOP for the editor's — when a deployment asks for
   * it.</b> The two are not the same statement about a container. A workspace is somewhere a person
   * works and a coding agent runs unattended, so nothing but a person may end it; the editor's
   * workspace is the one that is opened, read and left, and a browser tab closed at the end of a day
   * would otherwise hold a multi-gigabyte container for the week.
   *
   * <p><b>Stopping is not losing.</b> An idle-stopped place is <em>stopped</em>, not deleted: the
   * container keeps its id and its writable layer, {@code /workspace} is a volume of its own, and
   * the ensure ladder's second rung starts it back up where it stands. The daemon then finds a
   * checkout already there and clones nothing. So the reopen path needs no code at all, which is
   * what makes this switch cheap enough to have.
   *
   * <p><b>Unset is today's behaviour, and it ships unset.</b> {@code qits.editor.idle-stop-after}
   * blank means every workspace keeps the EXPLICIT lifetime it has always had — a kill switch that
   * is off, not a default that has to be argued with.
   *
   * <p>It rides the POLICY and not the spec, which is why turning it on does not replace anything:
   * {@code Recreate.ifChanged} compares the spec, and the policy is not part of it.
   */
  private Policy lifetime(WorkspaceContainer described) {
    if (!described.editor()) {
      return Policy.explicitLifetime();
    }
    return editorIdleStopAfter
        .filter(idle -> !idle.isZero() && !idle.isNegative())
        .map(idle -> Policy.idleStop(idle.toSeconds()))
        .orElseGet(Policy::explicitLifetime);
  }

  /** The platform's shared volumes, as this service names them. Blank means the mount is disabled. */
  private Set<String> sharedVolumeNames() {
    Set<String> names = new LinkedHashSet<>();
    for (String name :
        List.of(
            containerFactory.claudeVolume(),
            containerFactory.mavenVolume(),
            containerFactory.pnpmVolume())) {
      if (name != null && !name.isBlank()) {
        names.add(name);
      }
    }
    return names;
  }

  /**
   * The {@code --memory-swap} value a workspace container is started with. The factory's configured
   * total when there is one; the memory cap itself when there is not, because a spec whose swap
   * field is null would leave the value to the runtime — unlimited swap — and "the key is blank"
   * must mean "no swap", the shape this service always sent.
   */
  private static String memorySwap(WorkspaceContainer described) {
    String configured = described.memorySwap();
    return configured == null || configured.isBlank() ? described.memory() : configured;
  }

  /** The pids cap as the wire wants it. Blank, absent or unreadable all mean "no cap". */
  private static Long pids(String configured) {
    if (configured == null || configured.isBlank()) {
      return null;
    }
    try {
      return Long.valueOf(configured.trim());
    } catch (NumberFormatException e) {
      LOG.warnf("Ignoring qits.workspace.pids-limit='%s': it is not a number", configured);
      return null;
    }
  }

  // --- the rest of the lifecycle ----------------------------------------------------------------

  /**
   * Bring a stopped workspace back up — one {@code ensure}, at the place it already occupies.
   *
   * <p><b>This is why the method takes an identity rather than a container name.</b> The
   * orchestrator has no start route: a stopped place is started by asking for it <em>again</em>, and
   * asking means presenting the spec, which is derived from the workspace. So there is nothing to do
   * here beyond what {@link #run} already does — the registry reads the unchanged spec, sees a
   * container that is merely stopped, and starts that container where it stands rather than running
   * a second one. The container keeps its id and its writable layer across the call, so the checkout
   * survives whether or not {@code qits.workspace.persist-workspace} put it on a volume.
   *
   * <p>It stays a method of its own rather than a second name for {@code run} because the seam
   * distinguishes them and the callers mean different things: {@link #run} provisions a place that
   * is not there, this resumes one that is. The orchestrator collapses that distinction, which is
   * the whole point of asking it rather than telling it.
   */
  @Override
  public void start(String repoId, String workspaceId, Long rowId, String branch, String parent) {
    run(repoId, workspaceId, rowId, branch, parent);
  }

  @Override
  public void stop(String container) {
    ContainersAnswer<Envelope> answer =
        containers.stop(owner, WORKLOAD, ref(container), TEARDOWN_TIMEOUT);
    if (!answer.succeeded() && !gone(answer)) {
      LOG.debugf("Failed to stop container %s: %s", container, answer.detail());
    }
  }

  @Override
  public void rm(String container) {
    // withVolumes=false, deliberately and on every path. The per-workspace volume outlives the
    // container by design — a recreate reattaches the same checkout — and the one operation that
    // means to drop it says so by name: removeWorkspaceVolume.
    ContainersAnswer<DeleteOutcome> answer =
        containers.delete(owner, WORKLOAD, ref(container), false, false, TEARDOWN_TIMEOUT);
    if (!answer.succeeded() && !gone(answer)) {
      LOG.debugf("Failed to remove container %s: %s", container, answer.detail());
    }
  }

  @Override
  public void touch(String container) {
    ContainersAnswer<Void> answer =
        containers.touch(owner, WORKLOAD, ref(container), READ_TIMEOUT);
    if (!answer.succeeded() && !gone(answer)) {
      // Best-effort by contract. A missed keepalive costs at worst a sweep, and a swept container is
      // started back up in place by the next ensure with its /workspace volume intact — while
      // throwing here would fail whatever the caller was really doing.
      LOG.debugf("Failed to touch container %s: %s", container, answer.detail());
    }
  }

  @Override
  public boolean exists(String container) {
    Observed observed = observed(container);
    // A place with no row, and one whose container is not there, are both "nothing to inspect" —
    // which is what the docker `container inspect` this replaced answered non-zero for.
    return observed != null && observed != Observed.MISSING && observed != Observed.GONE;
  }

  @Override
  public boolean isRunning(String container) {
    return observed(container) == Observed.RUNNING;
  }

  /**
   * What the orchestrator's last look at this place found, or null when it has no row for it or
   * could not say.
   *
   * <p><b>Unreachable reads as absent, and that is the safe direction here rather than the honest
   * one.</b> Both readers above are guards in front of a provision: {@link #exists} false sends the
   * caller to {@link #run}, which is an idempotent PUT at the same place, so a read that failed
   * costs an ensure that adopts what is already there. The opposite default would report a workspace
   * as running while the orchestrator was down, and the proxy would then dial a container nobody
   * could confirm.
   */
  private Observed observed(String container) {
    ContainersAnswer<Envelope> answer =
        containers.status(owner, WORKLOAD, ref(container), READ_TIMEOUT);
    if (!answer.succeeded()) {
      if (!gone(answer)) {
        LOG.debugf("Could not read the state of %s: %s", container, answer.detail());
      }
      return null;
    }
    Envelope envelope = answer.value();
    return envelope == null || envelope.state() == null ? null : envelope.state().observed();
  }

  /** The refusal that means "there is no such place", which every teardown reads as success. */
  private static boolean gone(ContainersAnswer<?> answer) {
    return answer instanceof ContainersAnswer.Refused<?> refused && refused.status() == 404;
  }

  /**
   * Every workspace container of a repository, in <b>one</b> call.
   *
   * <p><b>The listing is matched on names, because the orchestrator's listing carries no labels.</b>
   * A container name embeds the workspace id and the leading characters of the repository id, and
   * the real name travels as {@code explicitName}, so the name a row answers with is the name this
   * service composed — which makes stripping the prefix and the repository suffix exact. What that
   * loses against the old {@code docker ps --filter label=qits.repository=<id>} is precision at the
   * edges: a repository id that is a dash-suffix of another's could match a sibling's container, and
   * what comes back is then a workspace id that no row of this repository carries. The one consumer
   * intersects this set with its own rows, so such an entry selects nothing.
   *
   * <p>One call and not one per workspace, deliberately: this backs the workspace list endpoint,
   * which a browser polls.
   */
  @Override
  public List<ContainerInfo> listWorkspaceContainers(String repoId) {
    ContainersAnswer<List<Envelope>> answer = containers.list(owner, WORKLOAD, READ_TIMEOUT);
    if (!answer.succeeded()) {
      // A read failure must not shrink the answer into a claim: an empty list here reads as "nothing
      // is running", which is a statement this call did not earn. It is still what the old `docker
      // ps` failure returned, and the caller treats the persisted column as the other half.
      LOG.warnf("Failed to list containers for repo %s: %s", repoId, answer.detail());
      return List.of();
    }
    String suffix = "-" + shortRepo(repoId);
    List<ContainerInfo> infos = new ArrayList<>();
    for (Envelope envelope : answer.value() == null ? List.<Envelope>of() : answer.value()) {
      String name = envelope.containerName();
      if (name == null || !name.startsWith(NAME_PREFIX) || !name.endsWith(suffix)) {
        continue;
      }
      String workspaceId =
          name.substring(NAME_PREFIX.length(), name.length() - suffix.length());
      if (workspaceId.isBlank()) {
        continue;
      }
      boolean running =
          envelope.state() != null && envelope.state().observed() == Observed.RUNNING;
      // branch and parent are null: they lived on labels the listing does not answer with, and
      // nothing reads them. See ContainerInfo.
      infos.add(new ContainerInfo(name, workspaceId, null, null, running));
    }
    return infos;
  }

  /**
   * On the shared network qits reaches a container by its DNS name and the real container port — no
   * host publish, no create-time port constraint, and no round trip to find out.
   *
   * <p><b>The {@code bridge-ip} mode is gone with the key that selected it.</b> It read the
   * container's IP off a {@code docker inspect} for plain-Linux hosts where the bridge is
   * host-routable; there is no inspect to make, and under the swarm overlay this platform runs on
   * there is no host-routable bridge address either. What is left is the mode every deployment
   * already used.
   */
  @Override
  public ProxyOrigin resolveTarget(String container, int containerPort) {
    return new ProxyOrigin(container, containerPort);
  }

  // --- Per-workspace /workspace volumes ---------------------------------------------------------

  @Override
  public String workspaceVolumeName(String workspaceId) {
    return containerFactory.workspaceVolumeName(workspaceId);
  }

  @Override
  public void ensureWorkspaceVolume(
      String repoId, String workspaceId, String branch, String parent) {
    String name = containerFactory.workspaceVolumeName(workspaceId);
    ContainersAnswer<VolumeEnvelope> answer =
        containers.ensureVolume(owner, name, READ_TIMEOUT);
    if (!answer.succeeded()) {
      LOG.warnf("Could not ensure workspace volume '%s': %s", name, answer.detail());
    }
    // The rich qits.* labels this used to set are the orchestrator's now: it labels a volume by the
    // place that claims it, which is the identity the reconcile it fed actually needed. There is
    // nothing left here to pass them through, and no reader on the other side.
  }

  @Override
  public void removeWorkspaceVolume(String workspaceId) {
    String name = containerFactory.workspaceVolumeName(workspaceId);
    // The standalone volume door rather than a delete's ?volumes=true, and the reason is the call
    // order every caller has: the container is removed first and the volume second, on paths where
    // the volume can also outlive or precede a container entirely (a branch abandoned before it was
    // ever provisioned). Asking for the volume on the container's delete would only work on the one
    // path where both exist at once.
    ContainersAnswer<VolumeEnvelope> answer = containers.deleteVolume(owner, name, READ_TIMEOUT);
    if (!answer.succeeded() && !gone(answer)) {
      LOG.debugf("Failed to remove workspace volume %s: %s", name, answer.detail());
    }
  }

  // --- what the orchestrator has no verb for ----------------------------------------------------

  @Override
  public ExecResult exec(String container, String workdir, Map<String, String> env, String... argv) {
    throw noSuchVerb("exec");
  }

  @Override
  public List<String> execArgv(
      String container, boolean tty, String workdir, Map<String, String> env) {
    throw noSuchVerb("execArgv");
  }

  @Override
  public void restart(String container) {
    throw noSuchVerb("restart");
  }

  @Override
  public List<VolumeInfo> listWorkspaceVolumes() {
    throw noSuchVerb("listWorkspaceVolumes");
  }

  /**
   * The one refusal these four share. None of them has a production caller — the interface keeps
   * them because the two {@code FakeContainerRuntime}s are their real implementors — so reaching one
   * here is a new caller rather than a state, and it must fail where it is written instead of
   * quietly answering something.
   */
  private static UnsupportedOperationException noSuchVerb(String verb) {
    return new UnsupportedOperationException(
        "ContainerRuntime." + verb + " has no production caller and qits-containers has no verb for"
            + " it — a workspace container is reached through its own daemon, never from the host");
  }

  // --- small shared helpers ---------------------------------------------------------------------

  /** Feed the provision segment's tap, if the caller attached one. Never fails the caller. */
  private static void say(Consumer<String> onLine, String line) {
    if (onLine == null) {
      return;
    }
    try {
      onLine.accept(line);
    } catch (RuntimeException ignored) {
      // the tap is observational only
    }
  }

  /** Wait, or report that this thread is being asked to stop — in which case the loop is over. */
  private static boolean sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
