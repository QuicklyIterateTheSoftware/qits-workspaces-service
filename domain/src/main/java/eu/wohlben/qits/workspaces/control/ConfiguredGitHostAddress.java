package eu.wohlben.qits.workspaces.control;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The shipped {@link GitHostAddress}: {@code qits.githost.url} plus the <b>public</b> {@code
 * /git/<projectId>/<repoName>} route qits-githost serves.
 *
 * <p><b>The public route, not the storage-UUID one.</b> qits-githost keys a repository internally by
 * its row id (a per-instance UUID) and serves that id at {@code /git/<repoId>} — but the repository
 * identity ruling reserves that route for the qits-projects client alone; every other consumer,
 * this service included, is answered <b>403</b> there. The address every consumer clones, fetches
 * and pushes through is the public one, {@code /git/<projectId>/<repoName>}: the pair a clone url, a
 * committed pipeline and a person all spell, and the same one the in-container workspace-daemon
 * already self-clones under. So this bean resolves the id it is handed to that pair before building
 * the url — the mirror directories stay keyed by row id (that is {@code RepoMirror}'s business), but
 * the remote is name-addressed.
 *
 * <p><b>The git host is qits-githost, not qits-artifacts.</b> The byte-plane split moved the git
 * smart-HTTP server into a service of its own — a repository is not an artifact, it only shared the
 * storage layout — so both the key and the route segment moved with it. The old spelling was {@code
 * qits.artifacts.url} plus {@code /artifacts/git/<repoId>}; a deployment still passing
 * {@code QITS_ARTIFACTS_URL} configures nothing now, which is the loud failure rather than the
 * quiet one of pushing every ref at a service that no longer serves git.
 *
 * <p><b>{@code @DefaultBean}, exactly as {@code HttpRepositoryLookup} is.</b> It yields to any other
 * bean of the type, which is what lets a test double point the push at a local bare with no change
 * on the production side. Keep the annotation: dropping it makes the two an ambiguous dependency
 * and the build fails at {@code ArcProcessor#validate}, for every test at once.
 *
 * <p>The path segment is spelled here and not configured. It is the git host's contract, not a
 * deployment's choice — {@code GitHostRoutes.BASE} in qits-githost is the same literal — and a
 * second copy in a properties file would be a second place for it to drift.
 */
@ApplicationScoped
@DefaultBean
public class ConfiguredGitHostAddress implements GitHostAddress {

  @Inject Instance<GitHostBearer> bearer;

  /**
   * The repository registry, which turns the row id a caller holds into the {@code (projectId,
   * name)} the public route is addressed by. Mandatory — a workspace cannot exist without a
   * repository — so a resolve that comes back empty or without both fields is a broken registry or a
   * deleted repository, and this fails loudly rather than falling back to the UUID route the git
   * host would answer 403.
   */
  @Inject RepositoryLookup repositories;

  /**
   * Scheme, host and port with <b>no path</b> — the shape {@code qits.projects.url} and {@code
   * qits.observability.url} already use, so that one value works whether the call goes direct on
   * qits-net or through the gateway.
   *
   * <p>The value ships in {@code META-INF/microprofile-config.properties}: the git host's edge host,
   * {@code https://githost.qits.<domain>} (qits-1412), never the {@code <env>-qits-githost} wire
   * alias. No default here, so the one spelling is the shipped file's.
   */
  @ConfigProperty(name = "qits.githost.url")
  String gitHostUrl;

  @Override
  public String fetchUrl(String repoId) {
    RepositoryLookup.RepositoryView view = resolve(repoId);
    return base() + "/git/" + view.projectId() + "/" + view.name();
  }

  /** One address, so reads and writes cannot drift apart in a deployment. */
  @Override
  public String pushUrl(String repoId) {
    return fetchUrl(repoId);
  }

  /**
   * The repository view the public route needs, or a loud failure. A row id that resolves to
   * nothing, or to a view missing either the project id or the name, cannot be name-addressed — and
   * the UUID route it would otherwise fall to is exactly the one qits-githost refuses with a 403, so
   * silently using it would only reproduce the failure one layer down. Fail here, naming the
   * repository.
   */
  private RepositoryLookup.RepositoryView resolve(String repoId) {
    RepositoryLookup.RepositoryView view =
        repositories
            .find(repoId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Cannot address repository "
                            + repoId
                            + " on the git host: it is not in the repository registry"));
    if (isBlank(view.projectId()) || isBlank(view.name())) {
      throw new IllegalStateException(
          "Cannot address repository "
              + repoId
              + " on the git host: the registry answered without a project id and name, so the"
              + " public /git/<projectId>/<repoName> route cannot be built");
    }
    return view;
  }

  private String base() {
    String base = gitHostUrl == null ? "" : gitHostUrl.trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** The mirror turns this into git's one-request {@code Authorization} extra header. */
  @Override
  public Optional<String> httpExtraHeader() {
    if (!bearer.isResolvable()) {
      return Optional.empty();
    }
    return bearer
        .get()
        .token()
        .filter(token -> !token.isBlank())
        .filter(token -> token.indexOf('\r') < 0 && token.indexOf('\n') < 0)
        .map(token -> "Authorization: Bearer " + token);
  }
}
