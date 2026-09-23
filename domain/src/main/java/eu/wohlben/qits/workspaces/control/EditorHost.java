package eu.wohlben.qits.workspaces.control;

import java.util.Locale;

/**
 * The editor's origin, recognised: a forwarded host whose <b>first label is {@code editor}</b> is the
 * editor's, and nothing else about the name is read.
 *
 * <p><b>Why the editor has an origin of its own at all.</b> openvscode-server serves from {@code /}
 * with its own service worker, its own websockets and webviews on origins of their own, and this
 * platform rewrites no paths anywhere. So the editor is a whole host, aliased at the edge onto this
 * service, and the only thing that says a request is the editor's rather than the workspaces SPA's is
 * the name the browser asked for — which arrives here as {@code X-Forwarded-Host}.
 *
 * <h2>One label is read, and the rest of the name is deliberately not</h2>
 *
 * <p>This class used to parse a <em>project</em> out of position 1 ({@code
 * editor.<project>.<env>.<domain>}) and hand it to a lookup, because there was one editor per
 * project. There is one editor for the platform now, so there is nothing left to select: the name
 * either names the editor or it does not, and the answer is a boolean.
 *
 * <p><b>Grammar-agnostic on purpose, and that is a decision rather than laziness.</b> The origin
 * itself is not changing in this step — it is still spelled with the old per-project grammar — and
 * the label behind {@code editor} is the edge's business either way: the edge matches the
 * environment at its own position and routes the request to <em>this</em> environment's process, so
 * reading anything behind the first label here would be the same routing decision made twice, by the
 * endpoint of the first. Accepting any name that starts with {@code editor} therefore accepts both
 * {@code editor.<slug>.<env>.<domain>} and the shorter {@code editor.<env>.<domain>} the origin is
 * moving to — so the origin can change in its own commit, at the edge and in the client, without
 * this file or its tests moving with it.
 *
 * <p>There is deliberately <b>no minimum label count</b> left either. It used to be three — an app
 * label, a project label, and something for the environment and domain to be — and every part of
 * that floor was about the project label this class no longer reads. A bare {@code editor} reaching
 * this process would be a name the edge routed here, which is the only thing that decides what is
 * routable.
 *
 * <h2>What the header may and may not be trusted for</h2>
 *
 * <p>{@code X-Forwarded-Host} is written by the edge from the client's own authority, and it is
 * <b>set-if-absent</b> there — a client that sends its own keeps it. That is why the value selects
 * <em>nothing addressable</em>: it decides which of this service's two surfaces answers, and the
 * editor surface then resolves the one editor row out of this service's own state ({@link
 * EditorProxyTargets}). It never selects a host, a port or an address of any kind. That is the same
 * posture {@link DaemonProxyTargets} states at length, and for the same reason: a component of a
 * request that could name an origin would be an SSRF primitive aimed at everything on the platform
 * network.
 *
 * <p>It is also why the recognition being permissive costs nothing. A caller that writes its own
 * {@code X-Forwarded-Host: editor…} reaches the editor route, which refuses it outright without the
 * platform's identity headers and, past that, answers about the one editor there is — the same
 * editor that name would have reached honestly.
 */
public final class EditorHost {

  /**
   * The first label of an editor origin. A constant rather than a literal at the call site because
   * the edge's {@code qits.edge.apps} entry has to spell the same word, and one of the two ends
   * changing alone is a 404 nobody can explain.
   */
  public static final String APP_LABEL = "editor";

  private EditorHost() {}

  /**
   * Whether this forwarded host is the editor's.
   *
   * <p>False for every reason at once, on purpose: a blank header, another app's host, and a name
   * that merely begins with the letters {@code editor} without a label boundary ({@code
   * editorial.example.eu}). The caller turns all of them into the surface they were always going to
   * reach, so telling them apart would be a distinction only an attacker could use.
   *
   * @param forwardedHost the raw {@code X-Forwarded-Host}. <b>The first entry wins</b> — the header
   *     is a list and only the client-facing hop's value describes the name a browser asked for; a
   *     port suffix, a trailing dot, surrounding space and letter case are all tolerated.
   */
  public static boolean isEditorHost(String forwardedHost) {
    if (forwardedHost == null) {
      return false;
    }
    String first = forwardedHost.split(",", -1)[0].trim();
    // Case-normalize first: a Host name is case-insensitive, and the comparison below is against
    // something lowercase.
    String name = first.toLowerCase(Locale.ROOT);
    int port = name.indexOf(':');
    if (port >= 0) {
      name = name.substring(0, port);
    }
    while (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    // The FIRST LABEL and not a prefix: `editorial.example.eu` starts with the same seven letters
    // and is somebody else's host. Splitting is what makes the boundary a dot rather than a
    // character count — and a name with no dot at all is one label, which is the bare `editor` case.
    return APP_LABEL.equals(name.split("\\.", -1)[0]);
  }
}
