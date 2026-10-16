package eu.wohlben.qits.workspaces.control;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * The workspace registry's live view of a workspace's in-container {@code workspace-daemon}
 * (docs/epics/qits-workspace-registry/): when its control socket registered, and the build identity
 * it announced in its {@link eu.wohlben.qits.workspacedaemon.protocol.Hello}. Framework-free (no
 * websockets type) so it lives in {@code domain}, the sibling of {@link WorkspaceAgentActivity};
 * the {@code service} module implements it over {@code WorkspaceDaemonRegistry}, and {@link
 * WorkspaceService} reads it as an {@code Instance<>} that is simply empty in apps without the
 * backend (e.g. {@code cli}, tests).
 *
 * <p>Like agent activity, this is in-memory only and known only while the daemon is connected (the
 * container is RUNNING): {@link #lookup} is {@link Optional#empty()} for a workspace with no live
 * daemon, and {@code connectedAt} resets each time the socket (re)connects — it is "connected
 * since", not a durable first-registered timestamp. This is the extensible seam for further
 * per-daemon runtime facts the registry may surface over time.
 */
public interface WorkspaceDaemonInfo {

  /**
   * The live registry entry for {@code workspaceId}, or {@link Optional#empty()} if no daemon is
   * currently connected for it.
   */
  Optional<Info> lookup(Long workspaceId);

  /**
   * Every live daemon's registry facts, across all workspaces and repositories — the enumeration
   * seam for registry-wide questions the per-workspace {@link #lookup} can't answer, notably "which
   * daemon build is the newest currently connected" (the registry-only notion of the latest agent
   * version). Empty when no daemon is connected; iteration order is unspecified.
   */
  Collection<Info> all();

  /**
   * A live daemon connection's registry facts.
   *
   * @param connectedAt when the current control socket registered — the workspace's "connected
   *     since". Never {@code null} for a present entry.
   * @param version the daemon binary's release version (Maven {@code project.version}), or {@code
   *     null} if an older daemon image announced none
   * @param buildTime when the daemon binary was built, or {@code null} if unknown (older image, or
   *     a dev jar built without build-identity filtering)
   * @param capabilityVersion the wire-contract version the daemon announced, or {@code 0} before a
   *     {@code Hello} has arrived. Unlike the two above this one is <b>branched on</b>, not merely
   *     displayed: from {@code DaemonProtocol.TUNNEL_CAPABILITY_VERSION} the daemon's HTTP API binds
   *     loopback and is reachable only through the reverse tunnel, and below it the daemon binds
   *     qits-net and cannot serve a tunnel at all. The two are strictly complementary, so this
   *     single number decides which way to reach a workspace — and {@code 0} counts as "not
   *     capable", which is the safe direction: a container old enough to predate the tunnel is also
   *     old enough to still be listening.
   */
  record Info(Instant connectedAt, String version, Instant buildTime, int capabilityVersion) {}
}
