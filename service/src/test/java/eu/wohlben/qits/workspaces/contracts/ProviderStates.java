package eu.wohlben.qits.workspaces.contracts;

import eu.wohlben.qits.agents.ClaudeCodeAgent;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.health.CheckResult;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.WorkspacePromptDraftService;
import eu.wohlben.qits.workspaces.daemonhost.StubDaemonTunnels;
import eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerHealth;
import eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerMessageCodec;
import eu.wohlben.qits.workspacesrunner.protocol.Delete;
import eu.wohlben.qits.workspacesrunner.protocol.Deleted;
import eu.wohlben.qits.workspacesrunner.protocol.HealthCheck;
import eu.wohlben.qits.workspacesrunner.protocol.HealthChecked;
import eu.wohlben.qits.workspacesrunner.protocol.Inventory;
import eu.wohlben.qits.workspacesrunner.protocol.LoginPresence;
import eu.wohlben.qits.workspacesrunner.protocol.LoginState;
import eu.wohlben.qits.workspacesrunner.protocol.ProbeLogin;
import eu.wohlben.qits.workspacesrunner.protocol.Stop;
import eu.wohlben.qits.workspacesrunner.protocol.Stopped;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import eu.wohlben.qits.workspaces.control.TechnicalProcess;
import eu.wohlben.qits.workspaces.control.TechnicalProcessRegistry;
import eu.wohlben.qits.workspaces.control.TestGit;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceAddressPlanes;
import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.workspaces.persistence.AgentWaitRepository;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.AgentBranchRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerAddresses;
import eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerAddressesFixture;
import eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerRegistry;
import eu.wohlben.qits.workspaces.wiring.IdpRunnerCommissioner;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * <b>The provider states qits-workspaces records and verifies</b> (epic qits-112): each one writes
 * workspace rows straight into the store and answers its params.
 *
 * <p><b>The ids are qits-projects' frozen ids, on purpose.</b> qits-projects records its "… in
 * detail" states over one seeded project, and freezes their ids to {@code
 * 00000000-0000-4000-8000-00000000000N}. The rows here are bound to those same ids and qualified
 * ids, so a consumer can join a qits-projects recording with one of these:
 *
 * <ul>
 *   <li>project {@code …000f} (slug {@code contract-00000001})
 *   <li>epic {@code …0006}, {@code contract-00000001-2}
 *   <li>feature {@code …0007}, {@code contract-00000001-6}
 *   <li>task {@code …0011}, {@code contract-00000001-5}
 *   <li>bug ticket {@code …0001}, {@code contract-00000001-10}
 *   <li>improvement ticket {@code …000b}, {@code contract-00000001-11} — no workspace
 * </ul>
 *
 * <p>The wrapper repository the workspaces stand in has no frozen id over there; it is {@code
 * …0013}, the next number not used by those states.
 *
 * <p>The states the round-2 consumers asked for (qits-1149) add three repositories with a real git
 * origin — {@code …0014} (a dispatch), {@code …0015} (a released branch) and {@code …0016} (a branch
 * sweep), each built fresh once per JVM — and one workspace runner, {@code …0020}, named {@code
 * localhost} as the cold bootstrap names its own.
 *
 * <p>Other tests share this database, so every state first removes the rows bound to its ids, and
 * {@link #cleanUp} removes them again.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_PROJECT_WITH_WORKSPACES_BOUND_TO_WORK_ITEMS =
      "a project with workspaces bound to work items";
  public static final String A_WORK_ITEM_WITH_NO_WORKSPACES = "a work item with no workspaces";
  public static final String NO_WORK_ITEM_HAS_AN_OPEN_WORKSPACE =
      "no work item has an open workspace";

  // The agent states (qits-1152), named as qits-projects' consumer pact names them.
  public static final String A_TICKET_TO_DISPATCH = "a wrapper repository and a ticket to dispatch";
  public static final String AGENTS_BOUND_TO_WORK_ITEMS = "a project with agents bound to work items";
  public static final String AN_AGENT_ON_A_TICKET = "an agent working on a ticket";
  public static final String AN_AGENT_ON_A_RELEASED_BRANCH = "an agent owning a released branch";

  // The declared-wait state (qits-1153), for the CLI's `qits wait`.
  public static final String AN_AGENT_WITH_AN_OPEN_WAIT = "an agent with an open wait";
  // The presets' own bodies (qits-1149): `qits wait release-request` and `qits wait ci-run`.
  public static final String AN_AGENT_WAITING_ON_A_RELEASE_REQUEST =
      "an agent declaring a wait on a release request";
  public static final String AN_AGENT_WAITING_ON_A_CI_RUN = "an agent declaring a wait on a CI run";
  public static final String REPOSITORIES_WITH_MERGED_BRANCHES =
      "repositories with merged branches";
  public static final String A_CONFIGURED_WORKSPACE_IMAGE = "a configured workspace image";
  public static final String NO_RUNNERS = "no runners";
  public static final String AN_UNREGISTERED_RUNNER = "an unregistered runner";
  // The runner toolkit's register door (qits-1163), as qits-runner-toolkit-contracts names it.
  public static final String A_REGISTERED_RUNNER = "a registered runner";
  public static final String A_CONNECTED_RUNNER = "a connected runner";
  public static final String A_QUARANTINED_RUNNER = "a quarantined runner";

  // The landing app's workspace pages (epic qits-1230): a project's slots and their agents, one
  // slot, and one item's agent while its slot's container comes up.
  public static final String A_PROJECT_WITH_WORKSPACE_SLOTS =
      "a project with workspace slots hosting agents";
  public static final String A_WORKSPACE_SLOT_HOSTING_AGENTS = "a workspace slot hosting agents";
  public static final String AN_AGENT_WHOSE_WORKSPACE_IS_STARTING =
      "an agent whose workspace is starting";

  // The rest of the landing app's workspace UI (epic qits-1230): the agent page's chat and its
  // actions (qits-1241, qits-1243), a resolved slot's record, the slot's container buttons, and the
  // runners page (qits-1244).
  public static final String AN_AGENT_WITH_A_SAVED_PROMPT_DRAFT =
      "an agent with a saved prompt draft";
  public static final String AN_AGENT_WITH_NO_PROMPT_DRAFT = "an agent with no prompt draft";
  public static final String NO_AGENT_WITH_THAT_ID = "no agent with that id";
  public static final String AN_AGENT_WAITING_FOR_A_SLOT = "an agent waiting for a workspace slot";
  public static final String AN_AGENT_WHOSE_DAEMON_IS_NOT_CONNECTED =
      "an agent whose workspace daemon is not connected";
  public static final String AN_AGENT_WITH_LEFTOVER_WORK = "an agent with leftover work";
  public static final String A_RESOLVED_SLOT_WITH_AN_ARCHIVED_SESSION =
      "a resolved workspace slot with an archived agent session";
  public static final String A_RUNNER_IN_SERVICE = "a runner in service";
  public static final String A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE =
      "a workspace slot running on a runner in service";
  public static final String A_STOPPED_SLOT_ON_A_RUNNER_IN_SERVICE =
      "a stopped workspace slot on a runner in service";
  public static final String A_SLOT_ON_A_DISCONNECTED_RUNNER =
      "a workspace slot on a disconnected runner";

  static final String PROJECT_ID = "00000000-0000-4000-8000-00000000000f";
  static final String BUG_TICKET_ID = "00000000-0000-4000-8000-000000000001";
  static final String EPIC_ID = "00000000-0000-4000-8000-000000000006";
  static final String FEATURE_ID = "00000000-0000-4000-8000-000000000007";
  static final String IMPROVEMENT_TICKET_ID = "00000000-0000-4000-8000-00000000000b";
  static final String TASK_ID = "00000000-0000-4000-8000-000000000011";
  static final String WRAPPER_REPOSITORY_ID = "00000000-0000-4000-8000-000000000013";
  static final String DISPATCH_REPOSITORY_ID = "00000000-0000-4000-8000-000000000014";
  static final String RELEASED_REPOSITORY_ID = "00000000-0000-4000-8000-000000000015";
  static final String SWEPT_REPOSITORY_ID = "00000000-0000-4000-8000-000000000016";
  static final String RUNNER_ID = "00000000-0000-4000-8000-000000000020";
  static final String RUNNER_NAME = "localhost";

  /** The registration token the fake qits-idp hands out, and its id and subject. */
  static final String REGISTRATION_TOKEN = "qits_tok_contract-registration";

  static final String REGISTRATION_TOKEN_ID = "00000000-0000-4000-8000-000000000021";
  static final String REGISTRATION_SUBJECT = "contract-runner-registration";

  /**
   * The registration token as a runner presents it, the register door's {@code authorization}
   * param: {@link ContractBearers} turns it into the edge's JWT for {@link #REGISTRATION_SUBJECT}.
   */
  static final String REGISTRATION_BEARER = "Bearer " + REGISTRATION_TOKEN;

  /** The register door's role, as the edge's JWT for a registration token carries it. */
  static final String REGISTRATION_ROLE = "qits:workspaces-runner-registration";

  /** The client the fake qits-idp commissions for a registering runner. */
  static final String RUNNER_CLIENT_ID = "contract-runner-client";

  static final String RUNNER_CLIENT_SECRET = "contract-runner-secret";

  /** The bug ticket's branch, and the branch every ticket-bound state here stands on. */
  static final String TICKET_BRANCH = "ticket/invoice-totals-are-off-by-one-cent";

  static final String RELEASED_VERSION = "2026.101.120000";
  static final String RELEASED_SHA = "0123456789abcdef0123456789abcdef01234567";
  static final String MERGED_BRANCH = "merged-work";

  /** The repositories with a git origin, each built fresh once per JVM. */
  private static final List<String> GIT_REPOSITORIES =
      List.of(DISPATCH_REPOSITORY_ID, RELEASED_REPOSITORY_ID, SWEPT_REPOSITORY_ID);

  private static final AtomicBoolean ORIGINS_BUILT = new AtomicBoolean();

  private static final Set<String> WORK_IDS =
      Set.of(BUG_TICKET_ID, EPIC_ID, FEATURE_ID, IMPROVEMENT_TICKET_ID, TASK_ID);

  /** A hand-made workspace's label: bound to no work item, so no open list shows it. */
  private static final String HAND_MADE_LABEL = "contract-hand-made";

  /** The label of the slot workspace the agent states seed. */
  private static final String SLOT_LABEL = "ws-contract";

  /**
   * What a state answers.
   *
   * @param params the values a path template and a pact take, sorted by name
   * @param rowIds the generated row ids of the workspaces the state wrote, in the order it wrote
   *     them — frozen to 1, 2, 3 … in that order
   */
  public record Setup(Map<String, String> params, List<Long> rowIds) {}

  @Inject WorkspaceRepository workspaces;
  @Inject AgentWorktreeRepository agentRows;
  @Inject AgentBranchRepository agentBranches;
  @Inject AgentWaitRepository agentWaits;
  @Inject WorkspaceService workspaceService;
  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceRunners runners;
  @Inject WorkspaceRunnerRegistry runnerRegistry;
  @Inject TechnicalProcessRegistry technicalProcesses;
  @Inject WorkspacePromptDraftService promptDrafts;
  @Inject WorkspaceRunnerHealth runnerHealth;
  @Inject WorkspaceRunnerMessageCodec runnerCodec;
  @Inject Vertx vertx;
  @Inject ContractBearers bearers;

  @ConfigProperty(name = "qits.workspace.claude-archive-root")
  String archiveRoot;

  @ConfigProperty(name = "qits.test.origins-dir")
  String originsDir;

  @ConfigProperty(name = "qits.workspaces.data-dir")
  String dataDir;

  /** The connection {@link #A_CONNECTED_RUNNER} admitted, closed again by {@link #cleanUp}. */
  private volatile WorkspaceRunnerRegistry.Session runnerSession;

  /** The start {@link #AN_AGENT_WHOSE_WORKSPACE_IS_STARTING} left open, ended by {@link #cleanUp}. */
  private volatile TechnicalProcess startingProcess;

  public Set<String> names() {
    return Set.of(
        A_PROJECT_WITH_WORKSPACES_BOUND_TO_WORK_ITEMS,
        A_WORK_ITEM_WITH_NO_WORKSPACES,
        NO_WORK_ITEM_HAS_AN_OPEN_WORKSPACE,
        A_TICKET_TO_DISPATCH,
        AGENTS_BOUND_TO_WORK_ITEMS,
        AN_AGENT_ON_A_TICKET,
        AN_AGENT_ON_A_RELEASED_BRANCH,
        AN_AGENT_WITH_AN_OPEN_WAIT,
        AN_AGENT_WAITING_ON_A_RELEASE_REQUEST,
        AN_AGENT_WAITING_ON_A_CI_RUN,
        REPOSITORIES_WITH_MERGED_BRANCHES,
        A_CONFIGURED_WORKSPACE_IMAGE,
        NO_RUNNERS,
        AN_UNREGISTERED_RUNNER,
        A_REGISTERED_RUNNER,
        A_CONNECTED_RUNNER,
        A_QUARANTINED_RUNNER,
        A_PROJECT_WITH_WORKSPACE_SLOTS,
        A_WORKSPACE_SLOT_HOSTING_AGENTS,
        AN_AGENT_WHOSE_WORKSPACE_IS_STARTING,
        AN_AGENT_WITH_A_SAVED_PROMPT_DRAFT,
        AN_AGENT_WITH_NO_PROMPT_DRAFT,
        NO_AGENT_WITH_THAT_ID,
        AN_AGENT_WAITING_FOR_A_SLOT,
        AN_AGENT_WHOSE_DAEMON_IS_NOT_CONNECTED,
        AN_AGENT_WITH_LEFTOVER_WORK,
        A_RESOLVED_SLOT_WITH_AN_ARCHIVED_SESSION,
        A_RUNNER_IN_SERVICE,
        A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE,
        A_STOPPED_SLOT_ON_A_RUNNER_IN_SERVICE,
        A_SLOT_ON_A_DISCONNECTED_RUNNER);
  }

  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  public Setup setUp(String state) {
    cleanUp();
    return switch (state) {
      case A_PROJECT_WITH_WORKSPACES_BOUND_TO_WORK_ITEMS -> boundToWorkItems();
      case A_WORK_ITEM_WITH_NO_WORKSPACES -> new Setup(params(), List.of());
      case NO_WORK_ITEM_HAS_AN_OPEN_WORKSPACE -> noOpenWorkspace();
      case A_TICKET_TO_DISPATCH -> ticketToDispatch();
      case AGENTS_BOUND_TO_WORK_ITEMS -> agentsBoundToWorkItems();
      case AN_AGENT_ON_A_TICKET -> agentOnATicket();
      case AN_AGENT_ON_A_RELEASED_BRANCH -> agentOwningAReleasedBranch();
      case AN_AGENT_WITH_AN_OPEN_WAIT -> agentWithAnOpenWait();
      case AN_AGENT_WAITING_ON_A_RELEASE_REQUEST -> agentDeclaringAWait("releaseRequestId", RELEASE_REQUEST_ID);
      case AN_AGENT_WAITING_ON_A_CI_RUN -> agentDeclaringAWait("runId", CI_RUN_ID);
      case REPOSITORIES_WITH_MERGED_BRANCHES -> mergedBranches();
      case A_CONFIGURED_WORKSPACE_IMAGE -> new Setup(new TreeMap<>(), List.of());
      case NO_RUNNERS -> noRunners();
      case AN_UNREGISTERED_RUNNER -> unregisteredRunner();
      case A_REGISTERED_RUNNER -> registeredRunner();
      case A_CONNECTED_RUNNER -> connectedRunner();
      case A_QUARANTINED_RUNNER -> quarantinedRunner();
      case A_PROJECT_WITH_WORKSPACE_SLOTS -> slotsHostingAgents(false);
      case A_WORKSPACE_SLOT_HOSTING_AGENTS -> slotsHostingAgents(true);
      case AN_AGENT_WHOSE_WORKSPACE_IS_STARTING -> agentWhoseWorkspaceIsStarting();
      case AN_AGENT_WITH_A_SAVED_PROMPT_DRAFT -> agentInARunningSlot(true, false);
      case AN_AGENT_WITH_NO_PROMPT_DRAFT, AN_AGENT_WHOSE_DAEMON_IS_NOT_CONNECTED ->
          agentInARunningSlot(false, false);
      case AN_AGENT_WITH_LEFTOVER_WORK -> agentInARunningSlot(false, true);
      case NO_AGENT_WITH_THAT_ID -> noSuchAgent();
      case AN_AGENT_WAITING_FOR_A_SLOT -> agentWaitingForASlot();
      case A_RESOLVED_SLOT_WITH_AN_ARCHIVED_SESSION -> resolvedSlotWithAnArchivedSession();
      case A_RUNNER_IN_SERVICE -> runnerInService();
      case A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE ->
          slotOnARunnerInService(WorkspaceRuntimeStatus.RUNNING);
      case A_STOPPED_SLOT_ON_A_RUNNER_IN_SERVICE ->
          slotOnARunnerInService(WorkspaceRuntimeStatus.STOPPED);
      case A_SLOT_ON_A_DISCONNECTED_RUNNER -> slotOnADisconnectedRunner();
      default -> throw new IllegalArgumentException("No provider state named '" + state + "'");
    };
  }

  /** Removes every row a state writes, and tears down every workspace a git-backed state made. */
  public void cleanUp() {
    bearers.reset();
    endStartingProcess();
    endLeftoverProcesses();
    tearDownGitWorkspaces();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // Agents first: they name their workspace (and cascade their pushed branches).
              agentRows.delete("workId in ?1", List.copyOf(WORK_IDS));
              agentRows.delete("repositoryId = ?1", WRAPPER_REPOSITORY_ID);
              // Every slot on the wrapper, not only the labels the states write: a dispatch an
              // interaction makes (createAgent) opens a slot of its own (ws-2, ...) that the
              // scheduler then fails to place, and the next state's list would show it.
              workspaces.delete("repositoryId = ?1", WRAPPER_REPOSITORY_ID);
              workspaces.delete("workId in ?1", List.copyOf(WORK_IDS));
              workspaces.delete("repositoryId in ?1", GIT_REPOSITORIES);
            });
    // After the rows: a runner that still holds an ACTIVE workspace refuses its delete.
    removeRunners();
  }

  /**
   * Every ACTIVE workspace on a git-backed repository is discarded the way a person would, so its
   * worktree and branch go too and the next state can make the same one again.
   */
  private void tearDownGitWorkspaces() {
    List<Long> active =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaces
                        .find(
                            "repositoryId in ?1 and status = ?2",
                            GIT_REPOSITORIES,
                            WorkspaceStatus.ACTIVE)
                        .list()
                        .stream()
                        .map(w -> w.id)
                        .toList());
    for (Long id : active) {
      try {
        // Stopped first, which drops a parked launch, as AgentDispatchControllerTest does.
        workspaceService.stopContainer(id);
      } catch (RuntimeException e) {
        // Nothing to stop.
      }
      try {
        workspaceService.discardWorkspace(id, null, true);
      } catch (RuntimeException e) {
        // The row is deleted below either way; a teardown that failed leaves only files under target/.
      }
    }
  }

  /** The runner this class writes, and any runner a recorded create named {@code localhost}. */
  private void removeRunners() {
    WorkspaceRunnerRegistry.Session session = runnerSession;
    runnerSession = null;
    if (session != null) {
      runnerRegistry.onClose(session);
    }
    for (WorkspaceRunner runner : runners.list()) {
      if (runner.id.toString().equals(RUNNER_ID) || RUNNER_NAME.equals(runner.name)) {
        runners.delete(runner.id);
        runnerRegistry.deleted(runner.id);
      }
    }
  }

  private static Map<String, String> params() {
    Map<String, String> params = new TreeMap<>();
    params.put("bugTicketId", BUG_TICKET_ID);
    params.put("bugTicketQualifiedId", "contract-00000001-10");
    params.put("epicId", EPIC_ID);
    params.put("featureId", FEATURE_ID);
    params.put("improvementTicketId", IMPROVEMENT_TICKET_ID);
    params.put("improvementTicketQualifiedId", "contract-00000001-11");
    params.put("projectId", PROJECT_ID);
    params.put("repositoryId", WRAPPER_REPOSITORY_ID);
    params.put("taskId", TASK_ID);
    return params;
  }

  /**
   * No ACTIVE workspace bound to a work item, so the open list is empty. Other tests leave such
   * rows in the shared store, so this state removes every one of them, not only its own.
   */
  private Setup noOpenWorkspace() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaces.delete(
                    "workId is not null and status = ?1", WorkspaceStatus.ACTIVE));
    return new Setup(params(), List.of());
  }

  /**
   * One ACTIVE workspace each for the epic, the feature and the task. The bug ticket has a history:
   * an abandoned workspace, then an integrated one, then the ACTIVE one. The improvement ticket has
   * none. One hand-made ACTIVE workspace is bound to nothing.
   */
  private Setup boundToWorkItems() {
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              String bugBranch = "ticket/invoice-totals-are-off-by-one-cent";
              ids.add(
                  write(
                      BUG_TICKET_ID,
                      "contract-00000001-10",
                      bugBranch,
                      WorkspaceStatus.ABANDONED,
                      WorkspaceRuntimeStatus.STOPPED));
              ids.add(
                  write(
                      BUG_TICKET_ID,
                      "contract-00000001-10",
                      bugBranch,
                      WorkspaceStatus.INTEGRATED,
                      WorkspaceRuntimeStatus.STOPPED));
              ids.add(
                  write(
                      EPIC_ID,
                      "contract-00000001-2",
                      "epic/export-invoices-for-the-accountants",
                      WorkspaceStatus.ACTIVE,
                      WorkspaceRuntimeStatus.RUNNING));
              ids.add(
                  write(
                      FEATURE_ID,
                      "contract-00000001-6",
                      "feature/pdf-export",
                      WorkspaceStatus.ACTIVE,
                      WorkspaceRuntimeStatus.STOPPED));
              ids.add(
                  write(
                      TASK_ID,
                      "contract-00000001-5",
                      "task/download-button-on-the-invoice-list",
                      WorkspaceStatus.ACTIVE,
                      WorkspaceRuntimeStatus.RUNNING));
              ids.add(
                  write(
                      BUG_TICKET_ID,
                      "contract-00000001-10",
                      bugBranch,
                      WorkspaceStatus.ACTIVE,
                      WorkspaceRuntimeStatus.RUNNING));
              ids.add(
                  write(
                      null,
                      null,
                      "spike/try-a-new-pdf-library",
                      WorkspaceStatus.ACTIVE,
                      WorkspaceRuntimeStatus.STOPPED));
            });
    return new Setup(params(), List.copyOf(ids));
  }

  /**
   * A repository whose origin holds {@code master}, a diverged {@code feature} and {@link
   * #MERGED_BRANCH}, whose tip is {@code master}'s: a sweep removes that one.
   */
  private Setup mergedBranches() {
    buildOrigins();
    try {
      TestGit.exec(origin(SWEPT_REPOSITORY_ID), "git", "branch", "-f", MERGED_BRANCH, "master");
    } catch (Exception e) {
      throw new IllegalStateException("Could not seed the merged branch", e);
    }
    Map<String, String> params = new TreeMap<>();
    params.put("mergedBranch", MERGED_BRANCH);
    params.put("repositoryId", SWEPT_REPOSITORY_ID);
    params.put("repositoryName", "contract-service");
    return new Setup(params, List.of());
  }

  // --- agents (qits-1152) -------------------------------------------------------------------------

  static final String AGENT_ACTIVE_ID = "00000000-0000-4000-8000-0000000000a1";

  static final String OPEN_WAIT_ID = "00000000-0000-4000-8000-0000000000b1";

  static final String RELEASE_REQUEST_ID = "00000000-0000-4000-8000-0000000000c1";

  /** The CI run {@code qits wait ci-run} waits on. */
  static final String CI_RUN_ID = "00000000-0000-4000-8000-0000000000c2";

  /** The caller the recording sends ({@code X-Qits-User}), and so the agents' token subject. */
  static final String CONTRACT_CALLER = "contract-caller";
  static final String AGENT_OBSOLETE_ID = "00000000-0000-4000-8000-0000000000a2";
  static final String AGENT_REMOVED_ID = "00000000-0000-4000-8000-0000000000a3";
  static final String WRAPPER_BRANCH = "ticket/contract-00000001-10";
  static final String PUSHED_BRANCH = WRAPPER_BRANCH + "-totals";

  /** A wrapper repository and a ticket nobody dispatched yet; no runner, so a dispatch queues. */
  private Setup ticketToDispatch() {
    // An agent's credential is minted only where it can be spent: through an edge plane.
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.planesWithDomain(WorkspaceRunnerAddressesFixture.DOMAIN),
        WorkspaceAddressPlanes.class);
    buildOrigins();
    Map<String, String> params = new TreeMap<>();
    params.put("repositoryId", DISPATCH_REPOSITORY_ID);
    params.put("ticketId", BUG_TICKET_ID);
    params.put("ticketQualifiedId", "contract-00000001-10");
    params.put("wrapperBranch", WRAPPER_BRANCH);
    return new Setup(params, List.of());
  }

  /** The bug ticket's agents in every state: ACTIVE in a slot (with a pushed branch), OBSOLETE, REMOVED. */
  private Setup agentsBoundToWorkItems() {
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.RUNNING);
              ids.add(slot);
              agent(AGENT_REMOVED_ID, slot, AgentState.REMOVED, AgentRunState.YIELDED);
              agent(AGENT_OBSOLETE_ID, slot, AgentState.OBSOLETE, AgentRunState.YIELDED);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.RUNNING);
              agentBranches.upsert(
                  UUID.fromString(AGENT_ACTIVE_ID),
                  RELEASED_REPOSITORY_ID,
                  PUSHED_BRANCH,
                  RELEASED_SHA,
                  Instant.now());
            });
    Map<String, String> params = new TreeMap<>();
    params.put("bugTicketId", BUG_TICKET_ID);
    return new Setup(params, List.copyOf(ids));
  }

  /** One ACTIVE agent on the bug ticket, holding a slot that waits for a runner. */
  private Setup agentOnATicket() {
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.QUEUED);
              ids.add(slot);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.RUNNING);
            });
    Map<String, String> params = new TreeMap<>();
    params.put("ticketId", BUG_TICKET_ID);
    return new Setup(params, List.copyOf(ids));
  }

  /**
   * An ACTIVE agent waiting on a release request (qits-1153): one OPEN wait, two hours to run. Its
   * token subject is the recording's caller, so the wait doors are recorded as the agent itself.
   */
  private Setup agentWithAnOpenWait() {
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.QUEUED);
              ids.add(slot);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.RUNNING);
              openWait();
            });
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_ACTIVE_ID);
    params.put("releaseRequestId", RELEASE_REQUEST_ID);
    params.put("waitId", OPEN_WAIT_ID);
    return new Setup(params, List.copyOf(ids));
  }

  /**
   * An ACTIVE agent with no wait yet, about to declare one on what {@code param} names — a release
   * request ({@code releaseRequestId}) or a CI run ({@code runId}): the presets' create, recorded as
   * the agent itself.
   */
  private Setup agentDeclaringAWait(String param, String value) {
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.QUEUED);
              ids.add(slot);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.RUNNING);
            });
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_ACTIVE_ID);
    params.put(param, value);
    return new Setup(params, List.copyOf(ids));
  }

  /** The bug ticket's agent waits on a release request: one OPEN wait, two hours to run. */
  private void openWait() {
    AgentWait wait = new AgentWait();
    wait.id = UUID.fromString(OPEN_WAIT_ID);
    wait.agentId = UUID.fromString(AGENT_ACTIVE_ID);
    wait.label = "release-request " + RELEASE_REQUEST_ID;
    wait.selection =
        EventSelection.of(
                "ReleaseRequestStateChanged",
                EventSelection.Condition.exact("releaseRequestId", RELEASE_REQUEST_ID),
                EventSelection.Condition.exact("state", "FINALIZED"))
            .toJson();
    wait.eventName = "ReleaseRequestStateChanged";
    wait.createdAt = Instant.now();
    wait.expiresAt = wait.createdAt.plusSeconds(7200);
    agentWaits.persist(wait);
  }

  // --- the landing app's workspace pages (epic qits-1230) -----------------------------------------

  static final String AGENT_EPIC_ID = "00000000-0000-4000-8000-0000000000a4";
  static final String AGENT_QUEUED_ID = "00000000-0000-4000-8000-0000000000a5";

  /**
   * The wrapper's one slot, RUNNING on the connected runner {@code localhost}, hosting two ACTIVE
   * agents: the bug ticket's, which holds the slot and waits on a release request (a declared wait,
   * so nobody is waiting on a person), and the epic's, which yielded it. The improvement ticket's
   * agent is QUEUED: no slot was free, so it names none.
   *
   * <p>{@code withSlot} adds the slot's row id as {@code workspaceId}, for the reads addressed by
   * it. The project's reads leave it out: a listing filtered to the state's params would keep
   * every entry whose text holds that small number.
   */
  private Setup slotsHostingAgents(boolean withSlot) {
    connectedRunner();
    repositories.register(WRAPPER_REPOSITORY_ID);
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.RUNNING, UUID.fromString(RUNNER_ID));
              ids.add(slot);
              agent(AGENT_EPIC_ID, slot, AgentState.ACTIVE, AgentRunState.YIELDED, EPIC);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.RUNNING);
              openWait();
              agent(
                  AGENT_QUEUED_ID,
                  null,
                  AgentState.ACTIVE,
                  AgentRunState.QUEUED,
                  IMPROVEMENT_TICKET);
            });
    Map<String, String> params = params();
    params.put("agentId", AGENT_ACTIVE_ID);
    params.put("runnerId", RUNNER_ID);
    if (withSlot) {
      params.put("workspaceId", String.valueOf(ids.get(0)));
    }
    return new Setup(params, List.copyOf(ids));
  }

  /**
   * The bug ticket's agent, waiting on a release request, in the wrapper's slot while that slot's
   * container comes up on {@code localhost}: the slot is PROVISIONING and its start is a live
   * technical process, past its {@code queued} segment and in its {@code container} one.
   */
  private Setup agentWhoseWorkspaceIsStarting() {
    connectedRunner();
    repositories.register(WRAPPER_REPOSITORY_ID);
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.PROVISIONING, UUID.fromString(RUNNER_ID));
              ids.add(slot);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.RUNNING);
              openWait();
            });
    TechnicalProcess start =
        technicalProcesses.begin(WRAPPER_REPOSITORY_ID, SLOT_LABEL, ids.get(0));
    start.openSegment("queued");
    start.appendLine("queued", "waiting for a slot on " + RUNNER_NAME);
    start.settleSegment("queued", true);
    start.openSegment("container");
    start.appendLine("container", "pulling the workspace image");
    startingProcess = start;
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_ACTIVE_ID);
    params.put("bugTicketId", BUG_TICKET_ID);
    params.put("bugTicketQualifiedId", "contract-00000001-10");
    params.put("repositoryId", WRAPPER_REPOSITORY_ID);
    params.put("runnerId", RUNNER_ID);
    params.put("workspaceId", String.valueOf(ids.get(0)));
    return new Setup(params, List.copyOf(ids));
  }

  /**
   * Ends whatever process an interaction itself began on a slot this class makes — the start an
   * {@code ensureWorkspaceContainer} or {@code recreateWorkspaceContainer} kicks off, still live when
   * the verification moves on. The registry is keyed by repository and label, which every state
   * reuses, so a leftover would otherwise be the next state's {@code /active-process}.
   */
  private void endLeftoverProcesses() {
    for (String label : List.of(SLOT_LABEL, HAND_MADE_LABEL)) {
      technicalProcesses
          .activeFor(WRAPPER_REPOSITORY_ID, label)
          .ifPresent(id -> technicalProcesses.end(id, "the contract state was cleaned up"));
    }
  }

  /** Ends the start {@link #agentWhoseWorkspaceIsStarting} left open, so no slot shows it. */
  private void endStartingProcess() {
    TechnicalProcess start = startingProcess;
    startingProcess = null;
    if (start != null) {
      technicalProcesses.end(start.id(), "the contract state was cleaned up");
    }
  }

  /**
   * An ACTIVE agent whose wrapper branch a release just consumed (qits-1227: only the wrapper
   * branch's release makes an agent OBSOLETE, never a branch it pushed).
   */
  private Setup agentOwningAReleasedBranch() {
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.QUEUED);
              ids.add(slot);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.RUNNING);
            });
    Map<String, String> params = new TreeMap<>();
    params.put("branch", WRAPPER_BRANCH);
    params.put("repositoryId", WRAPPER_REPOSITORY_ID);
    params.put("sha", RELEASED_SHA);
    params.put("version", RELEASED_VERSION);
    return new Setup(params, List.copyOf(ids));
  }

  // --- the rest of the landing app's workspace UI (epic qits-1230) --------------------------------

  /** An agent id nothing was ever written for. */
  static final String AGENT_UNKNOWN_ID = "00000000-0000-4000-8000-0000000000a9";

  /** The archived session {@link #A_RESOLVED_SLOT_WITH_AN_ARCHIVED_SESSION} lays out. */
  static final String ARCHIVED_SESSION_ID = "00000000-0000-4000-8000-0000000000d1";

  /** The draft {@link #AN_AGENT_WITH_A_SAVED_PROMPT_DRAFT} holds: the client's blob, and its text. */
  static final String DRAFT_CONTENT = "{\"text\":\"Check the rounding in the invoice totals.\",\"picked\":[]}";

  static final String DRAFT_PROMPT = "Check the rounding in the invoice totals.";

  /** The agent home volume the runner in service reports. */
  static final String DOT_CLAUDE_VOLUME = "qits-workspaces-runner-dot-claude-contract";

  /**
   * Its login commands, recorded with the workspace image's version as {@link
   * GoldenMasterRecordingTest#FIXED_VERSION}: a bump moves the image, not the command.
   */
  static final String CLAUDE_LOGIN_COMMAND =
      "docker run --rm -it --user 1000 --entrypoint claude -v "
          + DOT_CLAUDE_VOLUME
          + ":/claude-home -e HOME=/claude-home -e CLAUDE_CONFIG_DIR=/claude-home/.claude"
          + " registry.qits.runners.example.test/qits/workspace:"
          + GoldenMasterRecordingTest.FIXED_VERSION;

  static final String KIMI_LOGIN_COMMAND =
      "docker run --rm -it --user 1000 --entrypoint kimi -v "
          + DOT_CLAUDE_VOLUME
          + ":/claude-home -e HOME=/claude-home -e KIMI_CODE_HOME=/claude-home/.kimi-code"
          + " registry.qits.runners.example.test/qits/workspace:"
          + GoldenMasterRecordingTest.FIXED_VERSION
          + " login";

  /** The workspace token a stopped slot keeps, so its start re-presents it rather than minting. */
  static final String SLOT_TOKEN_ID = "00000000-0000-4000-8000-000000000022";

  /** When the resolved slot's agent was created, worked and was removed: fixed, so lines are too. */
  static final Instant ARCHIVED_AGENT_CREATED = Instant.parse("2026-01-01T00:00:00Z");

  static final Instant ARCHIVED_SESSION_AT = Instant.parse("2026-01-01T00:05:00Z");
  static final Instant ARCHIVED_AGENT_REMOVED = Instant.parse("2026-01-01T01:00:00Z");

  /** The stub daemon {@link #AN_AGENT_WITH_LEFTOVER_WORK} answers from, started once per JVM. */
  private static volatile HttpServer leftoverDaemon;

  /**
   * The bug ticket's agent, RUNNING in the wrapper's slot, which runs on the connected runner
   * {@code localhost}. With {@code draft}, it holds a saved prompt draft. With {@code leftover}, the
   * slot's daemon answers through its tunnel and its cleanup check finds work that never reached
   * the git host (a dirty submodule, an unpushed commit on the wrapper branch); without, the slot's
   * daemon is not connected — as it is for every slot in this suite.
   */
  private Setup agentInARunningSlot(boolean draft, boolean leftover) {
    connectedRunner();
    repositories.register(WRAPPER_REPOSITORY_ID);
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.RUNNING, UUID.fromString(RUNNER_ID));
              ids.add(slot);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.RUNNING);
            });
    if (draft) {
      promptDrafts.saveDraft(UUID.fromString(AGENT_ACTIVE_ID), DRAFT_CONTENT, DRAFT_PROMPT);
    }
    if (leftover) {
      long slot = ids.get(0);
      StubDaemonTunnels.install(vertx, leftoverDaemonPort(), row -> row == slot);
    }
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_ACTIVE_ID);
    params.put("bugTicketId", BUG_TICKET_ID);
    params.put("workspaceId", String.valueOf(ids.get(0)));
    return new Setup(params, List.copyOf(ids));
  }

  /** No agent row at all for {@link #AGENT_UNKNOWN_ID}. */
  private Setup noSuchAgent() {
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_UNKNOWN_ID);
    return new Setup(params, List.of());
  }

  /** The improvement ticket's agent, QUEUED: no slot was free, so it names none. */
  private Setup agentWaitingForASlot() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                agent(
                    AGENT_QUEUED_ID,
                    null,
                    AgentState.ACTIVE,
                    AgentRunState.QUEUED,
                    IMPROVEMENT_TICKET));
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_QUEUED_ID);
    params.put("improvementTicketId", IMPROVEMENT_TICKET_ID);
    return new Setup(params, List.of());
  }

  /**
   * A wrapper slot that was removed with its last agent (ABANDONED), and the session that agent's
   * harness ran there, on the shared harness volume where its directory is keyed: one exchange on
   * the bug ticket's wrapper branch. The agent's life and the session's lines carry fixed instants,
   * so the transcript is the same on every run.
   */
  private Setup resolvedSlotWithAnArchivedSession() {
    repositories.register(WRAPPER_REPOSITORY_ID);
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.STOPPED, UUID.fromString(RUNNER_ID));
              ids.add(slot);
              Workspace row = workspaces.findById(slot);
              row.status = WorkspaceStatus.ABANDONED;
              row.resolvedAt = ARCHIVED_AGENT_REMOVED;
              row.result = "Removed: its last agent was removed (qits-1152, D2).";
              agent(
                  AGENT_ACTIVE_ID,
                  slot,
                  AgentState.REMOVED,
                  AgentRunState.YIELDED,
                  BUG_TICKET,
                  ARCHIVED_AGENT_CREATED);
              AgentWorktree agent = agentRows.findById(UUID.fromString(AGENT_ACTIVE_ID));
              agent.obsoleteAt = null;
              agent.removedAt = ARCHIVED_AGENT_REMOVED;
            });
    String wrapperBranch = "ticket/" + BUG_TICKET.qualifiedId();
    String cwd =
        "/workspace/agents/"
            + AGENT_ACTIVE_ID
            + "/"
            + FakeRepositoryLookup.nameOf(WRAPPER_REPOSITORY_ID);
    Path file =
        Path.of(archiveRoot)
            .resolve(".claude")
            .resolve(new ClaudeCodeAgent().transcriptPath(cwd, ARCHIVED_SESSION_ID));
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          String.join(
                  "\n",
                  turn("user", "Fix the rounding in the invoice totals.", wrapperBranch, ARCHIVED_SESSION_AT),
                  turn(
                      "assistant",
                      "Rounded each line before summing; the totals match now.",
                      wrapperBranch,
                      ARCHIVED_SESSION_AT.plusSeconds(60)))
              + "\n",
          StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Could not lay out the archived session", e);
    }
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_ACTIVE_ID);
    params.put("sessionId", ARCHIVED_SESSION_ID);
    params.put("workspaceId", String.valueOf(ids.get(0)));
    return new Setup(params, List.copyOf(ids));
  }

  /** One transcript line, as the harness writes a conversation turn. */
  private static String turn(String type, String text, String branch, Instant at) {
    return "{\"type\":\""
        + type
        + "\",\"gitBranch\":\""
        + branch
        + "\",\"timestamp\":\""
        + at
        + "\",\"message\":{\"content\":\""
        + text
        + "\"}}";
  }

  /**
   * {@code localhost}, registered, greenlit, connected and greeted at the pin, answering every
   * frame the host sends as a healthy runner would ({@link ScriptedRunner}): its newest health
   * check passed, and its node reports Claude signed in and Kimi not.
   */
  private Setup runnerInService() {
    // The login commands name the workspace image by its public address.
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.withDomain(WorkspaceRunnerAddressesFixture.DOMAIN),
        WorkspaceRunnerAddresses.class);
    quarantinedRunner();
    WorkspaceRunner runner = runners.greenlight(UUID.fromString(RUNNER_ID));
    ScriptedRunner scripted = new ScriptedRunner();
    WorkspaceRunnerRegistry.Session session = runnerRegistry.admit(runner, scripted.connection());
    scripted.session = session;
    runnerSession = session;
    runnerRegistry.onHello(
        session,
        new Hello(
            WorkspacesRunnerBinary.VERSION,
            WorkspacesRunnerProtocol.CAPABILITY_VERSION,
            1,
            new RunnerCapabilities(true, "amd64", "linux", Map.of()),
            List.of(),
            Map.of()));
    runnerHealth.request(runner.id);
    runnerRegistry.onInventory(session, new Inventory(List.of(), List.of(), DOT_CLAUDE_VOLUME));
    runnerRegistry.onLoginState(session, scripted.loginState());
    return new Setup(runnerParams(), List.of());
  }

  /**
   * The wrapper's slot on {@link #runnerInService}, {@code runtime}, hosting the bug ticket's
   * agent: RUNNING while the slot runs, YIELDED while it is stopped. A stopped slot keeps the
   * workspace token its container was launched with, so starting it again queues it at once.
   */
  private Setup slotOnARunnerInService(WorkspaceRuntimeStatus runtime) {
    // A start is only queued where a runner's container could reach home: an edge plane.
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.planesWithDomain(WorkspaceRunnerAddressesFixture.DOMAIN),
        WorkspaceAddressPlanes.class);
    runnerInService();
    repositories.register(WRAPPER_REPOSITORY_ID);
    boolean running = runtime == WorkspaceRuntimeStatus.RUNNING;
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(runtime, UUID.fromString(RUNNER_ID));
              ids.add(slot);
              Workspace row = workspaces.findById(slot);
              row.commissionedTokenId = SLOT_TOKEN_ID;
              row.commissionedTokenSubject = "contract-workspace-token";
              row.commissionedToken = "qits_tok_contract-workspace";
              agent(
                  AGENT_ACTIVE_ID,
                  slot,
                  AgentState.ACTIVE,
                  running ? AgentRunState.RUNNING : AgentRunState.YIELDED);
            });
    Map<String, String> params = runnerParams();
    params.put("agentId", AGENT_ACTIVE_ID);
    params.put("repositoryId", WRAPPER_REPOSITORY_ID);
    params.put("workspaceId", String.valueOf(ids.get(0)));
    return new Setup(params, List.copyOf(ids));
  }

  /**
   * The wrapper's slot, RUNNING on {@code localhost} as it last reported, with the bug ticket's
   * agent in it — and {@code localhost}, registered and greenlit, holds no connection: it dropped.
   */
  private Setup slotOnADisconnectedRunner() {
    quarantinedRunner();
    runners.greenlight(UUID.fromString(RUNNER_ID));
    repositories.register(WRAPPER_REPOSITORY_ID);
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.RUNNING, UUID.fromString(RUNNER_ID));
              ids.add(slot);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.RUNNING);
            });
    Map<String, String> params = runnerParams();
    params.put("agentId", AGENT_ACTIVE_ID);
    params.put("repositoryId", WRAPPER_REPOSITORY_ID);
    params.put("workspaceId", String.valueOf(ids.get(0)));
    return new Setup(params, List.copyOf(ids));
  }

  /**
   * A runner on a socket that answers what the host asks as a healthy runner does, at once and on
   * the asking thread: a {@code stop} with {@code stopped}, a {@code delete} with {@code deleted},
   * a {@code healthCheck} with a passing {@code healthChecked}, a {@code probeLogin} with its
   * {@code loginState}. Everything else it takes and says nothing. Answering on the asking thread
   * is what keeps a recording deterministic: the answer is in before the door returns.
   */
  private final class ScriptedRunner {

    volatile WorkspaceRunnerRegistry.Session session;

    /** Checked now: a login checked before the connection began proves nothing of its image. */
    LoginState loginState() {
      return new LoginState(LoginPresence.PRESENT, LoginPresence.ABSENT, Instant.now().toString());
    }

    WebSocketConnection connection() {
      return (WebSocketConnection)
          Proxy.newProxyInstance(
              ProviderStates.class.getClassLoader(),
              new Class<?>[] {WebSocketConnection.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "id" -> "contract-runner-in-service";
                    case "isOpen" -> Boolean.TRUE;
                    case "isClosed" -> Boolean.FALSE;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "contract-runner-in-service";
                    case "sendText" -> {
                      answer(String.valueOf(args[0]));
                      yield Uni.createFrom().voidItem();
                    }
                    default ->
                        Uni.class.isAssignableFrom(method.getReturnType())
                            ? Uni.createFrom().voidItem()
                            : null;
                  });
    }

    private void answer(String frame) {
      WorkspaceRunnerRegistry.Session to = session;
      if (to == null) {
        return;
      }
      switch (runnerCodec.decode(frame)) {
        case Stop stop -> runnerRegistry.onStopped(to, new Stopped(stop.rowId()));
        case Delete delete -> runnerRegistry.onDeleted(to, new Deleted(delete.rowId()));
        case HealthCheck check ->
            runnerRegistry.onHealthChecked(
                to,
                new HealthChecked(
                    true,
                    "2 checks passed",
                    check.requestId(),
                    List.of(
                        new CheckResult("docker", true, "27.1", Map.of("version", "27.1")),
                        new CheckResult(
                            "selfTest", true, "the workspace image started", Map.of()))));
        case ProbeLogin ignored -> runnerRegistry.onLoginState(to, loginState());
        default -> {}
      }
    }
  }

  /**
   * The port of a stub workspace daemon whose cleanup check finds leftover work for any agent, and
   * which knows no other agent verb (404, as a daemon answers for an agent it does not know).
   */
  private int leftoverDaemonPort() {
    HttpServer server = leftoverDaemon;
    if (server == null) {
      synchronized (ProviderStates.class) {
        server = leftoverDaemon;
        if (server == null) {
          try {
            server =
                vertx
                    .createHttpServer()
                    .requestHandler(
                        request -> {
                          boolean check = request.path().endsWith("/cleanup-check");
                          request
                              .response()
                              .setStatusCode(check ? 200 : 404)
                              .putHeader("Content-Type", "application/json")
                              .end(
                                  check
                                      ? "{\"clean\":false,\"dirty\":[\"qits-ci-service\"],"
                                          + "\"unpushed\":[{\"repository\":\"wrapper\","
                                          + "\"branch\":\"ticket/contract-00000001-10\","
                                          + "\"commits\":1}],\"busy\":false}"
                                      : "{\"message\":\"Unknown agent\"}");
                        })
                    .listen(0, "127.0.0.1")
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
          } catch (Exception e) {
            throw new IllegalStateException("Could not start the stub workspace daemon", e);
          }
          leftoverDaemon = server;
        }
      }
    }
    return server.actualPort();
  }

  /** A slot workspace of the wrapper, on a runner, naming no branch (qits-1152). */
  private Long slot(WorkspaceRuntimeStatus runtime) {
    return slot(runtime, null);
  }

  /** A slot workspace of the wrapper, placed on {@code runnerId} (null: no runner took it yet). */
  private Long slot(WorkspaceRuntimeStatus runtime, UUID runnerId) {
    Workspace row = new Workspace();
    row.runnerId = runnerId;
    row.repositoryId = WRAPPER_REPOSITORY_ID;
    row.workspaceId = SLOT_LABEL;
    row.status = WorkspaceStatus.ACTIVE;
    row.runtimeStatus = runtime;
    row.placement = eu.wohlben.qits.workspaces.entity.WorkspacePlacement.RUNNER;
    row.gitRefs = "[]";
    workspaces.persist(row);
    workspaces.flush();
    return row.id;
  }

  private void agent(String id, Long slot, AgentState state, AgentRunState runState) {
    agent(id, slot, state, runState, BUG_TICKET);
  }

  /** A work item an agent works on, as qits-projects' "… in detail" states record it. */
  record Item(String workId, String qualifiedId, boolean epic, String title) {}

  static final Item BUG_TICKET =
      new Item(BUG_TICKET_ID, "contract-00000001-10", false, "Invoice totals are off by one cent");
  static final Item EPIC =
      new Item(EPIC_ID, "contract-00000001-2", true, "Export invoices for the accountants");
  static final Item IMPROVEMENT_TICKET =
      new Item(
          IMPROVEMENT_TICKET_ID, "contract-00000001-11", false, "Remember the last export format");

  private void agent(String id, Long slot, AgentState state, AgentRunState runState, Item item) {
    agent(id, slot, state, runState, item, null);
  }

  /** {@link #agent}, created at {@code createdAt} (null: just now); the column is never updated. */
  private void agent(
      String id,
      Long slot,
      AgentState state,
      AgentRunState runState,
      Item item,
      Instant createdAt) {
    String wrapperBranch = (item.epic() ? "epic/" : "ticket/") + item.qualifiedId();
    AgentWorktree agent = new AgentWorktree();
    agent.id = UUID.fromString(id);
    agent.workspaceId = slot;
    agent.repositoryId = WRAPPER_REPOSITORY_ID;
    agent.workId = item.workId();
    agent.entityId = item.qualifiedId();
    if (item.epic()) {
      agent.epicId = item.workId();
    } else {
      agent.ticketId = item.workId();
    }
    agent.wrapperBranch = wrapperBranch;
    agent.state = state;
    agent.runState = runState;
    agent.entityTitle = item.title();
    agent.entityStatus = state == AgentState.ACTIVE ? "IMPLEMENTING" : "VERIFIED";
    agent.entityBlocked = false;
    agent.commissionedTokenSubject = CONTRACT_CALLER;
    agent.gitRefs = "[\"refs/heads/" + wrapperBranch + "\",\"refs/heads/" + wrapperBranch + "-*\"]";
    // Apart by state, so a listing newest first is the same order on every run.
    agent.createdAt =
        createdAt != null ? createdAt : Instant.now().minusSeconds(60L * state.ordinal());
    agent.lastActivityAt = Instant.now();
    if (state != AgentState.ACTIVE) {
      agent.obsoleteAt = Instant.now();
    }
    if (state == AgentState.REMOVED) {
      agent.removedAt = Instant.now();
    }
    agentRows.persist(agent);
    agentRows.flush();
  }

  private static Map<String, String> runnerParams() {
    Map<String, String> params = new TreeMap<>();
    params.put("runnerId", RUNNER_ID);
    params.put("runnerName", RUNNER_NAME);
    return params;
  }

  /** No runner at all, and a qits-idp that commissions: a create answers the install line. */
  private Setup noRunners() {
    commissioningRunners();
    return new Setup(runnerParams(), List.of());
  }

  /**
   * {@code localhost}, declared and never registered; its registration token is presented as the
   * {@code authorization} param, so the register door answers it its client.
   */
  private Setup unregisteredRunner() {
    commissioningRunners();
    declareRunner();
    return new Setup(presentingItsRegistrationToken(), List.of());
  }

  /**
   * {@code localhost}, registered already: its registration token, presented as the {@code
   * authorization} param, opens nothing now (the register door answers 409).
   */
  private Setup registeredRunner() {
    commissioningRunners();
    quarantinedRunner();
    return new Setup(presentingItsRegistrationToken(), List.of());
  }

  /** The runner params, plus the registration token's bearer granted as the edge would forward it. */
  private Map<String, String> presentingItsRegistrationToken() {
    bearers.grant(REGISTRATION_BEARER, REGISTRATION_SUBJECT, REGISTRATION_ROLE);
    Map<String, String> params = runnerParams();
    params.put("authorization", REGISTRATION_BEARER);
    return params;
  }

  /** {@code localhost}, registered and quarantined, as registration leaves a runner. */
  private Setup quarantinedRunner() {
    declareRunner();
    runners.markRegistered(UUID.fromString(RUNNER_ID), "contract-runner-client", null);
    return new Setup(runnerParams(), List.of());
  }

  /** {@code localhost}, registered, greenlit and holding an open socket. */
  private Setup connectedRunner() {
    quarantinedRunner();
    WorkspaceRunner runner = runners.greenlight(UUID.fromString(RUNNER_ID));
    runnerSession = runnerRegistry.admit(runner, openConnection());
    return new Setup(runnerParams(), List.of());
  }

  private void declareRunner() {
    runners.create(
        UUID.fromString(RUNNER_ID),
        RUNNER_NAME,
        null,
        1,
        REGISTRATION_TOKEN_ID,
        REGISTRATION_SUBJECT);
  }

  /**
   * A qits-idp that hands out the one registration token, and a public domain to address a runner
   * by — both for the current test only, as {@code WorkspaceRunnerControllerTest} installs them.
   */
  private static void commissioningRunners() {
    QuarkusMock.installMockForType(
        new IdpRunnerCommissioner() {
          @Override
          public boolean enabled() {
            return true;
          }

          @Override
          public IssuedToken registrationToken(UUID runnerId) {
            return new IssuedToken(REGISTRATION_TOKEN_ID, REGISTRATION_TOKEN, REGISTRATION_SUBJECT);
          }

          @Override
          public RunnerClient runnerClient(UUID runnerId) {
            return new RunnerClient(RUNNER_CLIENT_ID, RUNNER_CLIENT_SECRET);
          }

          @Override
          public void decommissionClient(String runnerClientId) {}

          @Override
          public boolean deleteToken(String tokenId) {
            return true;
          }
        },
        IdpRunnerCommissioner.class);
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.withDomain(WorkspaceRunnerAddressesFixture.DOMAIN),
        WorkspaceRunnerAddresses.class);
  }

  /** A socket that is open and says nothing: all the registry reads of one to call it connected. */
  private static WebSocketConnection openConnection() {
    return (WebSocketConnection)
        Proxy.newProxyInstance(
            ProviderStates.class.getClassLoader(),
            new Class<?>[] {WebSocketConnection.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "id" -> "contract-runner-connection";
                  case "isOpen" -> Boolean.TRUE;
                  case "isClosed" -> Boolean.FALSE;
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  case "toString" -> "contract-runner-connection";
                  default -> null;
                });
  }

  /** Builds the three git origins once per JVM, over whatever an earlier run left under target/. */
  private void buildOrigins() {
    if (!ORIGINS_BUILT.compareAndSet(false, true)) {
      return;
    }
    try {
      for (String repoId : GIT_REPOSITORIES) {
        Path own = Path.of(dataDir).toAbsolutePath();
        TestOrigin.deleteRecursively(own.resolve("mirrors").resolve(repoId + ".git"));
        TestOrigin.deleteRecursively(own.resolve("worktrees").resolve(repoId));
        TestOrigin.deleteRecursively(own.resolve("metadata").resolve(repoId));
        TestOrigin.create(originsDir, repoId, repoId.equals(SWEPT_REPOSITORY_ID));
        repositories.register(repoId);
      }
    } catch (Exception e) {
      ORIGINS_BUILT.set(false);
      throw new IllegalStateException("Could not build the contract origins", e);
    }
  }

  private File origin(String repoId) {
    return Path.of(originsDir, repoId, "origin").toAbsolutePath().toFile();
  }

  private Long write(
      String workId,
      String qualifiedId,
      String branch,
      WorkspaceStatus status,
      WorkspaceRuntimeStatus runtime) {
    Workspace row = new Workspace();
    row.repositoryId = WRAPPER_REPOSITORY_ID;
    row.workspaceId =
        workId == null ? HAND_MADE_LABEL : branch.replaceAll("[^A-Za-z0-9_-]", "-");
    row.branch = branch;
    row.status = status;
    row.runtimeStatus = runtime;
    // A regular workspace runs on a runner: an ACTIVE regular DIRECT row is refused (V15, qits-780).
    row.placement = eu.wohlben.qits.workspaces.entity.WorkspacePlacement.RUNNER;
    row.workId = workId;
    row.entityId = qualifiedId;
    if (workId != null && workId.equals(EPIC_ID)) {
      row.epicId = workId;
    } else if (workId != null && (workId.equals(BUG_TICKET_ID))) {
      row.ticketId = workId;
    }
    if (status != WorkspaceStatus.ACTIVE) {
      row.resolvedAt = Instant.now();
    }
    workspaces.persist(row);
    workspaces.flush();
    return row.id;
  }
}
