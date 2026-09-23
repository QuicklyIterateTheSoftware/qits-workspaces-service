package eu.wohlben.qits.workspaces.control;

/**
 * What a workspace <em>is</em>, by row id — the postures {@link WorkspaceContainerFactory} asks
 * about before it decides what the container is made of. Two questions today: whether it runs in
 * admin mode (the host's docker socket), and whether it is the editor (the richer editor image and
 * the editor's environment).
 *
 * <p><b>Why the factory looks this up instead of being handed it.</b> Exactly the reason {@link
 * WorkspaceCredentials} gives, and it is worth repeating because the cost of getting it wrong is the
 * same: the orchestrator has no start verb, so a stopped container is started by presenting its spec
 * <em>again</em>, under {@code Recreate.ifChanged}. A posture that arrived as an argument on the
 * provision path and was missing on the start path would make every resume a spec change, and a spec
 * change replaces the container — writable layer and all. So the posture has to be derivable from
 * the workspace at <em>every</em> ensure, which makes the row its carrier and this a lookup rather
 * than a parameter threaded through {@link ContainerRuntime}.
 *
 * <p>An interface, injected as {@code Instance<T>}, because {@link WorkspaceContainerFactory} is
 * built by hand in the unit tests that assert what a container is made of and those tests have no
 * database. <b>Absent means no admin workspace exists</b> — no socket, which is the answer every
 * ordinary workspace gets and the only safe direction for an absence to fall.
 */
@FunctionalInterface
public interface WorkspacePostures {

  /** Whether this workspace's container is the admin kind. False for anything unknown. */
  boolean isAdmin(Long rowId);

  /**
   * Whether this workspace <b>is the editor</b> — the one shared editor container the platform
   * opens, the row {@code WorkspaceService.createEditorWorkspace} writes.
   *
   * <p><b>Stored, and it has to be.</b> It used to be derived — the repository's archetype being
   * {@code PROJECT} and the branch being that repository's main branch, back when there was one
   * editor per project — and a derivation was the better answer then, because every input to it was
   * a fact somebody else already owned. A single platform-wide editor has no such inputs: no
   * project, no wrapper repository, no branch. So the answer is a column ({@code Workspace.editor},
   * {@code V7}) and this is a local read.
   *
   * <p><b>It obeys the same reproducibility rule as {@link #isAdmin}</b>, and more sharply, because
   * it changes more of the spec: the image AND two environment variables. A spec that differs from
   * what is running is a {@code Recreate.ifChanged} <em>replacement</em>, so this answer has to be
   * the same at every ensure — which a column is by construction, and which is why the shipped
   * implementation needs no memo to promise it.
   *
   * <p>A {@code default} rather than a second abstract method, and the reason is mechanical as well
   * as semantic: this interface is written as a lambda by every hand-built test factory, so a second
   * abstract method would break all of them at once. <b>False is the right default</b> — a port that
   * does not answer is a plain workspace, which is what every workspace was before an editor
   * existed.
   */
  default boolean isEditor(Long rowId) {
    return false;
  }
}
