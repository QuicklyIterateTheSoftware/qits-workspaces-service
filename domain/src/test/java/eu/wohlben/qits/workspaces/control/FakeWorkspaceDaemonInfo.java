package eu.wohlben.qits.workspaces.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link WorkspaceDaemonInfo}: a workspace has no live daemon (so {@link #lookup}
 * is {@link Optional#empty()} and {@link #all} is empty) until a test {@linkplain #report reports}
 * one, so by default every {@code @QuarkusTest} sees no registry facts and {@code WorkspaceDto}'s
 * daemon fields (including {@code daemonOutdated}) stay null. Mirrors {@link
 * FakeWorkspaceAgentActivity}; drives the "latest agent version"/outdated computation in {@link
 * WorkspaceService#listWorkspaces}.
 */
@Mock
@ApplicationScoped
public class FakeWorkspaceDaemonInfo implements WorkspaceDaemonInfo {

  /**
   * A capability version at or above the reverse tunnel's, spelled as a literal because {@code
   * domain} does not depend on the protocol module — it is framework-free and the wire contract is
   * the {@code service} module's. {@code DaemonProtocol.TUNNEL_CAPABILITY_VERSION} is the real
   * name; if that ever moves past this, the tunnel branch stops being exercised by the fake.
   */
  private static final int TUNNEL_CAPABLE = 4;

  private final ConcurrentHashMap<Long, Info> infos = new ConcurrentHashMap<>();

  /**
   * Announce a live daemon for {@code workspaceId} with the given build identity. A null {@code
   * buildTime} mimics an older image that reported none — never orderable, so never "the latest".
   */
  public void report(Long workspaceId, String version, Instant buildTime) {
    report(workspaceId, version, buildTime, TUNNEL_CAPABLE);
  }

  /**
   * The same, naming the capability version the daemon announced. Overloaded rather than added to
   * the call above because only the reverse-tunnel branch cares about it, and every existing caller
   * is about build identity — the tunnel-capable value is the right default for them.
   */
  public void report(Long workspaceId, String version, Instant buildTime, int capabilityVersion) {
    infos.put(workspaceId, new Info(Instant.EPOCH, version, buildTime, capabilityVersion));
  }

  public void forget(Long workspaceId) {
    infos.remove(workspaceId);
  }

  @Override
  public Optional<Info> lookup(Long workspaceId) {
    return Optional.ofNullable(infos.get(workspaceId));
  }

  @Override
  public Collection<Info> all() {
    return List.copyOf(infos.values());
  }
}
