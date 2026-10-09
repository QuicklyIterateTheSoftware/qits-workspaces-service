package eu.wohlben.qits.workspaces.control;

/**
 * An agent launch this service made was accepted by the workspace's daemon, and the command it
 * started is now the row's {@code dispatchCommandId} (qits-895).
 *
 * <p>Fired in-process <b>after the write committed</b>, by {@link DispatchService}, so a reader that
 * re-reads the row sees what the event says. Its one reader is the daemon registry's relay of the
 * dispatched agent's waiting state to qits-projects: a new dispatched command is a new agent, so
 * the relay takes the new id and forgets what it last said about the old one — the new agent's
 * first turn ending is news even when the old agent's last one ended the same way.
 *
 * @param rowId the workspace row, {@code Workspace.id}
 * @param commandId the daemon command the launch started
 * @param workId the work item the row is bound to, or null for a row no dispatch bound
 */
public record AgentLaunched(Long rowId, String commandId, String workId) {}
