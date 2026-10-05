package eu.wohlben.qits.workspaces.error;

/**
 * Base for domain-layer errors. Carries an HTTP-ish status code so the web layer can map it to a
 * response without the domain depending on JAX-RS. The {@code service} module maps these via {@code
 * WorkspacesExceptionMapper}.
 */
public class DomainException extends RuntimeException {

  private final int statusCode;

  private final String code;

  public DomainException(int statusCode, String message) {
    this(statusCode, null, message);
  }

  public DomainException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
    this.code = null;
  }

  /**
   * A refusal that names itself: {@code code} is a stable word a client branches on (e.g. {@code
   * RUNNER_OWNS_WORKSPACES}), answered beside the message. Null for the refusals that have none.
   */
  public DomainException(int statusCode, String code, String message) {
    super(message);
    this.statusCode = statusCode;
    this.code = code;
  }

  public int statusCode() {
    return statusCode;
  }

  /** The refusal's own name, or null when it has none. */
  public String code() {
    return code;
  }
}
