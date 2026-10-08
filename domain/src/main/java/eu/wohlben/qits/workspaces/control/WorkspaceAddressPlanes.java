package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The configured {@link WorkspaceAddressPlane}: {@code qits.workspace.domain} ({@code QITS_DOMAIN})
 * and the hosts this deployment spells its own registry by — the registry host of {@code
 * qits.workspace.image-repo} and of {@code qits.editor.image-repo}, and {@code
 * registry.<env>.localhost:8080}, the machine spelling the estate's committed files use. Read at
 * the moment of asking, so the RUNNER start's check and the claim's composition see one answer.
 *
 * <p><b>It reads the two image keys itself rather than through {@link WorkspaceContainerFactory}</b>,
 * and only for their registry host. {@link WorkspaceService} injects this bean, and the factory
 * carries required config with no defaults ({@code qits.projects.url}, the oidc-client block): a
 * dependency on it from here would make every {@code @QuarkusTest} that boots the service satisfy
 * all of it — {@link RetiredImageVersionKeys} records the same lesson.
 */
@ApplicationScoped
public class WorkspaceAddressPlanes {

  /** The port of the edge's local machine spelling, {@code registry.<env>.localhost:8080}. */
  static final String LOCAL_EDGE_PORT = "8080";

  @ConfigProperty(name = "qits.workspace.domain")
  Optional<String> domain;

  /**
   * The environment label qits-deployments injects into every container. Read for one thing: the
   * local machine spelling of the registry in this environment, which an image reference may carry.
   */
  @ConfigProperty(name = "QITS_ENVIRONMENT", defaultValue = "dev")
  String environment;

  @ConfigProperty(name = "qits.workspace.image-repo")
  Optional<String> imageRepo;

  @ConfigProperty(name = "qits.editor.image-repo")
  Optional<String> editorImageRepo;

  /**
   * The plane a RUNNER workspace is composed from, and a DIRECT admin or editor row that holds a
   * workspace token is addressed through (qits-1084).
   *
   * @throws EdgePlaneUnconfigured when the domain is no public domain
   */
  public WorkspaceAddressPlane plane() {
    return WorkspaceAddressPlane.of(
        domain == null ? "" : domain.orElse(""), registrySpellings());
  }

  /** The hosts that name the platform's registry, in a fixed order. */
  List<String> registrySpellings() {
    List<String> spellings = new ArrayList<>();
    spellings.add(registryOf(imageRepo == null ? null : imageRepo.orElse(null)));
    spellings.add(registryOf(editorImageRepo == null ? null : editorImageRepo.orElse(null)));
    spellings.add("registry." + environment + ".localhost:" + LOCAL_EDGE_PORT);
    return spellings;
  }

  /** The registry authority a repository reference names — its first path segment — or null. */
  private static String registryOf(String repository) {
    if (repository == null) {
      return null;
    }
    int slash = repository.trim().indexOf('/');
    return slash <= 0 ? null : repository.trim().substring(0, slash);
  }
}
