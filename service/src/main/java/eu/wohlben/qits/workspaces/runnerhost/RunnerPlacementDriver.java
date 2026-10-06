package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.workspaces.control.RunnerPlacement;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.error.RunnerRefusals;
import eu.wohlben.qits.workspacesrunner.protocol.Delete;
import eu.wohlben.qits.workspacesrunner.protocol.Stop;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The domain's {@link RunnerPlacement} port, over the runner sockets (qits-851, qits-853): how
 * {@code WorkspaceService} reaches the runner holding a RUNNER workspace.
 *
 * <ul>
 *   <li>{@link #stop} and {@link #delete} send the frame to the runner's current connection and
 *       wait up to {@link RunnerPlacement#REPLY_DEADLINE} for {@code stopped}/{@code deleted}: no
 *       connection is 409 {@code RUNNER_UNAVAILABLE}, no answer in time is 504 {@code
 *       RUNNER_TIMEOUT}, and either way the caller writes nothing.
 *   <li>{@link #released} sends {@code delete} and waits for nothing: a resolution never waits on
 *       a runner, and an offline one drops the row at its next {@code estate}.
 *   <li>{@link #backlogChanged}, {@link #estateChanged} and {@link #released} are notifications,
 *       which the port says may be made inside the transaction that wrote the row. Each runs once
 *       that transaction has committed — on the registry's own thread, which reads the rows in a
 *       transaction of its own — and not at all if it rolled back; with no transaction it runs now.
 * </ul>
 */
@ApplicationScoped
public class RunnerPlacementDriver implements RunnerPlacement {

  private static final Logger LOG = Logger.getLogger(RunnerPlacementDriver.class);

  @Inject WorkspaceRunnerRegistry registry;

  @Inject TransactionSynchronizationRegistry transactions;

  @Override
  public void backlogChanged(Workspace row) {
    UUID runnerId = row.runnerId;
    afterCommit(() -> registry.backlogChanged(runnerId));
  }

  @Override
  public void stop(Workspace row) {
    route(row, new Stop(row.id), "stop", "stop");
  }

  @Override
  public void delete(Workspace row) {
    route(row, new Delete(row.id), "delete", "delete its container");
  }

  @Override
  public void released(Workspace row) {
    UUID runnerId = row.runnerId;
    long rowId = row.id;
    if (runnerId != null) {
      afterCommit(() -> registry.sendLater(runnerId, new Delete(rowId)));
    }
  }

  @Override
  public boolean presence(UUID runnerId) {
    return registry.presence(runnerId);
  }

  @Override
  public void estateChanged(UUID runnerId) {
    afterCommit(() -> registry.estateChanged(runnerId));
  }

  private void route(
      Workspace row, eu.wohlben.qits.runner.protocol.RunnerMessage frame, String verb, String what) {
    if (row.runnerId == null) {
      throw RunnerRefusals.unavailable(row.id, what);
    }
    switch (registry.request(row.runnerId, verb, row.id, frame, REPLY_DEADLINE)) {
      case ANSWERED -> {}
      case UNAVAILABLE -> throw RunnerRefusals.unavailable(row.id, what);
      case TIMEOUT -> {
        LOG.warnf(
            "Runner %s did not answer %s for workspace %d within %ss",
            row.runnerId, verb, row.id, REPLY_DEADLINE.toSeconds());
        throw RunnerRefusals.timeout(row.id, what);
      }
    }
  }

  /** {@code work} once the caller's transaction committed, or now when there is none. */
  private void afterCommit(Runnable work) {
    int status;
    try {
      status = transactions.getTransactionStatus();
    } catch (RuntimeException noContext) {
      status = Status.STATUS_NO_TRANSACTION;
    }
    if (status != Status.STATUS_ACTIVE) {
      work.run();
      return;
    }
    transactions.registerInterposedSynchronization(
        new Synchronization() {
          @Override
          public void beforeCompletion() {}

          @Override
          public void afterCompletion(int outcome) {
            if (outcome == Status.STATUS_COMMITTED) {
              work.run();
            }
          }
        });
  }
}
