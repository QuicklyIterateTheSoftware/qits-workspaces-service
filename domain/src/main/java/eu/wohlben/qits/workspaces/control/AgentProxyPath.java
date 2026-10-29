package eu.wohlben.qits.workspaces.control;

/**
 * The agent proxy's path shape (epic qits-1667): {@code /workspaces/agent/{agentId}/…}, which
 * reaches the daemon of whatever workspace the agent is placed in now, under {@link
 * ContainerProxyPath#base}. A caller names the agent and never the workspace, so an agent moved to
 * another workspace is reached there with no caller change.
 *
 * <p><b>Singular {@code agent}.</b> {@code /workspaces/agents} is not used, so this prefix cannot
 * shadow anything under {@code /workspaces/api/agents}.
 *
 * <p>Unlike {@link ContainerProxyPath}, this prefix is <em>not</em> the daemon's own address: the
 * route maps each of its few sub-paths onto the daemon's path under the workspace's base. The daemon
 * still only ever sees {@code /workspaces/container/{workspaceId}/…}.
 */
public final class AgentProxyPath {

  public static final String PREFIX = "/workspaces/agent/";

  private AgentProxyPath() {}
}
