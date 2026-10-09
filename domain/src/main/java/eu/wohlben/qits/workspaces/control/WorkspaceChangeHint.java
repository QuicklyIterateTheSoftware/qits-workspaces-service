package eu.wohlben.qits.workspaces.control;

/**
 * A payload-free "something changed, re-read it" signal for one workspace's live channel. Fired at
 * the existing mutation choke-points (telemetry ingest, command lifecycle, bootstrap, the daemon's
 * working-tree and agent reports) and delivered — via CDI async events — to the SSE boundary in the {@code service}
 * module, which pushes the {@link Topic} name to subscribed browsers. The hint carries no data: the
 * frontend reacts by re-fetching through the unchanged REST endpoints, so a dropped or missed hint
 * self-heals on the next hint or on reconnect.
 */
public record WorkspaceChangeHint(String repoId, Long workspaceRowId, Topic topic) {

  /** The kind of change; maps 1:1 to a frontend query-invalidation. */
  public enum Topic {
    /** The workspace's telemetry buffers got new data (debounced — highest churn). */
    TELEMETRY,
    /** A command's lifecycle changed (started, exited, terminated). */
    COMMANDS,
    /**
     * The bootstrap chain's state changed for this workspace — a chain started or ended, or a
     * command's last-run outcome was recorded. The frontend re-fetches the workspace bootstrap
     * surface.
     */
    BOOTSTRAP,
    /**
     * The workspace working tree changed on disk — a file was created, modified, deleted, or moved
     * (typically the coding agent scaffolding without a commit). Fired by {@code
     * WorkspaceDaemonRegistry} whenever the in-container {@code workspace-daemon} reports its
     * working-tree marker moved (the daemon watches with its own {@code inotifywait}); the frontend
     * re-fetches {@code /files} and {@code /detection}.
     */
    FILES,
    /**
     * The workspace's working-tree cleanliness (clean/dirty) changed — reported by the in-container
     * {@code workspace-daemon} over its socket and cached by {@code WorkspaceDaemonRegistry}. Fired
     * on the repository channel (branch tree) so the frontend re-fetches the workspace list and
     * refreshes the dirty badge.
     */
    GIT_STATUS,
    /**
     * A coding agent's live activity state changed for this workspace — its rollup flipped between
     * cooking (BUSY), idle, waiting-on-you, or none. Reported by the in-container {@code
     * workspace-daemon} (which hears the agent's lifecycle hooks) and cached by {@code
     * WorkspaceDaemonRegistry}; fired on the workspace channel (the frontend re-fetches the
     * workspace and refreshes the Agents-tab activity chip + the tab's busy dot), and mirrored onto
     * the repository channel {@code (repoId, null)} and the global channel {@code (null, null)} so
     * the agent-activity bars on the repository and project detail routes refresh live too.
     */
    AGENT_ACTIVITY,
    /**
     * A technical process for this workspace started or completed (e.g. a container start). The
     * frontend re-fetches {@code /active-process} and — when an id comes back — opens the separate
     * payload-bearing process SSE stream; this channel stays hint-only.
     */
    PROCESS,
    /**
     * The workspace's persisted prompt draft text changed — an autosave {@code PUT} or a {@code
     * DELETE} (Discard/Clear). Fired by {@code WorkspacePromptDraftService}; the frontend
     * invalidates the draft query so a draft edited on another device rehydrates the open view
     * (applied only when the local draft is pristine, so mid-typing is never clobbered). Kept
     * separate from {@link #PROMPT_ATTACHMENTS} so a high-churn text autosave never re-downloads
     * the image payloads.
     */
    PROMPT_DRAFT,
    /**
     * The workspace's prompt image attachments changed — a row was added or removed. Fired by
     * {@code WorkspacePromptAttachmentService}; the frontend invalidates only the attachments query
     * so another open view refreshes its thumbnail rows. Its own topic (not {@link #PROMPT_DRAFT})
     * so the far larger image-bytes refetch fires only on an actual attachment change, not on every
     * debounced prompt-text keystroke autosave.
     */
    PROMPT_ATTACHMENTS
  }
}
