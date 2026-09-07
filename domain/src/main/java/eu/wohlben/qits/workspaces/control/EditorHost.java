package eu.wohlben.qits.workspaces.control;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The editor's origin, read: {@code editor.<project>.<environment>.<domain>} → the project label
 * between the first two dots.
 *
 * <p><b>Why the editor has an origin of its own at all.</b> openvscode-server serves from {@code /}
 * with its own service worker, its own websockets and webviews on origins of their own, and this
 * platform rewrites no paths anywhere. So a project's editor is a whole host, aliased at the edge
 * onto this service, and the only thing that says <em>which</em> project's editor a request is for is
 * the name the browser asked for — which arrives here as {@code X-Forwarded-Host}.
 *
 * <p><b>Parsing only, and deliberately no lookup.</b> This class turns a header into a label and
 * stops; {@link EditorProxyTargets} is what turns a label into a workspace, out of this service's own
 * state. Splitting them is what makes the parsing testable without a database and the resolution
 * testable without a header.
 *
 * <h2>One label is read, and the environment label is deliberately not it</h2>
 *
 * <p><b>Position 1 is read; position 2 is not.</b> The name carries an environment label — that is
 * what makes the editor a four-label host and what the edge matches on to pick <em>this</em>
 * environment's processes — and reading it again here would be a second, independent routing
 * decision made by the endpoint of the first. The request is already in this environment's
 * qits-workspaces: there is no other environment it could have arrived at, and nothing this service
 * could do with the answer except disagree with the edge. Worse, it is a header a client may write
 * (see below), so an environment read off it would be a claim rather than a fact, and a claim that
 * matched would authorise nothing while a claim that did not would 404 a working editor. One
 * routing decision, made once, at the edge.
 *
 * <p><b>The minimum stays THREE labels, and it is a floor rather than a shape.</b> Four is what a
 * deployed platform serves, but {@code editor.qits.localhost} is what a local one does, and during
 * the edge's fallthrough removal the three-label short form is still in flight. A parser that
 * demanded four would refuse both — so the check asks only that there is a project label with
 * <em>something</em> behind it for an environment and a domain to be, and lets the edge decide what
 * it is willing to route. Loosening a floor here would cost nothing anyway: a name that reaches this
 * process reached it because the edge routed it.
 *
 * <h2>What the header may and may not be trusted for</h2>
 *
 * <p>{@code X-Forwarded-Host} is written by the edge from the client's own authority, and it is
 * <b>set-if-absent</b> there — a client that sends its own keeps it. So this value is caller-shaped
 * input and is treated as such: it selects a label, and a label selects a row through {@link
 * RepositoryLookup} and {@link eu.wohlben.qits.workspaces.persistence.WorkspaceRepository}. It never
 * selects a host, a port or an address of any kind. That is the same posture {@link
 * DaemonProxyTargets} states at length, and for the same reason: a component of a request that could
 * name an origin would be an SSRF primitive aimed at everything on the platform network.
 *
 * <p>Being caller-shaped is also why the label is validated before anything is looked up. It is a
 * DNS label of a project slug, so it is matched against qits-projects' own slug grammar; a value that
 * is not one names no project and is refused without a query, let alone a registry call.
 */
public final class EditorHost {

  /**
   * The first label of an editor origin. A constant rather than a literal at the call site because
   * the edge's {@code qits.edge.apps} entry has to spell the same word, and one of the two ends
   * changing alone is a 404 nobody can explain.
   */
  public static final String APP_LABEL = "editor";

  /**
   * qits-projects' project-slug grammar, verbatim ({@code ProjectSlug.PATTERN}): lowercase
   * alphanumerics and dashes, never dash-led or dash-trailed, at most 40 characters. Copied rather
   * than imported, because this context does not depend on that one — the same reason {@code
   * QitsConfig.RepositorySection.archetype} is a String. A slug that stops matching this is a
   * qits-projects change, and it fails here as a 404 rather than as a wrong lookup.
   */
  private static final Pattern PROJECT_LABEL =
      Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$");

  private EditorHost() {}

  /**
   * The project label an editor origin names, or empty when this is not one.
   *
   * <p>Empty for every reason at once, on purpose: a blank header, a host that is not {@code
   * editor.…}, one with nothing between the first two dots, and one whose label is not a project
   * slug. The caller turns all of them into a 404 without connecting anywhere, so telling them apart
   * would be a distinction only an attacker could use.
   *
   * @param forwardedHost the raw {@code X-Forwarded-Host}. <b>The first entry wins</b> — the header
   *     is a list and only the client-facing hop's value describes the name a browser asked for; a
   *     port suffix, a trailing dot, surrounding space and letter case are all tolerated.
   */
  public static Optional<String> projectLabel(String forwardedHost) {
    if (forwardedHost == null) {
      return Optional.empty();
    }
    String first = forwardedHost.split(",", -1)[0].trim();
    // Case-normalize first: a Host name is case-insensitive, and every comparison below and every
    // lookup after it is against something lowercase.
    String name = first.toLowerCase(java.util.Locale.ROOT);
    int port = name.indexOf(':');
    if (port >= 0) {
      name = name.substring(0, port);
    }
    while (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    String[] labels = name.split("\\.", -1);
    // Three labels MINIMUM, and it stays three: the app, the project, and something for the
    // environment and domain to be. `editor.qits` names no project's editor — it names a host with
    // nowhere to be served. A deployed platform serves four (`editor.qits.dev.wohlben.dev`), but
    // `editor.qits.localhost` is a real local address and the three-label form is still routable
    // while the edge's default-environment fallthrough is being removed, so the floor admits both.
    if (labels.length < 3 || !APP_LABEL.equals(labels[0])) {
      return Optional.empty();
    }
    // Position 1 and nothing else. The environment label at position 2 is not read here: the edge
    // routed this request to this environment's process already, and reading it again would be the
    // same decision made twice, off a header a client may have written. See the class javadoc.
    String label = labels[1];
    return PROJECT_LABEL.matcher(label).matches() ? Optional.of(label) : Optional.empty();
  }

  /**
   * The name a project's wrapper repository carries, derived from its slug — qits-projects'
   * {@code ProjectService.wrapperName}, which is {@code <slug>-<slug>}.
   *
   * <p><b>Derived and not looked up, because there is nothing to look it up by.</b> The registry
   * answers repositories by id and by {@code (projectId, name)}; a project slug is neither, and this
   * context holds no project table to translate one into the other. What it does hold is the rule
   * qits-projects names a wrapper by — immutable, since the slug is {@code updatable = false} — so
   * the label alone is enough to recognise the wrapper among the repositories this service already
   * has workspaces for. See {@link EditorProxyTargets#resolve}.
   */
  public static String wrapperRepositoryName(String projectLabel) {
    return projectLabel + "-" + projectLabel;
  }
}
