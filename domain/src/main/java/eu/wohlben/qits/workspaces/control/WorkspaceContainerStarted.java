package eu.wohlben.qits.workspaces.control;

/**
 * Fired when a workspace container completes a cold&#8594;RUNNING transition in {@link
 * WorkspaceService#ensureContainer} — either a fresh provision or an in-place restart of an Exited
 * container, distinguished by {@code freshProvision}. The already-running short-circuit
 * deliberately does <em>not</em> fire this (nothing changed).
 *
 * <p>Consumed asynchronously by {@code WorkspaceBootstrapRunner}, which awaits the repository's
 * bootstrap chain on a fresh provision and then ends the start's technical process. A plain restart
 * (or an empty chain) passes straight through. The event keeps the bootstrap await off {@code
 * ensureContainer}'s thread, mirroring the {@code WorkspaceChangeHint}/{@code
 * WorkspaceChangePublisher} pattern.
 *
 * <p>{@code technicalProcessId} correlates the asynchronous bootstrap phase with the
 * technical process that streamed the provision (the observer runs on the async observer thread, so
 * the correlation cannot ride a {@code ThreadLocal}). Null when the start wasn't process-tracked
 * (internal blocking callers like {@code CommandService.prepare}).
 *
 * <p>{@code freshProvision} is true only for the {@code docker run} + clone path — the transition
 * that leaves a bare checkout needing bootstrap. An in-place restart keeps its {@code /workspace}
 * clone and bootstrap state, so the chain does not re-run for it.
 */
public record WorkspaceContainerStarted(
    String repoId,
    String workspaceId,
    Long workspaceRowId,
    String technicalProcessId,
    boolean freshProvision) {}
