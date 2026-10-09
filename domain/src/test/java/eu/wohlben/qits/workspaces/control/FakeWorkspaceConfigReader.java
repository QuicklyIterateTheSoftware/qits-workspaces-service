package eu.wohlben.qits.workspaces.control;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link WorkspaceConfigReader}: the config-sourced definitions (actions, bootstrap
 * steps) the Part-5 single-source-of-truth runtime reads. Tests stage a workspace's
 * config with {@link #setConfig} instead of creating DB rows (the DB config store is gone), and the
 * bootstrap runner resolves from it. An unset workspace
 * reads empty — the no-live-daemon case.
 *
 * <p>An enabled alternative so it wins the {@code Instance<WorkspaceConfigReader>} injection even
 * where the backend registry is present (service module tests). Keep the {@code domain}/{@code
 * service} copies in sync.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class FakeWorkspaceConfigReader implements WorkspaceConfigReader {

  private final Map<Long, WorkspaceConfigView> views = new ConcurrentHashMap<>();

  private final java.util.Set<Long> oneShot = ConcurrentHashMap.newKeySet();

  @Override
  public Optional<WorkspaceConfigView> readConfig(Long workspaceId) {
    WorkspaceConfigView view = views.get(workspaceId);
    if (view != null && oneShot.remove(workspaceId)) {
      views.remove(workspaceId);
    }
    return Optional.ofNullable(view);
  }

  /** Stage {@code config} as {@code workspaceId}'s in-container config (warning-free). */
  public void setConfig(Long workspaceId, QitsConfig config) {
    oneShot.remove(workspaceId);
    views.put(workspaceId, new WorkspaceConfigView(config, null));
  }

  /**
   * Stage a config that answers exactly one read, then reads empty. The live config read is a
   * control-socket round trip that can stop answering at any moment (a reconnect, a reply starved
   * behind a blocked pipeline — the measured D1 deadlock), so a consumer that resolves the
   * definitions once must carry them rather than read again; this staging is how a test proves it.
   */
  public void setConfigOnce(Long workspaceId, QitsConfig config) {
    views.put(workspaceId, new WorkspaceConfigView(config, null));
    oneShot.add(workspaceId);
  }

  /** Forget every staged config (call between tests sharing the bean). */
  public void clear() {
    views.clear();
    oneShot.clear();
  }
}
