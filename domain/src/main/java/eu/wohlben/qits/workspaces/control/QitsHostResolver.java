package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Resolves the address a workspace container uses to reach this qits instance. Every host-facing
 * URL a container consumes is composed as {@code http://<qitsHost()>:<qits-port>/...} from this one
 * resolver: the git clone/push endpoint ({@code /artifacts/git/...}, {@code WorkspaceService}), the
 * OTLP receiver ({@code /observability/api/otel}, {@code OtelEnvironment}) and this service's own
 * daemon control socket ({@code /workspaces/daemon/<id>}, {@code WorkspaceContainerFactory}) — each under its
 * owning service's gateway segment, which the gateway routes verbatim by prefix. Only the host and
 * port come from here; the path is the callee's. Made a first-class concern because the right value
 * differs by environment and getting it wrong is the silent failure mode of the whole feature:
 *
 * <ul>
 *   <li><strong>Shared-network deployment (the devcontainer)</strong>: when qits itself runs in a
 *       container on the same {@code qits-net} as the workspace containers, a container reaches
 *       qits by its <em>network alias</em> — the devcontainer sets {@code
 *       qits.workspace.git-host=qits} explicitly, so container→qits (git/OTLP/MCP) is plain
 *       container→container DNS with no host hop at all. This is the preferred setup and sidesteps
 *       the WSL2 problem below entirely.
 *   <li><strong>Plain Linux docker (qits on the host)</strong>: {@code host.docker.internal} works
 *       — every workspace spec carries the {@code host.docker.internal:host-gateway} alias, so it
 *       maps to the host and the app (bound on {@code 0.0.0.0}) is reachable.
 *   <li><strong>WSL2 + Docker Desktop (qits on the host)</strong>: {@code host.docker.internal}
 *       resolves to an address that does <em>not</em> reach the WSL2 distro's app (commonly an
 *       unreachable IPv6), so a container clone fails. The distro's primary LAN IPv4 (its {@code
 *       eth0} address) <em>is</em> reachable.
 * </ul>
 *
 * <p>The config {@code qits.workspace.git-host} (historical name, kept for compatibility) defaults
 * to the sentinel {@code auto}: auto-detect per environment (the two host-run cases above) so it
 * "just works" without a machine-local override. Any explicit value (a container alias like {@code
 * qits}, an IP, a hostname, or literally {@code host.docker.internal}) is respected as-is — the
 * shared-network deployment relies on this to pin the alias.
 */
@ApplicationScoped
public class QitsHostResolver {

  private static final Logger LOG = Logger.getLogger(QitsHostResolver.class);

  @ConfigProperty(name = "qits.workspace.git-host", defaultValue = "auto")
  String configured;

  private volatile String resolved;

  /** The effective host containers use to reach qits, computed once and cached. */
  public String qitsHost() {
    String value = resolved;
    if (value == null) {
      synchronized (this) {
        if (resolved == null) {
          resolved = resolve(configured);
          LOG.infof(
              "Resolved workspace qits-host to '%s' (configured: '%s')", resolved, configured);
        }
        value = resolved;
      }
    }
    return value;
  }

  /**
   * Resolves {@code configured}: an explicit value is returned unchanged; {@code auto} (or blank)
   * detects — the primary LAN IPv4 on WSL2, else {@code host.docker.internal}. Public so callers
   * (e.g. an integration test) can compute the same effective host the app will use.
   */
  public static String resolve(String configured) {
    if (configured != null && !configured.isBlank() && !configured.equals("auto")) {
      return configured;
    }
    if (isWsl2()) {
      String lan = primaryLanIpv4();
      if (lan != null) {
        return lan;
      }
      LOG.warn(
          "Running on WSL2 but could not detect a LAN IPv4; falling back to host.docker.internal,"
              + " which is typically unreachable from containers here. Set qits.workspace.git-host"
              + " explicitly if container clones fail.");
    }
    return "host.docker.internal";
  }

  /** Whether we're running under WSL (the kernel string carries "microsoft"/"WSL"). */
  static boolean isWsl2() {
    try {
      String version = Files.readString(Path.of("/proc/version")).toLowerCase();
      return version.contains("microsoft") || version.contains("wsl");
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * The host's primary outbound IPv4 — the interface a packet to the internet would leave from
   * (i.e. the WSL2 {@code eth0} address). A UDP {@code connect} sets the route without sending any
   * packet, so this works offline as long as a default route exists. Returns null if none is found.
   */
  static String primaryLanIpv4() {
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.connect(InetAddress.getByName("8.8.8.8"), 80);
      InetAddress local = socket.getLocalAddress();
      if (local instanceof Inet4Address
          && !local.isAnyLocalAddress()
          && !local.isLoopbackAddress()) {
        return local.getHostAddress();
      }
    } catch (Exception ignored) {
      // fall through to null — caller decides the fallback
    }
    return null;
  }
}
