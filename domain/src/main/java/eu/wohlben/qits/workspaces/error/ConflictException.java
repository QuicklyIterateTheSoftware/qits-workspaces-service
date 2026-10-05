package eu.wohlben.qits.workspaces.error;

/**
 * Domain error mapped to HTTP 409 by the web layer: the request is well-formed, but the resource it
 * claims is already owned. Used for the branch a workspace wants — see {@link
 * eu.wohlben.qits.workspaces.control.WorkspaceService#createWorkspace}.
 */
public class ConflictException extends DomainException {

  public ConflictException(String message) {
    super(409, message);
  }

  public ConflictException(String message, Throwable cause) {
    super(409, message, cause);
  }

  /** A 409 that names itself: {@code code} is answered beside the message. */
  public ConflictException(String code, String message) {
    super(409, code, message);
  }
}
