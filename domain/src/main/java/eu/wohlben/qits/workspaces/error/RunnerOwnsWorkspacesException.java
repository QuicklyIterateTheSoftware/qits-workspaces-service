package eu.wohlben.qits.workspaces.error;

import java.util.List;

/**
 * The 409 a runner delete answers while the runner owns ACTIVE workspaces: {@code code} {@link
 * #CODE}, and the owned rows beside the message, so an operator can see which workspaces to resolve
 * (or move) first. {@code WorkspacesExceptionMapper} adds them to the envelope twice: as {@code
 * workspaceIds}, the bare row ids, and as {@code workspaces}, each with the repository and branch
 * the runners page links it by.
 */
public class RunnerOwnsWorkspacesException extends ConflictException {

  public static final String CODE = "RUNNER_OWNS_WORKSPACES";

  /**
   * One ACTIVE workspace the runner owns, as the runners page links to it.
   *
   * @param id the workspace row id
   * @param repositoryId its repository
   * @param branch its branch; null for a workspace on none
   */
  public record OwnedWorkspace(Long id, String repositoryId, String branch) {}

  private final List<OwnedWorkspace> workspaces;

  public RunnerOwnsWorkspacesException(String message, List<OwnedWorkspace> workspaces) {
    super(CODE, message);
    this.workspaces = List.copyOf(workspaces);
  }

  /** The ACTIVE workspace row ids the runner owns, in {@link #workspaces()}' order. Never null. */
  public List<Long> workspaceIds() {
    return workspaces.stream().map(OwnedWorkspace::id).toList();
  }

  /** The ACTIVE workspaces the runner owns, by row id. Never null. */
  public List<OwnedWorkspace> workspaces() {
    return workspaces;
  }
}
