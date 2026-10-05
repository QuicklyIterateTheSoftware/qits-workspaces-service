package eu.wohlben.qits.workspaces.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>Every address a RUNNER-placed workspace container is told</b>, as one value, composed from the
 * platform's public domain and nothing else (qits-625, qits-799). qits-ci's {@code StepAddressPlane}
 * is the shape.
 *
 * <p><b>One plane, the edge.</b> A runner's node has no qits-net and no internal DNS, so every
 * address is the PUBLIC name of the service that answers it, {@code <host>.qits.<domain>}, reached
 * through the platform edge. There is no docker network to join and no extra host to add, and no
 * idp token url is composed: nothing in a RUNNER container mints. A DIRECT container is not composed
 * from this record at all — {@link WorkspaceContainerFactory#forWorkspace} is the DIRECT composer and
 * stays as it is.
 *
 * <p><b>The paths are the services' own routes, spelled here as constants</b>, each naming the route
 * that serves it; the platform project carries no environment label, so the host is {@code
 * <app>.qits.<domain>}.
 *
 * <p>Pure: strings in, strings out, no I/O and no config of its own. {@link WorkspaceAddressPlanes}
 * builds the configured one.
 *
 * @param domain {@code $QITS_DOMAIN}: the bare public domain every address is composed from,
 *     lower-cased and without outer dots
 * @param registrySpellings every host that names the platform's own registry, lower case; an image
 *     reference naming one of them is pulled from {@code registry.qits.<domain>}
 */
public record WorkspaceAddressPlane(String domain, List<String> registrySpellings) {

  /** The platform's own project: the second label of every platform application's host. */
  static final String PLATFORM_PROJECT = "qits";

  /**
   * qits-workspaces' {@code DaemonControlSocket} ({@code @WebSocket(path =
   * "/workspaces/daemon/{id}")}): the socket the in-container daemon dials home on.
   */
  static final String DAEMON_SOCKET_PATH = "/workspaces/daemon/";

  /** qits-githost's root-level prefix, passed through the edge verbatim: the clone base. */
  static final String GIT_PATH = "/git";

  /** qits-projects' repository MCP server ({@code /projects/mcp}). */
  static final String REPOSITORY_MCP_PATH = "/projects/mcp";

  /** qits-observability's MCP server ({@code /observability/mcp}). */
  static final String OBSERVABILITY_MCP_PATH = "/observability/mcp";

  /** qits-platform-access-mcp-service's central qits CLI MCP server (epic qits-630). */
  static final String PLATFORM_MCP_PATH = "/mcp";

  static final String WORKSPACES_HOST = "workspaces";
  static final String GITHOST_HOST = "githost";
  static final String PROJECTS_HOST = "projects";
  static final String OBSERVABILITY_HOST = "observability";
  static final String PLATFORM_MCP_HOST = "mcp";
  static final String REGISTRY_HOST = "registry";

  public WorkspaceAddressPlane {
    registrySpellings = List.copyOf(registrySpellings);
  }

  /**
   * The plane for {@code domain}, recognising an image as the platform's by {@code
   * registrySpellings} (hosts, any case; blanks ignored).
   *
   * @throws EdgePlaneUnconfigured when {@code domain} is blank, has no dot, or is {@code localhost}
   *     or ends in {@code .localhost}: no runner's node reaches the platform by such a name
   */
  public static WorkspaceAddressPlane of(String domain, List<String> registrySpellings) {
    String folded =
        domain == null
            ? ""
            : domain.trim().toLowerCase(Locale.ROOT).replaceAll("^\\.+|\\.+$", "");
    if (folded.isEmpty()
        || folded.indexOf('.') < 0
        || folded.equals("localhost")
        || folded.endsWith(".localhost")) {
      throw new EdgePlaneUnconfigured(domain == null ? "" : domain.trim());
    }
    List<String> spellings = new ArrayList<>();
    if (registrySpellings != null) {
      for (String host : registrySpellings) {
        if (host != null && !host.isBlank()) {
          String h = host.trim().toLowerCase(Locale.ROOT);
          if (!spellings.contains(h)) {
            spellings.add(h);
          }
        }
      }
    }
    return new WorkspaceAddressPlane(folded, spellings);
  }

  /**
   * {@code wss://workspaces.qits.<d>/workspaces/daemon/<rowId>}: {@code QITS_WORKSPACE_DAEMON_URL}.
   */
  public String daemonUrl(long rowId) {
    return "wss://" + host(WORKSPACES_HOST) + DAEMON_SOCKET_PATH + rowId;
  }

  /** {@code https://githost.qits.<d>/git}: {@code QITS_WORKSPACE_DAEMON_GIT_BASE_URL}. */
  public String gitBaseUrl() {
    return "https://" + gitAuthHost() + GIT_PATH;
  }

  /**
   * {@code githost.qits.<d>}: the one authority the image's git credential helper answers for
   * ({@code QITS_GIT_AUTH_HOST}) — a bare host, no scheme and no path, which is what the helper
   * compares git's request with.
   */
  public String gitAuthHost() {
    return host(GITHOST_HOST);
  }

  /** {@code https://projects.qits.<d>/projects/mcp}: {@code QITS_REPOSITORY_MCP_URL}. */
  public String repositoryMcpUrl() {
    return "https://" + host(PROJECTS_HOST) + REPOSITORY_MCP_PATH;
  }

  /**
   * {@code https://observability.qits.<d>/observability/mcp}: {@code QITS_OBSERVABILITY_MCP_URL}.
   */
  public String observabilityMcpUrl() {
    return "https://" + host(OBSERVABILITY_HOST) + OBSERVABILITY_MCP_PATH;
  }

  /** {@code https://mcp.qits.<d>/mcp}: {@code QITS_PLATFORM_MCP_URL}. */
  public String platformMcpUrl() {
    return "https://" + host(PLATFORM_MCP_HOST) + PLATFORM_MCP_PATH;
  }

  /**
   * {@code image} as a runner pulls it: when its registry host (its first path segment) is one of
   * {@link #registrySpellings}, that host swapped for {@code registry.qits.<d>} with the path, tag
   * and {@code @sha256:} digest kept byte for byte — a digest is content-addressed, so it names the
   * same bytes at either address. Anything else ({@code docker.io/…}, {@code alpine:3}) is somebody
   * else's store and is returned exactly as given.
   */
  public String imageReference(String image) {
    if (image == null) {
      return null;
    }
    int slash = image.indexOf('/');
    if (slash <= 0) {
      return image;
    }
    String registry = image.substring(0, slash);
    if (!registrySpellings.contains(registry.toLowerCase(Locale.ROOT))) {
      return image;
    }
    return host(REGISTRY_HOST) + image.substring(slash);
  }

  private String host(String app) {
    return app + "." + PLATFORM_PROJECT + "." + domain;
  }
}
