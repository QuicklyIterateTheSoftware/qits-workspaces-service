package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test helper: records every {@link WorkspaceContainerStarted} the async event bus delivers, so
 * tests can assert which {@code ensureContainer} paths fire it (cold transitions) and which don't
 * (the already-running short-circuit).
 */
@ApplicationScoped
public class WorkspaceContainerStartedRecorder {

  private final List<WorkspaceContainerStarted> events = new CopyOnWriteArrayList<>();

  void onStarted(@ObservesAsync WorkspaceContainerStarted evt) {
    events.add(evt);
  }

  public void clear() {
    events.clear();
  }

  public long countFor(String repoId, String workspaceId) {
    return events.stream()
        .filter(e -> e.repoId().equals(repoId) && e.workspaceId().equals(workspaceId))
        .count();
  }

  /** Poll until at least {@code expected} events for the key arrive, or the deadline elapses. */
  public boolean awaitCount(String repoId, String workspaceId, int expected, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (countFor(repoId, workspaceId) >= expected) {
        return true;
      }
      Thread.sleep(25);
    }
    return countFor(repoId, workspaceId) >= expected;
  }
}
