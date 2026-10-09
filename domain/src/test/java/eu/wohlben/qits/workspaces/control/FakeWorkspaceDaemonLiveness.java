package eu.wohlben.qits.workspaces.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link WorkspaceDaemonLiveness}: a workspace is "daemon-live" only once a test
 * {@linkplain #markLive marks} it, so by default every {@code @QuarkusTest} sees no live daemon. A test
 * marks a workspace live to exercise the daemon-backed path.
 */
@Mock
@ApplicationScoped
public class FakeWorkspaceDaemonLiveness implements WorkspaceDaemonLiveness {

  private final Set<Long> live = ConcurrentHashMap.newKeySet();

  public void markLive(Long workspaceId) {
    live.add(workspaceId);
  }

  public void markDead(Long workspaceId) {
    live.remove(workspaceId);
  }

  @Override
  public boolean isDaemonLive(Long workspaceId) {
    return live.contains(workspaceId);
  }
}
