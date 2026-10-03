package eu.wohlben.qits.workspaces.contracts;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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

  static final String PROJECT_ID = "00000000-0000-4000-8000-00000000000f";
  static final String BUG_TICKET_ID = "00000000-0000-4000-8000-000000000001";
  static final String EPIC_ID = "00000000-0000-4000-8000-000000000006";
  static final String FEATURE_ID = "00000000-0000-4000-8000-000000000007";
  static final String IMPROVEMENT_TICKET_ID = "00000000-0000-4000-8000-00000000000b";
  static final String TASK_ID = "00000000-0000-4000-8000-000000000011";
  static final String WRAPPER_REPOSITORY_ID = "00000000-0000-4000-8000-000000000013";

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

  public Set<String> names() {
    return Set.of(
        A_PROJECT_WITH_WORKSPACES_BOUND_TO_WORK_ITEMS,
        A_WORK_ITEM_WITH_NO_WORKSPACES,
        NO_WORK_ITEM_HAS_AN_OPEN_WORKSPACE);
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
      default -> throw new IllegalArgumentException("No provider state named '" + state + "'");
    };
  }

  /** Removes every row a state writes. */
  public void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              workspaces.delete("workId in ?1", List.copyOf(WORK_IDS));
              workspaces.delete(
                  "repositoryId = ?1 and workspaceId = ?2",
                  WRAPPER_REPOSITORY_ID,
                  HAND_MADE_LABEL);
            });
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
