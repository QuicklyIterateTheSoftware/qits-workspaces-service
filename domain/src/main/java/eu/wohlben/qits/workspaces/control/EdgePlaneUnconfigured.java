package eu.wohlben.qits.workspaces.control;

/**
 * There is no edge plane to compose a RUNNER workspace's addresses from (qits-625): {@code
 * QITS_DOMAIN} is blank, has no dot, or is {@code localhost} or a {@code *.localhost} name — none of
 * which a container on a runner's node could reach the platform by. Thrown by {@link
 * WorkspaceAddressPlane#of}; the RUNNER start turns it into a FAILED row whose runtime error is
 * {@link #getMessage()}, and queues nothing.
 */
public class EdgePlaneUnconfigured extends RuntimeException {

  /** The code a refused row's runtime error starts with. */
  public static final String CODE = "EDGE_PLANE_UNCONFIGURED";

  private final String domain;

  public EdgePlaneUnconfigured(String domain) {
    super(CODE + ": QITS_DOMAIN '" + (domain == null ? "" : domain) + "' is not a public domain");
    this.domain = domain == null ? "" : domain;
  }

  /** The value that was refused, as configured ({@code ""} for none). */
  public String domain() {
    return domain;
  }
}
