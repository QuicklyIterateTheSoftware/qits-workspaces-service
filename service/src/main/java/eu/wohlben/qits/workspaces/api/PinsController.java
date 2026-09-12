package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspaceContainerFactory;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>What a container launch by THIS process would pull, as the artifact GC's launch-pin source.</b>
 *
 * <p>qits-artifacts collects the registry against a handful of sources read once per run, and an
 * image a service would <em>launch</em> is the one kind nothing else implies: it is pulled cold on
 * the next start, so no deployment, no manifest and no access record keeps it. qits-configuration
 * answers the <em>configured</em> versions; this route answers the <em>effective</em> ones — the
 * values this running process resolved at boot, which lag the configured entry until this service is
 * deployed again. That lag is the whole reason the route exists, and the reason it reads its own
 * config rather than asking qits-configuration: a peer's answer would be the value that has not
 * reached this process yet, which is exactly the version a launch would not pull.
 *
 * <p><b>The config is read through {@link WorkspaceContainerFactory} and nowhere else</b>, on the
 * single-source rule {@link WorkspaceContainerFactory#image()} already states for the reference it
 * composes: the pin answer and the launch must be one value, and a second {@code @ConfigProperty}
 * naming the same key is how two of them appear.
 *
 * <p><b>{@code image} is registry-relative</b> — the configured repo is fully qualified because a
 * bare name would resolve against the host daemon's local store, while the GC names images as the
 * registry holds them ({@code qits/workspace}), so the leading host segment is stripped here. A
 * value that is already registry-relative passes through unchanged.
 *
 * <p><b>A blank version omits the row and an empty answer is a valid 200.</b> The reply says what
 * would be pulled; a half-composed reference names nothing and must not be handed to a keep-rule as
 * if it did. Refusing instead would fail the GC closed over a service that simply launches nothing.
 *
 * <p><b>It is a MACHINE route wearing the same role pair as {@code /gc/branches}</b>:
 * qits-platform-orchestrator reads it with a bearer, and an operator asking what the GC will be told
 * is asking the same question. Nothing is registered for reflection — the method returns its record
 * type rather than a bare {@link jakarta.ws.rs.core.Response}, so the native build indexes it.
 */
@Path("/pins")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:system"})
public class PinsController {

  @Inject WorkspaceContainerFactory containerFactory;

  /**
   * One image a launch would pull.
   *
   * @param image the registry-relative repository, host segment stripped
   * @param version the calver tag this process resolved at boot
   * @param launches what starting it would be — {@code workspace} or {@code editor}
   */
  public record LaunchPin(String image, String version, String launches) {}

  /** The pins, with the instant they were read — nothing here is cached or stored. */
  public record LaunchPins(Instant generatedAt, List<LaunchPin> pins) {}

  // A read, so an agent may make it too (phase 4: agents keep every read, lose writes).
  @RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  @GET
  @Operation(summary = "The container images a launch by this service would pull right now")
  @APIResponse(responseCode = "200", description = "The effective launch pins, image order")
  public LaunchPins pins() {
    return new LaunchPins(
        Instant.now(),
        pins(
            containerFactory.imageRepo(),
            containerFactory.imageVersion(),
            containerFactory.editorImageRepo(),
            containerFactory.editorImageVersion()));
  }

  /**
   * The rows, ordered by image and then by what launches it. Deterministic because the consumer
   * diffs one run's answer against the next, and package-private because the omission rules are
   * worth proving without a config profile per case.
   */
  static List<LaunchPin> pins(
      String imageRepo, String imageVersion, String editorImageRepo, String editorImageVersion) {
    return Stream.of(
            pin(imageRepo, imageVersion, "workspace"),
            pin(editorImageRepo, editorImageVersion, "editor"))
        .flatMap(Optional::stream)
        .sorted(Comparator.comparing(LaunchPin::image).thenComparing(LaunchPin::launches))
        .toList();
  }

  private static Optional<LaunchPin> pin(String repo, String version, String launches) {
    if (repo == null || repo.isBlank() || version == null || version.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new LaunchPin(registryRelative(repo.trim()), version.trim(), launches));
  }

  /**
   * Strips a leading registry host from a configured repository: a first path segment carrying a
   * {@code .} or a {@code :} is a host and never a namespace — {@code
   * registry.dev.localhost:8080/qits/workspace} is {@code qits/workspace}, and {@code
   * qits/workspace-editor} is already what the registry calls it.
   */
  static String registryRelative(String repo) {
    int slash = repo.indexOf('/');
    if (slash < 0) {
      return repo;
    }
    String first = repo.substring(0, slash);
    return first.indexOf('.') >= 0 || first.indexOf(':') >= 0 ? repo.substring(slash + 1) : repo;
  }
}
