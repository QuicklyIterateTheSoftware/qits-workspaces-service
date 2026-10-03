package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * At most one ACTIVE workspace per work item (qits-112), kept by {@code uq_workspace_active_work}
 * ({@code V11}). The first tests write rows directly, so they prove the index and not a service
 * check. The last one proves the create path refuses before it pushes a branch.
 */
@QuarkusTest
public class OneActiveWorkspacePerWorkItemTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaceRepository;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /** Rows written directly, abandoned afterwards so no other test meets an orphan ACTIVE row. */
  private final List<Long> written = new ArrayList<>();

  @AfterEach
  void abandonWritten() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                written.forEach(
                    id -> workspaceRepository.findByIdOptional(id)
                        .ifPresent(w -> w.status = WorkspaceStatus.ABANDONED)));
    written.clear();
  }

  @Test
  public void refusesASecondActiveWorkspaceForOneWorkItem() {
    String workId = UUID.randomUUID().toString();
    insert(workId, WorkspaceStatus.ACTIVE);

    assertThrows(
        RuntimeException.class,
        () -> insert(workId, WorkspaceStatus.ACTIVE),
        "the index let a second ACTIVE workspace bind the same work item");
    assertEquals(1, activeFor(workId));
  }

  @Test
  public void allowsResolvedDuplicates() {
    String workId = UUID.randomUUID().toString();
    insert(workId, WorkspaceStatus.INTEGRATED);
    insert(workId, WorkspaceStatus.INTEGRATED);
    insert(workId, WorkspaceStatus.ABANDONED);
    insert(workId, WorkspaceStatus.ABANDONED);
    insert(workId, WorkspaceStatus.ACTIVE);

    assertEquals(1, activeFor(workId));
  }

  @Test
  public void leavesWorkspacesWithoutAWorkItemAlone() {
    long before = workspaceRepository.count("workId is null and status = ?1", WorkspaceStatus.ACTIVE);
    insert(null, WorkspaceStatus.ACTIVE);
    insert(null, WorkspaceStatus.ACTIVE);

    long after = workspaceRepository.count("workId is null and status = ?1", WorkspaceStatus.ACTIVE);
    assertEquals(before + 2, after);
  }

  /** In another repository too: a work item has one target, so one active workspace in all. */
  @Test
  public void createRefusesASecondActiveWorkspaceForOneWorkItem() throws Exception {
    String repoOne = TestOrigin.create(dataDir);
    String repoTwo = TestOrigin.create(dataDir);
    repositories.register(repoOne);
    repositories.register(repoTwo);
    String workId = UUID.randomUUID().toString();
    WorkspaceSubject subject = new WorkspaceSubject(null, null, null, workId);

    workspaceService.createWorkspace(
        repoOne, "alpha", null, "work-a", null, false, false, subject);

    ConflictException rejected =
        assertThrows(
            ConflictException.class,
            () ->
                workspaceService.createWorkspace(
                    repoTwo, "beta", null, "work-b", null, false, false, subject));
    assertEquals(409, rejected.statusCode());
    assertTrue(rejected.getMessage().contains(workId), rejected.getMessage());
    assertEquals(1, activeFor(workId));
  }

  private void insert(String workId, WorkspaceStatus status) {
    Long id =
        QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Workspace workspace = new Workspace();
              String label = "w" + UUID.randomUUID().toString().substring(0, 8);
              workspace.workspaceId = label;
              workspace.repositoryId = "repo-" + label;
              workspace.branch = label;
              workspace.status = status;
              workspace.workId = workId;
              workspaceRepository.persist(workspace);
              return workspace.id;
            });
    written.add(id);
  }

  private long activeFor(String workId) {
    return workspaceRepository.count(
        "workId = ?1 and status = ?2", workId, WorkspaceStatus.ACTIVE);
  }
}
