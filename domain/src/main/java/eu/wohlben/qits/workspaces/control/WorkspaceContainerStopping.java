package eu.wohlben.qits.workspaces.control;

/**
 * Fired when a workspace container is about to be deliberately removed — {@code stopContainer} (the
 * graceful, lossless stop) or a discard. Unlike its sibling {@link WorkspaceContainerStarted}, this
 * is fired <em>synchronously</em> and <em>before</em> {@code containers.rm}, so an observer sees
 * the container while it still exists. Its one production observer (the workspace services' settle)
 * went with qits-947; the edge stays because the lifecycle tests pin its ordering.
 *
 * <p>{@code graceful} distinguishes the two callers: {@code stopContainer} passes {@code true} (a
 * graceful stop), a discard passes {@code false} (the work is being thrown away).
 */
public record WorkspaceContainerStopping(
    String repoId, String workspaceId, Long workspaceRowId, boolean graceful) {}
