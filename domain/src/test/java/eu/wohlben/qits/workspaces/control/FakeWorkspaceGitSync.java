package eu.wohlben.qits.workspaces.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for {@link WorkspaceGitSync}: records every {@link #pullFromOrigin} call instead of
 * touching a real daemon, so a test can assert that a host-side merge/integration into a
 * workspace-backed target fired the incoming-pull notification. Always present (like {@link
 * FakeWorkspaceAgentActivity}), so {@code WorkspaceService.gitSync} is satisfied in every
 * {@code @QuarkusTest}; the recording starts empty.
 *
 * <p>The recorded list is reached only through {@link #pulls()}/{@link #clear()} <b>methods</b>,
 * never a public field: this is an {@code @ApplicationScoped} bean, so a test injects a client
 * proxy — a field read would hit the proxy's empty copy, while a method call delegates to the real
 * instance that {@link #pullFromOrigin} mutated (the same reason {@link FakeAgentDaemon}
 * exposes methods only).
 */
@Mock
@ApplicationScoped
public class FakeWorkspaceGitSync implements WorkspaceGitSync {

  private final List<String> pulls = new CopyOnWriteArrayList<>();

  @Override
  public void pullFromOrigin(Long workspaceId, String branch) {
    pulls.add(workspaceId + " " + branch);
  }

  /** Each recorded notification, as {@code workspaceId + " " + branch}, in call order. */
  public List<String> pulls() {
    return List.copyOf(pulls);
  }

  public void clear() {
    pulls.clear();
  }
}
