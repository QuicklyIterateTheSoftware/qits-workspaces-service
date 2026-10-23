package eu.wohlben.qits.workspaces.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeTargetPlacement;
import eu.wohlben.qits.workspaces.control.TargetPlacement;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * qits-system's {@code TargetLeaseChanged} and the lease reconcile (epic qits-1481): an event of
 * this service's WORKSPACE leases moves the row that names its lease; another owner's, or another
 * slot type's, moves nothing; and the reconcile ends a row whose lease qits-system no longer lists.
 */
@QuarkusTest
public class TargetLeaseEventsTest {

  @Inject TargetLeaseEvents events;
  @Inject FakeTargetPlacement placement;
  @Inject WorkspaceRepository workspaces;

  private Long rowId;

  @AfterEach
  void cleanUp() {
    placement.reset();
    if (rowId != null) {
      QuarkusTransaction.requiringNew().run(() -> workspaces.deleteById(rowId));
    }
  }

  private UUID queuedRow() {
    UUID leaseId = UUID.randomUUID();
    rowId =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace row = new Workspace();
                  row.repositoryId = "events-" + UUID.randomUUID().toString().substring(0, 8);
                  row.workspaceId = "ws-events";
                  row.status = WorkspaceStatus.ACTIVE;
                  row.runtimeStatus = WorkspaceRuntimeStatus.QUEUED;
                  row.placement = WorkspacePlacement.TARGET;
                  row.leaseId = leaseId;
                  workspaces.persist(row);
                  return row.id;
                });
    return leaseId;
  }

  private Workspace read() {
    return QuarkusTransaction.requiringNew().call(() -> workspaces.findById(rowId));
  }

  private TargetLeaseChanged changed(UUID leaseId, String owner, String slotType, String state) {
    return new TargetLeaseChanged(
        UUID.randomUUID(),
        leaseId.toString(),
        owner,
        String.valueOf(rowId),
        slotType,
        UUID.randomUUID().toString(),
        state,
        null,
        null,
        Instant.now());
  }

  @Test
  public void aGrantOfThisServicesLeaseMovesItsRow() {
    UUID leaseId = queuedRow();

    events.onEvent(changed(leaseId, "qits-workspaces", "WORKSPACE", "GRANTED"));

    Workspace row = read();
    assertEquals(WorkspaceRuntimeStatus.PROVISIONING, row.runtimeStatus);
    assertTrue(row.targetId != null, "the target it was granted on");
  }

  @Test
  public void anotherOwnersOrAnotherSlotTypesEventMovesNothing() {
    UUID leaseId = queuedRow();

    events.onEvent(changed(leaseId, "qits-ci", "WORKSPACE", "GRANTED"));
    events.onEvent(changed(leaseId, "qits-workspaces", "CI", "GRANTED"));

    assertEquals(WorkspaceRuntimeStatus.QUEUED, read().runtimeStatus);
  }

  @Test
  public void theReconcileEndsARowWhoseLeaseIsNoLongerListed() {
    queuedRow();

    List<Long> changed = events.reconcile();

    assertTrue(changed.contains(rowId), changed.toString());
    Workspace row = read();
    assertEquals(WorkspaceRuntimeStatus.STOPPED, row.runtimeStatus);
    assertNull(row.leaseId);
  }

  @Test
  public void anUnreadableListingMovesNothing() {
    queuedRow();
    placement.unreachable(new TargetPlacement.LeaseRefused(-1, null, "down", null));

    assertEquals(List.of(), events.reconcile());
    assertEquals(WorkspaceRuntimeStatus.QUEUED, read().runtimeStatus);
  }
}
