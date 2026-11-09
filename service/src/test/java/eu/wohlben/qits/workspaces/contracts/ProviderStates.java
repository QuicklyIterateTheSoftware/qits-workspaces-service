package eu.wohlben.qits.workspaces.contracts;

import eu.wohlben.qits.workspaces.control.AgentAttentions;
import eu.wohlben.qits.workspaces.control.AgentService;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.FakeTargetPlacement;
import eu.wohlben.qits.workspaces.control.TargetPlacement;
import eu.wohlben.qits.workspaces.control.WorkspaceAddressPlane;
import eu.wohlben.qits.workspaces.control.WorkspacePromptDraftService;
import eu.wohlben.qits.workspaces.daemonhost.StubDaemonTunnels;
import eu.wohlben.qits.workspaces.daemonhost.WorkspaceDaemonRegistry;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import io.quarkus.websockets.next.WebSocketConnection;
import eu.wohlben.qits.workspaces.transcripthost.ShippedClaudeTranscripts;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.util.concurrent.TimeUnit;
import eu.wohlben.qits.workspaces.control.TechnicalProcess;
import eu.wohlben.qits.workspaces.control.TechnicalProcessRegistry;
import eu.wohlben.qits.workspaces.control.TestGit;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceAddressPlanes;
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
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspacePromptAttachmentRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.entity.PromptAttachmentSource;
import eu.wohlben.qits.workspaces.entity.WorkspacePromptAttachment;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
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
 *   <li>project {@code …000e} (slug {@code contract-00000001})
 *   <li>epic {@code …0005}, {@code contract-00000001-1}
 *   <li>feature {@code …0006}, {@code contract-00000001-5}
 *   <li>task {@code …0010}, {@code contract-00000001-4}
 *   <li>bug ticket {@code …0001}, {@code contract-00000001-9}
 *   <li>improvement ticket {@code …000a}, {@code contract-00000001-10} — no workspace
 * </ul>
 *
 * <p>Those values are not restated here: {@link #PROJECT_ID} and its siblings are read from
 * qits-projects' golden masters on the test classpath, so a re-recording over there that shifts
 * them (qits-1908 removed a seeded entity) moves these rows with it at the next pin bump. The list
 * above is the seed as of qits-projects 2026.1106.202329.
 *
 * <p>The wrapper repository the workspaces stand in has no frozen id over there; it is {@code
 * …0013}, the next number not used by those states.
 *
 * <p>The states the round-2 consumers asked for (qits-1149) add three repositories with a real git
 * origin — {@code …0014} (a dispatch), {@code …0015} (a released branch) and {@code …0016} (a branch
 * sweep), each built fresh once per JVM. A slot that runs does so under a lease, {@code …0023}, on
 * an execution target, {@code …0020} (epic qits-1481): {@link FakeTargetPlacement} stands for
 * qits-system.
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
  // What a project's agents need a person for (epic qits-1681): one agent per attention code and a
  // healthy one, their waits on every kind of subject.
  public static final String A_PROJECT_WITH_AGENTS_NEEDING_ATTENTION =
      "a project with agents needing attention";
  // Where a project's agents are in coming up (qits-2007): one agent per lifecycle phase, and READY
  // both idle and busy.
  public static final String A_PROJECT_WITH_AGENTS_STARTING_UP =
      "a project with agents starting up";
  public static final String REPOSITORIES_WITH_MERGED_BRANCHES =
      "repositories with merged branches";
  public static final String A_CONFIGURED_WORKSPACE_IMAGE = "a configured workspace image";

  // The landing app's workspace pages (epic qits-1230): a project's slots and their agents, one
  // slot, and one item's agent while its slot's container comes up.
  public static final String A_PROJECT_WITH_WORKSPACE_SLOTS =
      "a project with workspace slots hosting agents";
  public static final String A_WORKSPACE_SLOT_HOSTING_AGENTS = "a workspace slot hosting agents";
  public static final String AN_AGENT_WHOSE_WORKSPACE_IS_STARTING =
      "an agent whose workspace is starting";

  // The rest of the landing app's workspace UI (epic qits-1230): the agent page's chat and its
  // actions (qits-1241, qits-1243), a resolved slot's record and the slot's container buttons.
  public static final String AN_AGENT_WITH_A_SAVED_PROMPT_DRAFT =
      "an agent with a saved prompt draft";
  public static final String AN_AGENT_WITH_NO_PROMPT_DRAFT = "an agent with no prompt draft";
  public static final String NO_AGENT_WITH_THAT_ID = "no agent with that id";
  public static final String AN_AGENT_WAITING_FOR_A_SLOT = "an agent waiting for a workspace slot";
  public static final String AN_AGENT_WHOSE_DAEMON_IS_NOT_CONNECTED =
      "an agent whose workspace daemon is not connected";
  public static final String AN_AGENT_WITH_LEFTOVER_WORK = "an agent with leftover work";
  // An agent's interrupt (epic qits-1667): a harness mid-turn whose daemon answers the ESC.
  public static final String AN_AGENT_IN_THE_MIDDLE_OF_A_TURN = "an agent in the middle of a turn";
  public static final String A_RESOLVED_SLOT_WITH_AN_ARCHIVED_SESSION =
      "a resolved workspace slot with an archived agent session";
  // The slot's container buttons on a lease (epic qits-1481).
  public static final String A_SLOT_RUNNING_ON_A_TARGET = "a workspace slot running on a target";
  public static final String A_STOPPED_SLOT = "a stopped workspace slot";
  public static final String A_SLOT_QITS_SYSTEM_CANNOT_BE_ASKED_ABOUT =
      "a workspace slot qits-system cannot be asked about";

  // The doors no state recorded yet (qits-1937): a project desk's declared waits, a repository's
  // workspace history and an agent's prompt attachments.
  public static final String A_PROJECT_DESK_WITH_AN_OPEN_WAIT = "a project desk with an open wait";
  public static final String A_REPOSITORY_WITH_WORKSPACE_HISTORY =
      "a repository with workspace history";
  public static final String AN_AGENT_WITH_A_PROMPT_ATTACHMENT = "an agent with a prompt attachment";

  // The two slot states' names before the cutover, answered as aliases and never recorded: the
  // consumers' pinned pacts were written against the golden masters that named them so, and they
  // can take the new names only once this service's golden masters carrying them are published.
  // Drop both once no pinned consumer pact names them (ConsumerPactVerificationTest says so).
  public static final String LEGACY_A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE =
      "a workspace slot running on a runner in service";
  public static final String LEGACY_A_STOPPED_SLOT_ON_A_RUNNER_IN_SERVICE =
      "a stopped workspace slot on a runner in service";

  // qits-projects' "… in detail" seed, read from its golden masters rather than restated (qits-1915):
  // every "… in detail" state records the same seeded project, so one state answers the ids, and
  // each item's own state answers its qualified id.
  private static final Map<String, String> PROJECTS_SEED = GoldenMasters.params("an epic in detail");

  static final String PROJECT_ID = PROJECTS_SEED.get("projectId");
  static final String BUG_TICKET_ID = PROJECTS_SEED.get("bugTicketId");
  static final String EPIC_ID = PROJECTS_SEED.get("epicId");
  static final String FEATURE_ID = PROJECTS_SEED.get("featureId");
  static final String IMPROVEMENT_TICKET_ID = PROJECTS_SEED.get("improvementTicketId");
  static final String TASK_ID = PROJECTS_SEED.get("taskId");

  static final String BUG_TICKET_QUALIFIED_ID = projectsQualifiedId("a bug ticket in detail");
  static final String IMPROVEMENT_TICKET_QUALIFIED_ID =
      projectsQualifiedId("an improvement ticket in detail");
  static final String EPIC_QUALIFIED_ID = projectsQualifiedId("an epic in detail");
  static final String FEATURE_QUALIFIED_ID = projectsQualifiedId("a feature in detail");
  static final String TASK_QUALIFIED_ID = projectsQualifiedId("a task in detail");

  static final String WRAPPER_REPOSITORY_ID = "00000000-0000-4000-8000-000000000013";
  static final String DISPATCH_REPOSITORY_ID = "00000000-0000-4000-8000-000000000014";
  static final String RELEASED_REPOSITORY_ID = "00000000-0000-4000-8000-000000000015";
  static final String SWEPT_REPOSITORY_ID = "00000000-0000-4000-8000-000000000016";

  /**
   * The repository of the one TARGET row a waiting lease stands for: while it waits, a dispatch
   * queues its agent rather than asking for another workspace (AgentService.targetCapacity).
   */
  static final String WAITING_REPOSITORY_ID = "00000000-0000-4000-8000-000000000017";
  /** The execution target a running slot's lease is granted on. */
  static final String TARGET_ID = "00000000-0000-4000-8000-000000000020";

  /** The lease a running slot's container runs under. */
  static final String LEASE_ID = "00000000-0000-4000-8000-000000000023";

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
  @Inject AgentAttentions attentions;
  @Inject AgentService agentService;
  @Inject WorkspaceService workspaceService;
  @Inject FakeRepositoryLookup repositories;
  @Inject FakeTargetPlacement targets;
  @Inject TechnicalProcessRegistry technicalProcesses;
  @Inject WorkspacePromptDraftService promptDrafts;
  @Inject WorkspacePromptAttachmentRepository promptAttachments;
  @Inject Vertx vertx;
  @Inject ContractBearers bearers;

  @Inject ShippedClaudeTranscripts shippedTranscripts;

  @Inject WorkspaceDaemonRegistry daemons;

  @ConfigProperty(name = "qits.test.origins-dir")
  String originsDir;

  @ConfigProperty(name = "qits.workspaces.data-dir")
  String dataDir;

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
        A_PROJECT_WITH_AGENTS_NEEDING_ATTENTION,
        A_PROJECT_WITH_AGENTS_STARTING_UP,
        REPOSITORIES_WITH_MERGED_BRANCHES,
        A_CONFIGURED_WORKSPACE_IMAGE,
        A_PROJECT_WITH_WORKSPACE_SLOTS,
        A_WORKSPACE_SLOT_HOSTING_AGENTS,
        AN_AGENT_WHOSE_WORKSPACE_IS_STARTING,
        AN_AGENT_WITH_A_SAVED_PROMPT_DRAFT,
        AN_AGENT_WITH_NO_PROMPT_DRAFT,
        NO_AGENT_WITH_THAT_ID,
        AN_AGENT_WAITING_FOR_A_SLOT,
        AN_AGENT_WHOSE_DAEMON_IS_NOT_CONNECTED,
        AN_AGENT_WITH_LEFTOVER_WORK,
        AN_AGENT_IN_THE_MIDDLE_OF_A_TURN,
        A_RESOLVED_SLOT_WITH_AN_ARCHIVED_SESSION,
        A_SLOT_RUNNING_ON_A_TARGET,
        A_STOPPED_SLOT,
        A_SLOT_QITS_SYSTEM_CANNOT_BE_ASKED_ABOUT,
        A_PROJECT_DESK_WITH_AN_OPEN_WAIT,
        A_REPOSITORY_WITH_WORKSPACE_HISTORY,
        AN_AGENT_WITH_A_PROMPT_ATTACHMENT,
        LEGACY_A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE,
        LEGACY_A_STOPPED_SLOT_ON_A_RUNNER_IN_SERVICE);
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
      case A_PROJECT_WITH_AGENTS_NEEDING_ATTENTION -> agentsNeedingAttention();
      case A_PROJECT_WITH_AGENTS_STARTING_UP -> agentsStartingUp();
      case REPOSITORIES_WITH_MERGED_BRANCHES -> mergedBranches();
      case A_CONFIGURED_WORKSPACE_IMAGE -> new Setup(new TreeMap<>(), List.of());
      case A_PROJECT_WITH_WORKSPACE_SLOTS -> slotsHostingAgents(false);
      case A_WORKSPACE_SLOT_HOSTING_AGENTS -> slotsHostingAgents(true);
      case AN_AGENT_WHOSE_WORKSPACE_IS_STARTING -> agentWhoseWorkspaceIsStarting();
      case AN_AGENT_WITH_A_SAVED_PROMPT_DRAFT -> agentInARunningSlot(true, false);
      case AN_AGENT_WITH_NO_PROMPT_DRAFT, AN_AGENT_WHOSE_DAEMON_IS_NOT_CONNECTED ->
          agentInARunningSlot(false, false);
      case AN_AGENT_WITH_LEFTOVER_WORK -> agentInARunningSlot(false, true);
      case AN_AGENT_IN_THE_MIDDLE_OF_A_TURN -> agentInTheMiddleOfATurn();
      case NO_AGENT_WITH_THAT_ID -> noSuchAgent();
      case AN_AGENT_WAITING_FOR_A_SLOT -> agentWaitingForASlot();
      case A_RESOLVED_SLOT_WITH_AN_ARCHIVED_SESSION -> resolvedSlotWithAnArchivedSession();
      case A_SLOT_RUNNING_ON_A_TARGET, LEGACY_A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE ->
          slotOnATarget(WorkspaceRuntimeStatus.RUNNING);
      case A_STOPPED_SLOT, LEGACY_A_STOPPED_SLOT_ON_A_RUNNER_IN_SERVICE ->
          slotOnATarget(WorkspaceRuntimeStatus.STOPPED);
      case A_SLOT_QITS_SYSTEM_CANNOT_BE_ASKED_ABOUT -> slotQitsSystemCannotBeAskedAbout();
      case A_PROJECT_DESK_WITH_AN_OPEN_WAIT -> deskWithAnOpenWait();
      case A_REPOSITORY_WITH_WORKSPACE_HISTORY -> repositoryWithWorkspaceHistory();
      case AN_AGENT_WITH_A_PROMPT_ATTACHMENT -> agentWithAPromptAttachment();
      default -> throw new IllegalArgumentException("No provider state named '" + state + "'");
    };
  }

  /** Removes every row a state writes, and tears down every workspace a git-backed state made. */
  public void cleanUp() {
    // A pass the last interaction kicked (a stop, a restart, a discard) must be over before the
    // next state writes its rows, or it places that state's queued agent mid-recording (qits-1667).
    agentService.awaitSchedulerIdle();
    bearers.reset();
    disconnectStartupDaemon();
    endStartingProcess();
    endLeftoverProcesses();
    tearDownGitWorkspaces();
    forgetShippedTranscripts();
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
              workspaces.delete("repositoryId = ?1", WAITING_REPOSITORY_ID);
              agentRows.delete("repositoryId = ?1", ATTENTION_REPOSITORY_ID);
              workspaces.delete("repositoryId = ?1", ATTENTION_REPOSITORY_ID);
              agentRows.delete("repositoryId = ?1", STARTUP_REPOSITORY_ID);
              workspaces.delete("repositoryId = ?1", STARTUP_REPOSITORY_ID);
              // The desk's waits name no agent, so no agent's delete takes them (qits-1937).
              agentWaits.delete("deskProjectId = ?1", UUID.fromString(PROJECT_ID));
              workspaces.delete("repositoryId = ?1", HISTORY_REPOSITORY_ID);
            });
    targets.reset();
  }

  /** The session {@link #A_RESOLVED_SLOT_WITH_AN_ARCHIVED_SESSION} shipped for its agent. */
  private void forgetShippedTranscripts() {
    try {
      TestOrigin.deleteRecursively(
          shippedTranscripts.store().agentDirectory(UUID.fromString(AGENT_ACTIVE_ID)));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Could not empty the agent's transcript store", e);
    }
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

  /** The {@code qualifiedId} param qits-projects' golden masters record for {@code state}. */
  private static String projectsQualifiedId(String state) {
    return java.util.Objects.requireNonNull(
        GoldenMasters.params(state).get("qualifiedId"), state + " records no qualifiedId");
  }

  private static Map<String, String> params() {
    Map<String, String> params = new TreeMap<>();
    params.put("bugTicketId", BUG_TICKET_ID);
    params.put("bugTicketQualifiedId", BUG_TICKET_QUALIFIED_ID);
    params.put("epicId", EPIC_ID);
    params.put("featureId", FEATURE_ID);
    params.put("improvementTicketId", IMPROVEMENT_TICKET_ID);
    params.put("improvementTicketQualifiedId", IMPROVEMENT_TICKET_QUALIFIED_ID);
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
                      BUG_TICKET_QUALIFIED_ID,
                      bugBranch,
                      WorkspaceStatus.ABANDONED,
                      WorkspaceRuntimeStatus.STOPPED));
              ids.add(
                  write(
                      BUG_TICKET_ID,
                      BUG_TICKET_QUALIFIED_ID,
                      bugBranch,
                      WorkspaceStatus.INTEGRATED,
                      WorkspaceRuntimeStatus.STOPPED));
              ids.add(
                  write(
                      EPIC_ID,
                      EPIC_QUALIFIED_ID,
                      "epic/export-invoices-for-the-accountants",
                      WorkspaceStatus.ACTIVE,
                      WorkspaceRuntimeStatus.RUNNING));
              ids.add(
                  write(
                      FEATURE_ID,
                      FEATURE_QUALIFIED_ID,
                      "feature/pdf-export",
                      WorkspaceStatus.ACTIVE,
                      WorkspaceRuntimeStatus.STOPPED));
              ids.add(
                  write(
                      TASK_ID,
                      TASK_QUALIFIED_ID,
                      "task/download-button-on-the-invoice-list",
                      WorkspaceStatus.ACTIVE,
                      WorkspaceRuntimeStatus.RUNNING));
              ids.add(
                  write(
                      BUG_TICKET_ID,
                      BUG_TICKET_QUALIFIED_ID,
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

  /** The caller the recording sends ({@link ContractBearers#caller}), and so the agents' token subject. */
  static final String CONTRACT_CALLER = "contract-caller";
  static final String AGENT_OBSOLETE_ID = "00000000-0000-4000-8000-0000000000a2";
  static final String AGENT_REMOVED_ID = "00000000-0000-4000-8000-0000000000a3";
  static final String WRAPPER_BRANCH = "ticket/" + BUG_TICKET_QUALIFIED_ID;
  static final String PUSHED_BRANCH = WRAPPER_BRANCH + "-totals";

  /**
   * A wrapper repository and a ticket nobody dispatched yet. A lease of this service waits already
   * (one TARGET row of another repository, QUEUED), so a dispatch queues its agent.
   */
  private Setup ticketToDispatch() {
    // An agent's credential is minted only where it can be spent: through an edge plane.
    anEdgePlane();
    buildOrigins();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Workspace waiting = new Workspace();
              waiting.repositoryId = WAITING_REPOSITORY_ID;
              waiting.workspaceId = "contract-waiting";
              waiting.branch = "contract-waiting";
              waiting.status = WorkspaceStatus.ACTIVE;
              waiting.runtimeStatus = WorkspaceRuntimeStatus.QUEUED;
              waiting.placement = eu.wohlben.qits.workspaces.entity.WorkspacePlacement.TARGET;
              waiting.leaseId = UUID.randomUUID();
              workspaces.persist(waiting);
            });
    Map<String, String> params = new TreeMap<>();
    params.put("repositoryId", DISPATCH_REPOSITORY_ID);
    params.put("ticketId", BUG_TICKET_ID);
    params.put("ticketQualifiedId", BUG_TICKET_QUALIFIED_ID);
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

  /**
   * One ACTIVE agent on the bug ticket, holding a slot whose lease waits. {@code agentId} names it
   * for the controls addressed by the agent (epic qits-1667).
   */
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
    params.put("agentId", AGENT_ACTIVE_ID);
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

  // --- attention (epic qits-1681) -----------------------------------------------------------------

  /**
   * The repository the attention state's agents stand in: a wrapper of {@link #PROJECT_ID} of its
   * own, so the project its listing filters by is never one an earlier state remembered for the
   * shared wrapper.
   */
  static final String ATTENTION_REPOSITORY_ID = "00000000-0000-4000-8000-000000000018";

  static final String AGENT_PASSED_ID = "00000000-0000-4000-8000-0000000000a6";
  static final String AGENT_BLOCKED_ID = "00000000-0000-4000-8000-0000000000a7";
  static final String AGENT_IDLE_ID = "00000000-0000-4000-8000-0000000000a8";
  static final String AGENT_OVERDUE_ID = "00000000-0000-4000-8000-0000000000a9";
  static final String AGENT_HEALTHY_ID = "00000000-0000-4000-8000-0000000000aa";

  static final String PASSED_WAIT_ID = "00000000-0000-4000-8000-0000000000b2";
  static final String OVERDUE_WAIT_ID = "00000000-0000-4000-8000-0000000000b3";
  static final String ITEM_WAIT_ID = "00000000-0000-4000-8000-0000000000b4";
  static final String COMMENTS_WAIT_ID = "00000000-0000-4000-8000-0000000000b5";

  static final Item FEATURE = new Item(FEATURE_ID, FEATURE_QUALIFIED_ID, false, "PDF export");
  static final Item TASK =
      new Item(TASK_ID, TASK_QUALIFIED_ID, false, "Download button on the invoice list");

  /** When the passed request ended and the item waited on began implementing: fixed, so a reason is. */
  static final Instant SUBJECT_STATE_AT = Instant.parse("2026-10-20T10:42:00Z");

  /**
   * The project's five ACTIVE agents, RUNNING in one slot of {@link #ATTENTION_REPOSITORY_ID}, newest
   * first: the bug ticket's waits on a release request that FINALIZED ({@code WAIT_SUBJECT_PASSED});
   * the epic's item is blocked explicitly by a person for 50 minutes, the desk asked before that
   * ({@code BLOCKED_NO_DESK}); the feature's agent has been idle 42 minutes with nothing declared,
   * its three nudges spent ({@code IDLE_NO_WAIT}); the task's waits on a run RUNNING 1 h 40 min,
   * expected to take 35 ({@code WAIT_OVERDUE}); and the improvement ticket's is working, waiting on a
   * work item to be VERIFIED and on the task's comments — nothing to see. The attention is evaluated
   * by {@link AgentAttentions} as the scheduler would; the durations are offset by a few seconds so
   * a pass in the middle of a recording writes the same minutes.
   */
  private Setup agentsNeedingAttention() {
    repositories.registerWrapper(ATTENTION_REPOSITORY_ID, PROJECT_ID, "contract-qits");
    Instant now = Instant.now();
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Workspace row = new Workspace();
              row.repositoryId = ATTENTION_REPOSITORY_ID;
              row.workspaceId = SLOT_LABEL;
              row.status = WorkspaceStatus.ACTIVE;
              row.runtimeStatus = WorkspaceRuntimeStatus.QUEUED;
              row.placement = eu.wohlben.qits.workspaces.entity.WorkspacePlacement.TARGET;
              workspaces.persist(row);
              workspaces.flush();
              Long slot = row.id;
              ids.add(slot);

              AgentWorktree passed = attentionAgent(AGENT_PASSED_ID, slot, BUG_TICKET, now, 1);
              agentWaits.persist(
                  subjectWait(
                      PASSED_WAIT_ID, passed, "release request contract-qits-rr-368 to end",
                      "ReleaseRequestStateChanged", "releaseRequestId", RELEASE_REQUEST_ID,
                      "RELEASE_REQUEST",
                      "[\"FINALIZED\",\"REJECTED\",\"FAILED\",\"WITHDRAWN\",\"OBSOLETE\"]",
                      "FINALIZED", SUBJECT_STATE_AT, now.minusSeconds(7200)));
              agentWaits.flush();
              AgentWait rr = agentWaits.findById(UUID.fromString(PASSED_WAIT_ID));
              rr.subjectId = RELEASE_REQUEST_ID;
              rr.subjectQualifiedId = "contract-qits-rr-368";

              AgentWorktree blocked = attentionAgent(AGENT_BLOCKED_ID, slot, EPIC, now, 2);
              blocked.entityBlocked = true;
              blocked.blockSource = "EXPLICIT";
              blocked.blockAuthor = "contract-person";
              blocked.blockedAt = now.minusSeconds(50 * 60 + 5);
              blocked.deskAskedAt = blocked.blockedAt.minusSeconds(2 * 3600);

              AgentWorktree idle = attentionAgent(AGENT_IDLE_ID, slot, FEATURE, now, 3);
              idle.waitingSince = now.minusSeconds(42 * 60 + 5);
              // Its nudges are spent, so the scheduler leaves it idle for a person.
              idle.nudgeCount = 3;
              idle.nudgedAt = now.minusSeconds(3600);

              AgentWorktree overdue = attentionAgent(AGENT_OVERDUE_ID, slot, TASK, now, 4);
              AgentWait run =
                  subjectWait(
                      OVERDUE_WAIT_ID, overdue, "CI run 00000000 to end", "BuildStatusChanged",
                      "runId", CI_RUN_ID, "CI_RUN",
                      "[\"SUCCESS\",\"FAILED\",\"CANCELLED\",\"CONFIG_ERROR\",\"TIMED_OUT\"]",
                      "RUNNING", now.minusSeconds(100 * 60 + 5), now.minusSeconds(110 * 60));
              run.subjectId = CI_RUN_ID;
              run.subjectExpectedBy = now.minusSeconds(65 * 60 + 5);
              agentWaits.persist(run);

              AgentWorktree healthy =
                  attentionAgent(AGENT_HEALTHY_ID, slot, IMPROVEMENT_TICKET, now, 5);
              AgentWait item =
                  subjectWait(
                      ITEM_WAIT_ID, healthy, "work " + TASK_QUALIFIED_ID + " VERIFIED",
                      "EntityTransitioned", "entities[].entityId", TASK_ID, "WORK_ITEM",
                      "[\"VERIFIED\"]", "IMPLEMENTING", SUBJECT_STATE_AT, now.minusSeconds(1800));
              item.subjectId = TASK_ID;
              item.subjectQualifiedId = TASK_QUALIFIED_ID;
              agentWaits.persist(item);
              agentWaits.persist(
                  subjectWait(
                      COMMENTS_WAIT_ID, healthy, "a comment on " + TASK_QUALIFIED_ID,
                      "EntityCommented", "entityId", TASK_ID, "COMMENTS", "[]", null, null,
                      now.minusSeconds(1700)));
            });
    for (String agent :
        List.of(AGENT_PASSED_ID, AGENT_BLOCKED_ID, AGENT_IDLE_ID, AGENT_OVERDUE_ID, AGENT_HEALTHY_ID)) {
      attentions.reevaluate(UUID.fromString(agent));
    }
    Map<String, String> params = params();
    return new Setup(params, List.copyOf(ids));
  }

  /** An ACTIVE, RUNNING agent of the attention state, {@code age} minutes old: older is listed later. */
  private AgentWorktree attentionAgent(String id, Long slot, Item item, Instant now, int age) {
    agent(id, slot, AgentState.ACTIVE, AgentRunState.RUNNING, item, now.minusSeconds(60L * age));
    AgentWorktree agent = agentRows.findById(UUID.fromString(id));
    agent.repositoryId = ATTENTION_REPOSITORY_ID;
    return agent;
  }

  /** An OPEN wait of {@code agent} on the subject {@code ref} names at {@code path}, looked up already. */
  private static AgentWait subjectWait(
      String id,
      AgentWorktree agent,
      String label,
      String event,
      String path,
      String ref,
      String kind,
      String targetStates,
      String state,
      Instant stateAt,
      Instant createdAt) {
    AgentWait wait = new AgentWait();
    wait.id = UUID.fromString(id);
    wait.agentId = agent.id;
    wait.label = label;
    wait.selection = EventSelection.of(event, EventSelection.Condition.exact(path, ref)).toJson();
    wait.eventName = event;
    wait.createdAt = createdAt;
    wait.subjectKind = kind;
    wait.subjectRef = ref;
    wait.targetStates = targetStates;
    wait.subjectState = state;
    wait.subjectStateAt = stateAt;
    wait.subjectResolvedAt = createdAt;
    return wait;
  }

  // --- agents starting up (qits-2007) -------------------------------------------------------------

  /**
   * The repository the starting-up state's agents stand in: a wrapper of {@link #PROJECT_ID} of its
   * own, as {@link #ATTENTION_REPOSITORY_ID} is, for the same reason.
   */
  static final String STARTUP_REPOSITORY_ID = "00000000-0000-4000-8000-00000000001a";

  static final String AGENT_STARTUP_QUEUED_ID = "00000000-0000-4000-8000-0000000000ab";
  static final String AGENT_STARTUP_PROVISIONING_ID = "00000000-0000-4000-8000-0000000000ac";
  static final String AGENT_STARTUP_STARTING_ID = "00000000-0000-4000-8000-0000000000ad";
  static final String AGENT_STARTUP_IDLE_ID = "00000000-0000-4000-8000-0000000000ae";
  static final String AGENT_STARTUP_BUSY_ID = "00000000-0000-4000-8000-0000000000af";

  /** The fake control socket {@link #agentsStartingUp} connects its running slot's daemon with. */
  private volatile StartupDaemon startupDaemon;

  private record StartupDaemon(Long slot, WebSocketConnection connection) {}

  /**
   * The project's five ACTIVE agents, one per lifecycle phase, newest first: the improvement
   * ticket's is QUEUED with no slot (a lease of this service waits already, so none is made for it);
   * the epic's holds a slot still PROVISIONING; the bug ticket's holds a RUNNING slot whose daemon is
   * connected and has not been started there (STARTING); the feature's and the task's were started
   * in that slot and their daemon reports them IDLE and BUSY (READY). The daemon's control socket is
   * a stand-in registered with the real registry, and the two reports are frames through it, so
   * {@code activity} is what the registry answers for them. It never says Hello, so no tunnel opens
   * and a scheduler pass's start of the bug ticket's agent answers UNREACHABLE: it stays STARTING.
   */
  private Setup agentsStartingUp() {
    repositories.registerWrapper(STARTUP_REPOSITORY_ID, PROJECT_ID, "contract-qits");
    Instant now = Instant.now();
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // A lease of this service waits, so the queued agent is offered no new slot.
              Workspace waiting = new Workspace();
              waiting.repositoryId = WAITING_REPOSITORY_ID;
              waiting.workspaceId = "contract-waiting";
              waiting.branch = "contract-waiting";
              waiting.status = WorkspaceStatus.ACTIVE;
              waiting.runtimeStatus = WorkspaceRuntimeStatus.QUEUED;
              waiting.placement = eu.wohlben.qits.workspaces.entity.WorkspacePlacement.TARGET;
              waiting.leaseId = UUID.randomUUID();
              waiting.queuedAt = now.minusSeconds(600);
              workspaces.persist(waiting);

              Long provisioning = startupSlot(WorkspaceRuntimeStatus.PROVISIONING, "ws-contract-1");
              ids.add(provisioning);
              Long running = startupSlot(WorkspaceRuntimeStatus.RUNNING, "ws-contract-2");
              ids.add(running);

              AgentWorktree queued =
                  startupAgent(
                      AGENT_STARTUP_QUEUED_ID, null, AgentRunState.QUEUED, IMPROVEMENT_TICKET, now, 1);
              queued.queuedAt = now.minusSeconds(60);

              AgentWorktree placing =
                  startupAgent(
                      AGENT_STARTUP_PROVISIONING_ID, provisioning, AgentRunState.RUNNING, EPIC, now, 2);
              placing.placedAt = now.minusSeconds(110);

              AgentWorktree starting =
                  startupAgent(
                      AGENT_STARTUP_STARTING_ID, running, AgentRunState.RUNNING, BUG_TICKET, now, 3);
              starting.placedAt = now.minusSeconds(170);

              for (String[] started :
                  List.of(
                      new String[] {AGENT_STARTUP_IDLE_ID, "4"},
                      new String[] {AGENT_STARTUP_BUSY_ID, "5"})) {
                AgentWorktree agent =
                    startupAgent(
                        started[0],
                        running,
                        AgentRunState.RUNNING,
                        started[0].equals(AGENT_STARTUP_IDLE_ID) ? FEATURE : TASK,
                        now,
                        Integer.parseInt(started[1]));
                agent.placedAt = now.minusSeconds(60L * Integer.parseInt(started[1]) - 10);
                agent.startedAt = agent.placedAt.plusSeconds(5);
                agent.startedWorkspaceId = running;
              }
            });
    Long running = ids.get(1);
    WebSocketConnection connection = contractDaemonConnection(running);
    daemons.register(running, connection);
    startupDaemon = new StartupDaemon(running, connection);
    report(running, AGENT_STARTUP_IDLE_ID, DaemonProtocol.AgentState.IDLE, "Stop");
    report(running, AGENT_STARTUP_BUSY_ID, DaemonProtocol.AgentState.BUSY, "UserPromptSubmit");
    Map<String, String> params = params();
    params.put("repositoryId", STARTUP_REPOSITORY_ID);
    return new Setup(params, List.copyOf(ids));
  }

  /** An ACTIVE slot of {@link #STARTUP_REPOSITORY_ID}, on no lease. */
  private Long startupSlot(WorkspaceRuntimeStatus runtime, String label) {
    Workspace row = new Workspace();
    row.repositoryId = STARTUP_REPOSITORY_ID;
    row.workspaceId = label;
    row.status = WorkspaceStatus.ACTIVE;
    row.runtimeStatus = runtime;
    row.placement = eu.wohlben.qits.workspaces.entity.WorkspacePlacement.TARGET;
    workspaces.persist(row);
    workspaces.flush();
    return row.id;
  }

  /** An ACTIVE agent of the starting-up state, {@code age} minutes old: older is listed later. */
  private AgentWorktree startupAgent(
      String id, Long slot, AgentRunState runState, Item item, Instant now, int age) {
    agent(id, slot, AgentState.ACTIVE, runState, item, now.minusSeconds(60L * age));
    AgentWorktree agent = agentRows.findById(UUID.fromString(id));
    agent.repositoryId = STARTUP_REPOSITORY_ID;
    return agent;
  }

  /**
   * One activity frame from the slot's daemon for {@code agentId}, as a daemon at capability 12
   * sends it with nothing in flight in the background. {@code awaitingInput} is left unsaid, so the
   * frame moves no {@code waitingSince} and the agent is never a yield candidate.
   */
  private void report(Long slot, String agentId, String state, String hookEvent) {
    daemons.onMessage(
        slot,
        startupDaemon.connection(),
        new AgentActivity(
            "contract-command-" + agentId.substring(agentId.length() - 2),
            null,
            state,
            hookEvent,
            null,
            null,
            System.currentTimeMillis(),
            null,
            null,
            null,
            agentId,
            false));
  }

  /**
   * A control socket that is open and does nothing else: what the registry holds for a daemon that
   * dialled home. Anything sent over it fails, as a send on a socket nobody reads would.
   */
  private static WebSocketConnection contractDaemonConnection(Long slot) {
    String id = "contract-daemon-" + slot;
    return (WebSocketConnection)
        java.lang.reflect.Proxy.newProxyInstance(
            WebSocketConnection.class.getClassLoader(),
            new Class<?>[] {WebSocketConnection.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "id", "toString" -> id;
                  case "isOpen" -> true;
                  case "isClosed" -> false;
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  default ->
                      throw new UnsupportedOperationException(
                          "the contract daemon's socket does not " + method.getName());
                });
  }

  /** Disconnects the daemon {@link #agentsStartingUp} connected, which drops its reports too. */
  private void disconnectStartupDaemon() {
    StartupDaemon connected = startupDaemon;
    startupDaemon = null;
    if (connected == null) {
      return;
    }
    try {
      daemons.unregister(connected.slot(), connected.connection());
    } catch (RuntimeException e) {
      // The socket and the reports are dropped first; only the tunnels' notice can fail here.
    }
  }

  // --- the landing app's workspace pages (epic qits-1230) -----------------------------------------

  static final String AGENT_EPIC_ID = "00000000-0000-4000-8000-0000000000a4";
  static final String AGENT_QUEUED_ID = "00000000-0000-4000-8000-0000000000a5";

  /**
   * The wrapper's one slot, RUNNING under its lease on the target, hosting two ACTIVE
   * agents: the bug ticket's, which holds the slot and waits on a release request (a declared wait,
   * so nobody is waiting on a person), and the epic's, which yielded it. The improvement ticket's
   * agent is QUEUED: no slot was free, so it names none.
   *
   * <p>{@code withSlot} adds the slot's row id as {@code workspaceId}, for the reads addressed by
   * it. The project's reads leave it out: a listing filtered to the state's params would keep
   * every entry whose text holds that small number.
   */
  private Setup slotsHostingAgents(boolean withSlot) {
    repositories.register(WRAPPER_REPOSITORY_ID);
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.RUNNING, true);
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
    params.put("targetId", TARGET_ID);
    if (withSlot) {
      params.put("workspaceId", String.valueOf(ids.get(0)));
    }
    return new Setup(params, List.copyOf(ids));
  }

  /**
   * The bug ticket's agent, waiting on a release request, in the wrapper's slot while that slot's
   * container comes up on its target: the slot is PROVISIONING under a granted lease and its start
   * is a live technical process, past its {@code queued} segment and in its {@code container} one.
   */
  private Setup agentWhoseWorkspaceIsStarting() {
    repositories.register(WRAPPER_REPOSITORY_ID);
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.PROVISIONING, true);
              ids.add(slot);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.RUNNING);
              openWait();
            });
    TechnicalProcess start =
        technicalProcesses.begin(WRAPPER_REPOSITORY_ID, SLOT_LABEL, ids.get(0));
    start.openSegment("queued");
    start.appendLine("queued", "Asking for a workspace slot.");
    start.settleSegment("queued", true);
    start.openSegment("container");
    start.appendLine("container", "pulling the workspace image");
    startingProcess = start;
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_ACTIVE_ID);
    params.put("bugTicketId", BUG_TICKET_ID);
    params.put("bugTicketQualifiedId", BUG_TICKET_QUALIFIED_ID);
    params.put("repositoryId", WRAPPER_REPOSITORY_ID);
    params.put("targetId", TARGET_ID);
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
              // Its item is VERIFIED: a release of an open item's branch leaves its agent ACTIVE
              // and names nobody (qits-1605), and this state is the one that names the agent.
              agentRows.findById(UUID.fromString(AGENT_ACTIVE_ID)).entityStatus = "VERIFIED";
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

  /** The workspace token a running slot's lease carries. */
  static final String SLOT_TOKEN_ID = "00000000-0000-4000-8000-000000000022";

  /** When the resolved slot's agent was created, worked and was removed: fixed, so lines are too. */
  static final Instant ARCHIVED_AGENT_CREATED = Instant.parse("2026-01-01T00:00:00Z");

  static final Instant ARCHIVED_SESSION_AT = Instant.parse("2026-01-01T00:05:00Z");
  static final Instant ARCHIVED_AGENT_REMOVED = Instant.parse("2026-01-01T01:00:00Z");

  /** The stub daemon {@link #AN_AGENT_WITH_LEFTOVER_WORK} answers from, started once per JVM. */
  private static volatile HttpServer leftoverDaemon;

  /**
   * The bug ticket's agent, RUNNING in the wrapper's slot, which runs under its lease on the
   * target. With {@code draft}, it holds a saved prompt draft. With {@code leftover}, the
   * slot's daemon answers through its tunnel and its cleanup check finds work that never reached
   * the git host (a dirty submodule, an unpushed commit on the wrapper branch); without, the slot's
   * daemon is not connected — as it is for every slot in this suite.
   */
  private Setup agentInARunningSlot(boolean draft, boolean leftover) {
    repositories.register(WRAPPER_REPOSITORY_ID);
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.RUNNING, true);
              ids.add(slot);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.RUNNING);
              // Started there, so its worktree may be on the slot and the guard must ask about it:
              // one that never started passes recreate untouched (qits-1750).
              agentRows.findById(UUID.fromString(AGENT_ACTIVE_ID)).startedWorkspaceId = slot;
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

  /** The daemon command a mid-turn agent's harness runs as. */
  static final String MID_TURN_COMMAND_ID = "contract-command-1";

  /**
   * The bug ticket's agent, RUNNING in the wrapper's slot with its harness started (a command id),
   * and the slot's daemon answering through its tunnel: the stub {@link #leftoverDaemonPort} daemon,
   * which takes an interrupt.
   */
  private Setup agentInTheMiddleOfATurn() {
    repositories.register(WRAPPER_REPOSITORY_ID);
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.RUNNING, true);
              ids.add(slot);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.RUNNING);
              agentRows.findById(UUID.fromString(AGENT_ACTIVE_ID)).commandId = MID_TURN_COMMAND_ID;
            });
    long slot = ids.get(0);
    StubDaemonTunnels.install(vertx, leftoverDaemonPort(), row -> row == slot);
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_ACTIVE_ID);
    params.put("bugTicketId", BUG_TICKET_ID);
    params.put("workspaceId", String.valueOf(slot));
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
   * daemon shipped into the transcript store: one exchange on the bug ticket's wrapper branch. The
   * agent's life and the session's lines carry fixed instants, so the transcript is the same on
   * every run; {@link #cleanUp} empties the agent's store directory, so no other state lists it.
   */
  private Setup resolvedSlotWithAnArchivedSession() {
    repositories.register(WRAPPER_REPOSITORY_ID);
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.STOPPED, false);
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
    String lines =
        String.join(
                "\n",
                turn("user", "Fix the rounding in the invoice totals.", wrapperBranch, ARCHIVED_SESSION_AT),
                turn(
                    "assistant",
                    "Rounded each line before summing; the totals match now.",
                    wrapperBranch,
                    ARCHIVED_SESSION_AT.plusSeconds(60)))
            + "\n";
    try {
      if (!shippedTranscripts
          .store()
          .append(UUID.fromString(AGENT_ACTIVE_ID), ARCHIVED_SESSION_ID, "main", 0, lines)
          .appended()) {
        throw new IllegalStateException("The archived session's store file was not empty");
      }
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

  /** The edge plane a start composes the lease spec from: a public domain. */
  private static void anEdgePlane() {
    QuarkusMock.installMockForType(
        new WorkspaceAddressPlanes() {
          @Override
          public WorkspaceAddressPlane plane() {
            return WorkspaceAddressPlane.of(
                "targets.example.test", List.of("registry.dev.localhost:8080"));
          }
        },
        WorkspaceAddressPlanes.class);
  }

  /**
   * The wrapper's slot, {@code runtime}, hosting the bug ticket's agent: RUNNING under its lease on
   * the target (qits-system holds the lease RUNNING, so a stop leaves it ENDING), or STOPPED with no
   * lease and no token (so a start mints one and asks for a lease, which waits QUEUED).
   */
  private Setup slotOnATarget(WorkspaceRuntimeStatus runtime) {
    anEdgePlane();
    repositories.register(WRAPPER_REPOSITORY_ID);
    boolean running = runtime == WorkspaceRuntimeStatus.RUNNING;
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(runtime, running);
              ids.add(slot);
              agent(
                  AGENT_ACTIVE_ID,
                  slot,
                  AgentState.ACTIVE,
                  running ? AgentRunState.RUNNING : AgentRunState.YIELDED);
            });
    if (running) {
      seedLease(ids.get(0), TargetPlacement.LeaseState.RUNNING);
    }
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_ACTIVE_ID);
    params.put("repositoryId", WRAPPER_REPOSITORY_ID);
    params.put("workspaceId", String.valueOf(ids.get(0)));
    return new Setup(params, List.copyOf(ids));
  }

  /**
   * The wrapper's slot, STOPPED, still naming the lease it was stopped from (qits-system reported
   * it ENDING), with the bug ticket's agent in it — and qits-system cannot be asked anything: a start
   * cannot end the old lease, nor a stop the current one.
   */
  private Setup slotQitsSystemCannotBeAskedAbout() {
    anEdgePlane();
    repositories.register(WRAPPER_REPOSITORY_ID);
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.STOPPED, true);
              ids.add(slot);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.YIELDED);
            });
    seedLease(ids.get(0), TargetPlacement.LeaseState.ENDING);
    targets.unreachable(
        new TargetPlacement.LeaseRefused(
            -1, null, "qits-system could not be asked: Connection refused", null));
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_ACTIVE_ID);
    params.put("repositoryId", WRAPPER_REPOSITORY_ID);
    params.put("workspaceId", String.valueOf(ids.get(0)));
    return new Setup(params, List.copyOf(ids));
  }

  /** The slot's lease, as qits-system holds it, granted on the target. */
  private void seedLease(Long slot, TargetPlacement.LeaseState state) {
    targets.seed(
        new TargetPlacement.Lease(
            UUID.fromString(LEASE_ID),
            String.valueOf(slot),
            state,
            UUID.fromString(TARGET_ID),
            null,
            null,
            null,
            null,
            Instant.now()));
  }

  /**
   * The port of a stub workspace daemon whose cleanup check finds leftover work for any agent, which
   * takes an interrupt for any agent (200), and which knows no other agent verb (404, as a daemon
   * answers for an agent it does not know).
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
                          if (request.method() == io.vertx.core.http.HttpMethod.POST
                              && request.path().endsWith("/interrupt")) {
                            request
                                .response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json")
                                .end("{}");
                            return;
                          }
                          request
                              .response()
                              .setStatusCode(check ? 200 : 404)
                              .putHeader("Content-Type", "application/json")
                              .end(
                                  check
                                      ? "{\"clean\":false,\"dirty\":[\"qits-ci-service\"],"
                                          + "\"unpushed\":[{\"repository\":\"wrapper\","
                                          + "\"branch\":\"ticket/" + BUG_TICKET_QUALIFIED_ID + "\","
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

  /** A slot workspace of the wrapper, on no lease yet, naming no branch (qits-1152). */
  private Long slot(WorkspaceRuntimeStatus runtime) {
    return slot(runtime, false);
  }

  /**
   * A slot workspace of the wrapper; with {@code leased}, under {@link #LEASE_ID} on {@link
   * #TARGET_ID}, holding the lease's token.
   */
  private Long slot(WorkspaceRuntimeStatus runtime, boolean leased) {
    Workspace row = new Workspace();
    if (leased) {
      row.leaseId = UUID.fromString(LEASE_ID);
      row.targetId = UUID.fromString(TARGET_ID);
      row.commissionedTokenId = SLOT_TOKEN_ID;
      row.commissionedTokenSubject = "contract-workspace-token";
      row.commissionedToken = "qits_tok_contract-workspace";
    }
    row.repositoryId = WRAPPER_REPOSITORY_ID;
    row.workspaceId = SLOT_LABEL;
    row.status = WorkspaceStatus.ACTIVE;
    row.runtimeStatus = runtime;
    row.placement = eu.wohlben.qits.workspaces.entity.WorkspacePlacement.TARGET;
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
      new Item(BUG_TICKET_ID, BUG_TICKET_QUALIFIED_ID, false, "Invoice totals are off by one cent");
  static final Item EPIC =
      new Item(EPIC_ID, EPIC_QUALIFIED_ID, true, "Export invoices for the accountants");
  static final Item IMPROVEMENT_TICKET =
      new Item(
          IMPROVEMENT_TICKET_ID, IMPROVEMENT_TICKET_QUALIFIED_ID, false, "Remember the last export format");

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
    // A regular workspace runs on a target: an ACTIVE regular DIRECT row is refused (V15, qits-780).
    row.placement = eu.wohlben.qits.workspaces.entity.WorkspacePlacement.TARGET;
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

  // --- the doors no state recorded yet (qits-1937) ------------------------------------------------

  /** The OPEN wait {@link #A_PROJECT_DESK_WITH_AN_OPEN_WAIT} seeds for {@link #PROJECT_ID}'s desk. */
  static final String DESK_WAIT_ID = "00000000-0000-4000-8000-0000000000b6";

  /** The repository {@link #A_REPOSITORY_WITH_WORKSPACE_HISTORY} keeps its one resolved row in. */
  static final String HISTORY_REPOSITORY_ID = "00000000-0000-4000-8000-000000000019";

  /** The attachment {@link #AN_AGENT_WITH_A_PROMPT_ATTACHMENT} seeds, and one it never wrote. */
  static final String PROMPT_ATTACHMENT_ID = "00000000-0000-4000-8000-0000000000f1";

  static final String MISSING_PROMPT_ATTACHMENT_ID = "00000000-0000-4000-8000-0000000000f2";

  /** A 1×1 transparent PNG, as {@code PromptAttachmentFixtures} holds it: the sniff wants real bytes. */
  static final String ONE_PIXEL_PNG =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5Erk"
          + "Jggg==";

  /**
   * {@link #PROJECT_ID}'s front desk waiting on a release request (qits-1618): one OPEN desk wait,
   * two hours to run, naming no agent. {@link #cleanUp} removes every desk wait of the project, so
   * the one a recorded create declares goes too.
   */
  private Setup deskWithAnOpenWait() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWait wait = new AgentWait();
              wait.id = UUID.fromString(DESK_WAIT_ID);
              wait.deskProjectId = UUID.fromString(PROJECT_ID);
              wait.waiterSubject = CONTRACT_CALLER;
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
            });
    Map<String, String> params = new TreeMap<>();
    params.put("projectId", PROJECT_ID);
    params.put("releaseRequestId", RELEASE_REQUEST_ID);
    params.put("waitId", DESK_WAIT_ID);
    return new Setup(params, List.of());
  }

  /**
   * One workspace that flowed through {@link #HISTORY_REPOSITORY_ID}, a repository of its own so its
   * history lists nothing another state wrote: the bug ticket's, INTEGRATED, with its narrative.
   */
  private Setup repositoryWithWorkspaceHistory() {
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Workspace row = new Workspace();
              row.repositoryId = HISTORY_REPOSITORY_ID;
              row.workspaceId = "ticket-invoice-totals-are-off-by-one-cent";
              row.branch = TICKET_BRANCH;
              row.status = WorkspaceStatus.INTEGRATED;
              row.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
              row.placement = eu.wohlben.qits.workspaces.entity.WorkspacePlacement.TARGET;
              row.workId = BUG_TICKET_ID;
              row.entityId = BUG_TICKET_QUALIFIED_ID;
              row.ticketId = BUG_TICKET_ID;
              row.preamble = "Fix the rounding in the invoice totals.";
              row.result = "Rounded each line before summing; integrated.";
              row.resolvedAt = Instant.now();
              workspaces.persist(row);
              workspaces.flush();
              ids.add(row.id);
            });
    Map<String, String> params = new TreeMap<>();
    params.put("repositoryId", HISTORY_REPOSITORY_ID);
    params.put("workspaceId", String.valueOf(ids.get(0)));
    return new Setup(params, List.copyOf(ids));
  }

  /**
   * The bug ticket's ACTIVE agent, holding a slot whose lease waits, with one pasted PNG beside its
   * prompt at {@link #PROMPT_ATTACHMENT_ID}. The attachments go with the agent's row (on delete
   * cascade), so the one a recorded add makes goes too.
   */
  private Setup agentWithAPromptAttachment() {
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Long slot = slot(WorkspaceRuntimeStatus.QUEUED);
              ids.add(slot);
              agent(AGENT_ACTIVE_ID, slot, AgentState.ACTIVE, AgentRunState.RUNNING);
              WorkspacePromptAttachment attachment = new WorkspacePromptAttachment();
              attachment.id = PROMPT_ATTACHMENT_ID;
              attachment.agentId = UUID.fromString(AGENT_ACTIVE_ID);
              attachment.mimeType = "image/png";
              attachment.label = "Pasted image 1";
              attachment.source = PromptAttachmentSource.PASTE;
              attachment.bytes = java.util.Base64.getDecoder().decode(ONE_PIXEL_PNG);
              promptAttachments.persist(attachment);
            });
    Map<String, String> params = new TreeMap<>();
    params.put("agentId", AGENT_ACTIVE_ID);
    params.put("attachmentId", PROMPT_ATTACHMENT_ID);
    params.put("missingAttachmentId", MISSING_PROMPT_ATTACHMENT_ID);
    return new Setup(params, List.copyOf(ids));
  }
}
