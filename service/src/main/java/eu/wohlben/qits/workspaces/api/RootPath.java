package eu.wohlben.qits.workspaces.api;

/**
 * Normalizes a configured path prefix to a strippable/concatenable form, for raw-router path
 * arithmetic. Six lines of string handling, but it reconciles the one split that keeps catching
 * people out: <strong>raw Vert.x routes do not follow {@code quarkus.rest.path}</strong>, and route
 * patterns <em>do</em> follow {@code quarkus.http.root-path} while {@code rc.request().path()} does
 * not. Two config keys, opposite directions; use this on both rather than doing it by hand.
 *
 * <p>{@code quarkus.http.root-path} — Quarkus mounts the route router under it, so route
 * <em>patterns</em> stay relative but {@code rc.request().path()}/{@code normalizedPath()} return
 * the full path, and any handler that parses its own path must strip the prefix first ({@link
 * ContainerProxyRoute}). Only non-{@code /} when qits itself runs as a managed service (the
 * qits-in-qits start script bridges {@code -Dquarkus.http.root-path}); the normal deployment's root
 * path is {@code /} and the prefix is empty.
 *
 * <p>{@code quarkus.rest.path} — the JAX-RS prefix, which a raw route must carry itself if it is to
 * land beside a JAX-RS one ({@link CaptureCorsRoute}, whose preflight is worthless anywhere but on
 * {@link CaptureResource}'s exact path).
 *
 * <p>Duplicated from the monorepo's app-shell {@code eu.wohlben.qits.http.RootPath}, which is
 * monolith-only and therefore in no target's manifest. Six lines of string arithmetic with no
 * state; a shared jar for it would cost more than the copy.
 */
public final class RootPath {

  private RootPath() {}

  /** The strippable prefix: {@code ""} for root, else leading-slash, no trailing slash. */
  public static String prefix(String rootPath) {
    String p = rootPath == null || rootPath.isEmpty() ? "/" : rootPath;
    if (!p.startsWith("/")) {
      p = "/" + p;
    }
    return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
  }
}
