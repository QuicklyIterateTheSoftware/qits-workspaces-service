package eu.wohlben.qits.workspaces.error;

/**
 * Domain error mapped to HTTP 403 by the web layer: the caller is authenticated, and this resource is
 * not theirs. Used by the runner register door, where the bearer must be that runner's own
 * registration token.
 */
public class ForbiddenException extends DomainException {

  public ForbiddenException(String message) {
    super(403, message);
  }
}
