package eu.wohlben.qits.workspaces.error;

import java.util.List;

/**
 * The 409 a runner delete answers while the runner owns ACTIVE workspaces: {@code code} {@link
 * #CODE}, and the owned row ids beside the message, so an operator can see which workspaces to
 * resolve (or move) first. {@code WorkspacesExceptionMapper} adds them to the envelope.
 */
public class RunnerOwnsWorkspacesException extends ConflictException {

  public static final String CODE = "RUNNER_OWNS_WORKSPACES";

  private final List<Long> workspaceIds;

  public RunnerOwnsWorkspacesException(String message, List<Long> workspaceIds) {
    super(CODE, message);
    this.workspaceIds = List.copyOf(workspaceIds);
  }

  /** The ACTIVE workspace row ids the runner owns. Never null. */
  public List<Long> workspaceIds() {
    return workspaceIds;
  }
}
