package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.util.function.Supplier;

/**
 * A regular workspace on the direct path, as the estate still holds them (qits-774). No create
 * writes one any more — a regular workspace is RUNNER from its first commit — but the rows created
 * before that keep working, and the tests proving the DIRECT ladder need such a row. This turns a
 * freshly created regular row into one: DIRECT, STOPPED, on no runner, unqueued, and without any
 * workspace token its RUNNER create minted (a DIRECT row never held one).
 */
public final class LegacyDirectRows {

  private LegacyDirectRows() {}

  /**
   * Runs {@code create} — any of {@link WorkspaceService}'s regular creates — and answers the row it
   * wrote rewritten as a pre-qits-774 regular DIRECT row, read afresh.
   */
  public static Workspace direct(Supplier<Workspace> create) {
    Workspace created = create.get();
    demote(created.id);
    return QuarkusTransaction.requiringNew().call(() -> repository().findById(created.id));
  }

  /** Rewrites row {@code rowId} as a pre-qits-774 regular DIRECT row, in a transaction of its own. */
  public static void demote(Long rowId) {
    demote(repository(), rowId);
  }

  /** {@link #demote(Long)} through the caller's repository. */
  public static void demote(WorkspaceRepository workspaces, Long rowId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var row = workspaces.findById(rowId);
              row.placement = WorkspacePlacement.DIRECT;
              row.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
              row.runtimeError = null;
              row.runnerId = null;
              row.queuedAt = null;
              row.commissionedTokenId = null;
              row.commissionedTokenSubject = null;
              row.commissionedToken = null;
            });
  }

  private static WorkspaceRepository repository() {
    return Arc.container().instance(WorkspaceRepository.class).get();
  }
}
