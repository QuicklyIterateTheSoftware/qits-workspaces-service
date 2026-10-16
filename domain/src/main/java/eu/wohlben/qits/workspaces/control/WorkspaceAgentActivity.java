package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * The live coding-agent activity a workspace's in-container {@code workspace-daemon} reported over
 * its dial-home socket (docs/epics/qits-coding-agents/ agent-activity tracking). Framework-free (no
 * websockets type) so it lives in {@code domain}; the {@code service} module implements it over
 * {@code WorkspaceDaemonRegistry}, and {@link WorkspaceService} reads it as an {@code Instance<>}
 * that is simply empty in apps without the backend (e.g. {@code cli}, tests). Sibling of {@link
 * WorkspaceDaemonInfo}.
 *
 * <p>The value is a per-workspace <b>rollup</b> across that workspace's currently-tracked agent
 * commands (precedence BUSY &gt; WAITING &gt; IDLE), cached in-memory only while the daemon is
 * connected (the container is RUNNING); {@link Optional#empty()} means "no active agent / unknown".
 * The daemon re-reports on every reconnect, so a qits restart self-heals within one socket
 * round-trip.
 */
public interface WorkspaceAgentActivity {

  /**
   * The current rollup activity state for {@code workspaceId}, or {@link Optional#empty()} if no
   * tracked agent is running there (or no live daemon / not yet reported).
   */
  Optional<AgentActivityState> activityFor(Long workspaceId);
}
