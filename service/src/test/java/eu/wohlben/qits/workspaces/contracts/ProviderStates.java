package eu.wohlben.qits.workspaces.contracts;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestGit;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceAddressPlanes;
import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.entity.Workspace;
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

  public static final String A_REPOSITORY_WITH_A_BRANCH_FOR_A_TICKET =
      "a repository with a branch for a ticket";
  public static final String A_WORKSPACE_STANDING_ON_A_TICKETS_BRANCH =
      "a workspace standing on a ticket's branch";
  public static final String A_WORKSPACE_STANDING_ON_A_RELEASED_BRANCH =
      "a workspace standing on a released branch";
  public static final String REPOSITORIES_WITH_MERGED_BRANCHES =
      "repositories with merged branches";
  public static final String A_CONFIGURED_WORKSPACE_IMAGE = "a configured workspace image";
  public static final String NO_RUNNERS = "no runners";
  public static final String AN_UNREGISTERED_RUNNER = "an unregistered runner";
  public static final String A_CONNECTED_RUNNER = "a connected runner";
  public static final String A_QUARANTINED_RUNNER = "a quarantined runner";

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

  /**
   * What a state answers.
   *
   * @param params the values a path template and a pact take, sorted by name
   * @param rowIds the generated row ids of the workspaces the state wrote, in the order it wrote
   *     them — frozen to 1, 2, 3 … in that order
   */
  public record Setup(Map<String, String> params, List<Long> rowIds) {}

  @Inject WorkspaceRepository workspaces;
  @Inject WorkspaceService workspaceService;
  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceRunners runners;
  @Inject WorkspaceRunnerRegistry runnerRegistry;

  @ConfigProperty(name = "qits.test.origins-dir")
  String originsDir;

  @ConfigProperty(name = "qits.workspaces.data-dir")
  String dataDir;

  /** The connection {@link #A_CONNECTED_RUNNER} admitted, closed again by {@link #cleanUp}. */
  private volatile WorkspaceRunnerRegistry.Session runnerSession;

  public Set<String> names() {
    return Set.of(
        A_PROJECT_WITH_WORKSPACES_BOUND_TO_WORK_ITEMS,
        A_WORK_ITEM_WITH_NO_WORKSPACES,
        NO_WORK_ITEM_HAS_AN_OPEN_WORKSPACE,
        A_REPOSITORY_WITH_A_BRANCH_FOR_A_TICKET,
        A_WORKSPACE_STANDING_ON_A_TICKETS_BRANCH,
        A_WORKSPACE_STANDING_ON_A_RELEASED_BRANCH,
        REPOSITORIES_WITH_MERGED_BRANCHES,
        A_CONFIGURED_WORKSPACE_IMAGE,
        NO_RUNNERS,
        AN_UNREGISTERED_RUNNER,
        A_CONNECTED_RUNNER,
        A_QUARANTINED_RUNNER);
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
      case A_REPOSITORY_WITH_A_BRANCH_FOR_A_TICKET -> branchForATicket();
      case A_WORKSPACE_STANDING_ON_A_TICKETS_BRANCH -> standingOnATicketsBranch();
      case A_WORKSPACE_STANDING_ON_A_RELEASED_BRANCH -> standingOnAReleasedBranch();
      case REPOSITORIES_WITH_MERGED_BRANCHES -> mergedBranches();
      case A_CONFIGURED_WORKSPACE_IMAGE -> new Setup(new TreeMap<>(), List.of());
      case NO_RUNNERS -> noRunners();
      case AN_UNREGISTERED_RUNNER -> unregisteredRunner();
      case A_CONNECTED_RUNNER -> connectedRunner();
      case A_QUARANTINED_RUNNER -> quarantinedRunner();
      default -> throw new IllegalArgumentException("No provider state named '" + state + "'");
    };
  }

  /** Removes every row a state writes, and tears down every workspace a git-backed state made. */
  public void cleanUp() {
    tearDownGitWorkspaces();
    removeRunners();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              workspaces.delete("workId in ?1", List.copyOf(WORK_IDS));
              workspaces.delete(
                  "repositoryId = ?1 and workspaceId = ?2",
                  WRAPPER_REPOSITORY_ID,
                  HAND_MADE_LABEL);
              workspaces.delete("repositoryId in ?1", GIT_REPOSITORIES);
            });
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

  /** The bug ticket's id, its qualified id and the branch a dispatch for it names. */
  private static Map<String, String> ticketParams(String repositoryId) {
    Map<String, String> params = new TreeMap<>();
    params.put("branch", TICKET_BRANCH);
    params.put("repositoryId", repositoryId);
    params.put("ticketId", BUG_TICKET_ID);
    params.put("ticketQualifiedId", "contract-00000001-10");
    return params;
  }

  /**
   * A repository with a git origin and no workspace: a dispatch for the bug ticket creates one on
   * {@link #TICKET_BRANCH}. Its container is the fake runtime's and no daemon ever answers, so the
   * answer is a fresh workspace with the launch {@code SCHEDULED}.
   */
  private Setup branchForATicket() {
    // A fresh dispatch's row is RUNNER, and queueing it needs a public domain (the edge plane).
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.planesWithDomain(WorkspaceRunnerAddressesFixture.DOMAIN),
        WorkspaceAddressPlanes.class);
    buildOrigins();
    deleteOriginBranch(DISPATCH_REPOSITORY_ID, TICKET_BRANCH);
    return new Setup(ticketParams(DISPATCH_REPOSITORY_ID), List.of());
  }

  /**
   * The bug ticket's ACTIVE workspace on {@link #TICKET_BRANCH} of the wrapper, QUEUED for a
   * workspace runner: a turn waits for one, and the subject's facts are stored on the row while no
   * daemon takes them live.
   */
  private Setup standingOnATicketsBranch() {
    List<Long> ids = new ArrayList<>();
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ids.add(
                    write(
                        BUG_TICKET_ID,
                        "contract-00000001-10",
                        TICKET_BRANCH,
                        WorkspaceStatus.ACTIVE,
                        WorkspaceRuntimeStatus.QUEUED)));
    return new Setup(ticketParams(WRAPPER_REPOSITORY_ID), List.copyOf(ids));
  }

  /** An ACTIVE workspace forked off {@code master} on {@link #TICKET_BRANCH}, which a release took. */
  private Setup standingOnAReleasedBranch() {
    buildOrigins();
    List<Long> ids = new ArrayList<>();
    String label = WorkspaceService.toWorkspaceSlug(TICKET_BRANCH);
    workspaceService.createWorkspace(
        RELEASED_REPOSITORY_ID, label, "master", TICKET_BRANCH, null, true, false, true);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ids.add(
                    workspaces
                        .findActiveByRepositoryAndBranch(RELEASED_REPOSITORY_ID, TICKET_BRANCH)
                        .orElseThrow()
                        .id));
    Map<String, String> params = new TreeMap<>();
    params.put("branch", TICKET_BRANCH);
    params.put("repositoryId", RELEASED_REPOSITORY_ID);
    params.put("sha", RELEASED_SHA);
    params.put("version", RELEASED_VERSION);
    return new Setup(params, List.copyOf(ids));
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

  /** {@code localhost}, declared and never registered. */
  private Setup unregisteredRunner() {
    commissioningRunners();
    declareRunner();
    return new Setup(runnerParams(), List.of());
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

  /** Deletes a branch from a git origin, if it is there. */
  private void deleteOriginBranch(String repoId, String branch) {
    try {
      TestGit.exec(origin(repoId), "git", "branch", "-D", branch);
    } catch (Exception absent) {
      // Not there: nothing to delete.
    }
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
