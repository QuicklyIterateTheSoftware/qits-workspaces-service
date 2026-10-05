package eu.wohlben.qits.workspaces.control;

import java.util.List;
import java.util.Map;

/**
 * What a workspace runner is told to launch for one RUNNER workspace: the domain's half of the
 * protocol's {@code WorkspaceSpec}, which the socket maps onto field for field (qits-851). Composed
 * by {@link RunnerWorkspaceSpecs} from the same row facts the DIRECT spec is.
 *
 * <p><b>No address and no credential</b> is the property that matters (the epic's stated
 * limitation): no dial-home URL, no git base, no MCP address, no token, no commissioned pair, no
 * network, no extra host, no docker socket and no user. A runner-placed workspace gets those with
 * qits-625; until then its daemon idles.
 *
 * @param image the PUBLIC workspace image reference, {@code registry.qits.<domain>/qits/workspace:<v>},
 *     which the runner pulls with its own client through the edge
 * @param env the container environment, in order
 * @param mounts the four logical volumes and where each is mounted; the runner names the node
 *     volume behind each
 * @param labels the factory's {@code qits.*} labels. Never a key under {@code
 *     qits.workspaces.runner.}: the runner adds its own through the driver's namespace
 * @param limits the configured resource limits, each null when unset
 * @param init whether tini runs as PID 1 ahead of the daemon; always true, as for DIRECT
 */
public record RunnerLaunchSpec(
    String image,
    Map<String, String> env,
    List<Mount> mounts,
    Map<String, String> labels,
    Limits limits,
    boolean init) {

  /** The volumes a runner keeps on its node for a workspace. */
  public enum Volume {
    /** The workspace's own {@code /workspace}: {@code -ws-<rowId>}, the reason the row is sticky. */
    WORKSPACE,
    /** The node's agent home, shared by every workspace on that runner (one login per node). */
    DOT_CLAUDE,
    /** The node's Maven repository cache. */
    M2,
    /** The node's pnpm store. */
    PNPM
  }

  /** One logical volume, mounted at {@code target}. */
  public record Mount(Volume volume, String target) {}

  /**
   * Resource limits, as the DIRECT spec carries them; each null when not configured.
   *
   * @param memory docker's {@code --memory}
   * @param memorySwap docker's {@code --memory-swap} (memory plus swap)
   * @param pids docker's {@code --pids-limit}
   * @param cpus docker's {@code --cpus}
   * @param oomScoreAdj docker's {@code --oom-score-adj}
   */
  public record Limits(
      String memory, String memorySwap, String pids, String cpus, Integer oomScoreAdj) {}
}
