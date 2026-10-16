package eu.wohlben.qits.workspaces.error;

/** Domain error mapped to HTTP 400 by the web layer. */
public class BadRequestException extends DomainException {

  public BadRequestException(String message) {
    super(400, message);
  }

  public BadRequestException(String message, Throwable cause) {
    super(400, message, cause);
  }

  /** A 400 that names itself: {@code code} is answered beside the message. */
  public BadRequestException(String code, String message) {
    super(400, code, message);
  }
}
