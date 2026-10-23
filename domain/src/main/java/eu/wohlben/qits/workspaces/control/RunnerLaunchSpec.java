package eu.wohlben.qits.workspaces.control;

import java.util.Map;

/**
 * A TARGET workspace's {@code LeaseSpec{image, env, user, limits}} (epic qits-1481): what this
 * service asks qits-system to run in a WORKSPACE slot, composed by {@link RunnerWorkspaceSpecs} from
 * the same row facts the DIRECT spec is.
 *
 * <p><b>The shape is the boundary.</b> Mounts, networks, capabilities, init and restart are the
 * WORKSPACE slot policy's, fixed in qits-system-runner: the work volume at {@code /workspace}, the
 * target's credential volume at {@code /claude-home} (with {@code CLAUDE_CONFIG_DIR} injected), the
 * m2 and pnpm caches, the bridge network. A spec can say none of them, and it never carries an env
 * key the policy owns ({@link RunnerWorkspaceSpecs#POLICY_ENV}).
 *
 * @param image the PUBLIC workspace image reference, {@code registry.qits.<domain>/qits/workspace:<v>},
 *     which the target pulls through the edge
 * @param env the container environment, in order, the lease's own token in it
 * @param user {@code --user}: never root; {@code 1000}, the workspace image's own
 * @param limits the configured resource limits, each null when unset
 */
public record RunnerLaunchSpec(
    String image, Map<String, String> env, String user, Limits limits) {

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
