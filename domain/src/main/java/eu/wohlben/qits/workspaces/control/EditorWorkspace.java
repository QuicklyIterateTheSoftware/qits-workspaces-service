package eu.wohlben.qits.workspaces.control;

/**
 * The two strings the platform's one editor workspace is spelled with.
 *
 * <p>They are here, in a holder of their own, because three unrelated places have to agree on them
 * and none of them owns the other: {@code WorkspaceService.createEditorWorkspace} writes the row,
 * {@link EditorProxyTargets} reads it back, and the container name and volume name the orchestrator
 * is asked for are <em>derived</em> from both ({@code qits-ws-editor-editor}, {@code
 * qits_workspace_editor}) by the same arithmetic every other workspace's are. A literal repeated at
 * those call sites would be three chances to rename one and not the others, and the symptom of that
 * is a second container beside the one somebody is working in.
 *
 * <p><b>Deterministic is the point, not an accident.</b> Every other workspace's container name
 * carries a branch label and eight characters of a repository id because there are many of them;
 * there is exactly one editor, so its name is a constant, and that is what lets
 * {@code EditorKeepalive} and {@code EditorProxyRoute} compose the same name off the row without
 * either of them special-casing anything.
 */
public final class EditorWorkspace {

  /**
   * The editor row's {@code workspaceId} — the branch-derived label every other row carries, except
   * that this one derives from nothing. It is the path/container-name/volume-name segment, so it is
   * a slug like any other ({@code [A-Za-z0-9_-]}).
   */
  public static final String WORKSPACE_ID = "editor";

  /**
   * The editor row's {@code repository_id}, and it is a <b>sentinel rather than a repository</b>:
   * the column is {@code nullable = false} and the shared editor belongs to no single repository.
   * Nothing resolves it — {@link RepositoryLookup} has never heard of it, and every lookup this
   * context makes about a repository is skipped for the editor rather than made and failed, so an
   * unresolvable id costs nothing. What it does buy is that the row satisfies the schema without a
   * migration that makes the column nullable for one row's sake.
   */
  public static final String REPOSITORY_ID = "editor";

  private EditorWorkspace() {}
}
