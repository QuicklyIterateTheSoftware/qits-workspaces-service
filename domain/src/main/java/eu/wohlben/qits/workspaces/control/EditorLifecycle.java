package eu.wohlben.qits.workspaces.control;

/**
 * The in-container web editor's state, as the workspace-daemon reports it — and it is <b>not</b> the
 * container's. A container can be RUNNING with nothing serving inside it yet, which is the whole
 * reason the editor page has a waiting state at all.
 *
 * <p>Three values, because the editor is one process the daemon owns outright: there is no restart
 * policy whose outcome would need reporting. A restart is the editor going back to {@link
 * #STARTING}.
 *
 * <p>The sibling of {@link AgentActivityState}, down to being an enum here while the wire carries a
 * plain String: the vendored protocol module stays free of this context's display types, and a value
 * this enum has never heard of must read as "nothing reported" rather than fail a frame.
 */
public enum EditorLifecycle {

  /** Spawned (or about to be respawned) and not yet serving. The waiting room keeps waiting. */
  STARTING,

  /** Listening on its loopback port. Paired with a running container this is <em>ready</em>. */
  RUNNING,

  /**
   * Not coming back in this container: the daemon is shutting down, or the editor crash-looped past
   * its restart budget. Terminal, so a client can stop waiting and say so rather than spin for the
   * container's lifetime.
   */
  ENDED;

  /** The wire's String as one of these, or {@code null} for anything else — blank and unknown alike. */
  public static EditorLifecycle parse(String state) {
    if (state == null || state.isBlank()) {
      return null;
    }
    try {
      return valueOf(state.trim());
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }
}
