package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.workspaces.control.WorkspaceContainerFactory;
import eu.wohlben.qits.workspaces.error.DomainException;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Every address a workspace runner is handed, composed from the platform's public domain alone
 * (qits-ci's {@code RunnerAddresses} is the shape). A runner lives outside the swarm and reaches
 * this platform only through its edge, so every name here is a public one: {@code
 * https://workspaces.qits.<d>} for the install script and the register door, {@code
 * wss://workspaces.qits.<d>/workspaces/runners/socket} for its socket, {@code
 * https://idp.qits.<d>/idp/token} for its bearer, {@code registry.qits.<d>} for every image it
 * pulls. The platform's own project, {@code qits}, is the second label of each: the edge names a
 * platform application {@code <app>.qits.<d>}.
 *
 * <p><b>No override keys.</b> CI's has three, from the days of the internal plane; this one never
 * had a second plane, so the domain is the whole input and nothing is a deployment's to spell.
 *
 * <p><b>A domain without a dot is no domain</b>, the rule CI's addresses keep: the suites' and a
 * bare local install's {@code localhost} names nothing a runner on another host could reach. Every
 * method then refuses with 503 {@link #RUNNER_PLANE_UNCONFIGURED}, which is what the create door,
 * the register door and {@code install.sh} answer before anything is minted.
 *
 * <p>The domain is {@code qits.workspace.domain} ({@code QITS_DOMAIN}), the key {@link
 * WorkspaceContainerFactory#publicDomain} reads for the runner launch spec's image; the workspace
 * image's version is the factory's too, so the image a runner is told in its {@code estate}, in a
 * {@code take} and in the login command is the one a DIRECT launch would pull.
 */
@ApplicationScoped
public class WorkspaceRunnerAddresses {

  /** The refusal of every runner door on a deployment with no public domain. */
  public static final String RUNNER_PLANE_UNCONFIGURED = "RUNNER_PLANE_UNCONFIGURED";

  /** The audience every token this platform mints carries, and the one a runner asks for. */
  public static final String AUDIENCE = "qits-platform";

  /** The platform's own project: the second label of every platform application's host. */
  static final String PLATFORM_PROJECT = "qits";

  static final String WORKSPACES_HOST = "workspaces";

  static final String IDP_HOST = "idp";

  static final String REGISTRY_HOST = "registry";

  /** The workspace image's repository in the platform registry. */
  static final String WORKSPACE_IMAGE_REPOSITORY = "qits/workspace";

  @ConfigProperty(name = "qits.workspace.domain")
  Optional<String> domain;

  @Inject WorkspaceContainerFactory containerFactory;

  /** {@code https://workspaces.qits.<d>}: the install script's and the register door's origin. */
  public String serviceBase() {
    return origin(WORKSPACES_HOST);
  }

  /** {@code wss://workspaces.qits.<d>/workspaces/runners/socket}. */
  public String socketUrl() {
    return "wss://" + host(WORKSPACES_HOST) + WorkspacesRunnerProtocol.SOCKET_PATH;
  }

  /** {@code https://idp.qits.<d>/idp/token}, where a runner mints its bearer. */
  public String tokenUrl() {
    return origin(IDP_HOST) + "/idp/token";
  }

  /** The audience a runner asks its bearer for. */
  public String audience() {
    return AUDIENCE;
  }

  /** {@code registry.qits.<d>}: the host every runner image and workspace image is pulled from. */
  public String registryHost() {
    return host(REGISTRY_HOST);
  }

  /** {@code registry.qits.<d>/qits/qits-workspaces-runner:<version>}: an upgrade's image. */
  public String runnerImage(String version) {
    return registryHost() + "/" + WorkspaceRunnerPins.IMAGE_REPOSITORY + ":" + version;
  }

  /** {@code registry.qits.<d>/qits/workspace:<pin>}: what every runner-placed workspace runs. */
  public String workspaceImage() {
    return registryHost() + "/" + WORKSPACE_IMAGE_REPOSITORY + ":" + containerFactory.imageVersion();
  }

  /** Whether a public domain is configured, so every method above answers rather than refusing. */
  public boolean configured() {
    return publicDomain().isPresent();
  }

  /** 503 {@link #RUNNER_PLANE_UNCONFIGURED} unless a public domain is configured. */
  public void requireConfigured() {
    host(WORKSPACES_HOST);
  }

  private String origin(String app) {
    return "https://" + host(app);
  }

  private String host(String app) {
    return app
        + "."
        + PLATFORM_PROJECT
        + "."
        + publicDomain()
            .orElseThrow(
                () ->
                    new DomainException(
                        503,
                        RUNNER_PLANE_UNCONFIGURED,
                        "qits-workspaces knows no public domain (QITS_DOMAIN is '"
                            + set(domain).orElse("")
                            + "'), so it has no address to give a workspace runner; set"
                            + " QITS_DOMAIN to the platform's dotted public domain"));
  }

  /** The domain, lower-cased and without outer dots, when it has a dot in it; else empty. */
  Optional<String> publicDomain() {
    return set(domain)
        .map(value -> value.toLowerCase(Locale.ROOT).replaceAll("^\\.+|\\.+$", ""))
        .filter(value -> value.indexOf('.') > 0);
  }

  private static Optional<String> set(Optional<String> value) {
    return value == null ? Optional.empty() : value.map(String::trim).filter(v -> !v.isEmpty());
  }
}
