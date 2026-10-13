package eu.wohlben.qits.workspaces.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkspaceRepository#countHoldingRunnerSlots} (qits-1228): only the RUNNER rows that hold a
 * runner slot or wait for one count against the runners' capacity. The suite shares one database,
 * so the count is read as a difference over the rows this test writes.
 */
@QuarkusTest
public class WorkspaceRepositoryRunnerSlotsTest {

  @Inject WorkspaceRepository workspaces;

  private final String repoId = "slots-" + UUID.randomUUID().toString().substring(0, 8);
  private final List<Long> rows = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                rows.forEach(
                    id ->
                        workspaces
                            .findByIdOptional(id)
                            .ifPresent(w -> w.status = WorkspaceStatus.ABANDONED)));
    rows.clear();
  }

  private void row(
      WorkspaceStatus status,
      WorkspacePlacement placement,
      WorkspaceRuntimeStatus runtimeStatus,
      UUID runnerId) {
    Long id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace row = new Workspace();
                  row.repositoryId = repoId;
                  row.workspaceId = "ws-" + UUID.randomUUID().toString().substring(0, 8);
                  row.status = status;
                  row.placement = placement;
                  row.admin = placement == WorkspacePlacement.DIRECT;
                  row.runtimeStatus = runtimeStatus;
                  row.runnerId = runnerId;
                  workspaces.persist(row);
                  return row.id;
                });
    rows.add(id);
  }

  private long count() {
    return QuarkusTransaction.requiringNew().call(() -> workspaces.countHoldingRunnerSlots());
  }

  @Test
  void onlyRunnerRowsInUseOrQueuedHoldASlot() {
    long before = count();
    UUID runner = UUID.randomUUID();

    row(WorkspaceStatus.ACTIVE, WorkspacePlacement.RUNNER, WorkspaceRuntimeStatus.QUEUED, null);
    row(WorkspaceStatus.ACTIVE, WorkspacePlacement.RUNNER, WorkspaceRuntimeStatus.QUEUED, runner);
    row(
        WorkspaceStatus.ACTIVE,
        WorkspacePlacement.RUNNER,
        WorkspaceRuntimeStatus.PROVISIONING,
        runner);
    row(WorkspaceStatus.ACTIVE, WorkspacePlacement.RUNNER, WorkspaceRuntimeStatus.RUNNING, runner);
    assertEquals(before + 4, count(), "queued, with or without a runner, provisioning and running");

    row(WorkspaceStatus.ACTIVE, WorkspacePlacement.RUNNER, WorkspaceRuntimeStatus.STOPPED, null);
    row(WorkspaceStatus.ACTIVE, WorkspacePlacement.RUNNER, WorkspaceRuntimeStatus.STOPPED, runner);
    row(WorkspaceStatus.ACTIVE, WorkspacePlacement.RUNNER, WorkspaceRuntimeStatus.FAILED, null);
    row(WorkspaceStatus.ACTIVE, WorkspacePlacement.RUNNER, WorkspaceRuntimeStatus.FAILED, runner);
    row(WorkspaceStatus.ACTIVE, WorkspacePlacement.DIRECT, WorkspaceRuntimeStatus.RUNNING, null);
    row(
        WorkspaceStatus.ABANDONED,
        WorkspacePlacement.RUNNER,
        WorkspaceRuntimeStatus.RUNNING,
        runner);
    row(
        WorkspaceStatus.INTEGRATED,
        WorkspacePlacement.RUNNER,
        WorkspaceRuntimeStatus.QUEUED,
        null);
    assertEquals(
        before + 4, count(), "stopped, failed, direct and resolved rows hold no runner slot");
  }
}
