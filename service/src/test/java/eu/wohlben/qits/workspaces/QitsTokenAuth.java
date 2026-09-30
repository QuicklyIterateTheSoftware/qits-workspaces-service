package eu.wohlben.qits.workspaces;

import java.net.http.HttpRequest;

/**
 * The {@code Authorization} header a download from qits-artifacts needs on the EDGE plane and must
 * not send on the internal one.
 *
 * <p>An internal step reaches the registry through the internal alias, which needs no auth; an EDGE
 * step reaches it through the public edge, which refuses an anonymous read and accepts the run's own
 * job token — {@code QITS_TOKEN}, an opaque {@code qits_tok_…} bearer the step container carries —
 * as {@code Authorization: Bearer <token>}. A pin IT's own downloads of a real, pinned daemon run on
 * whichever plane gates this repository, so they carry the same header a composed step's {@code
 * curl}/{@code wget} would. Same class, same rule, as qits-ci's {@code QitsTokenAuth}.
 */
public final class QitsTokenAuth {

  private QitsTokenAuth() {}

  /** Adds the header to {@code request} when {@code QITS_TOKEN} is set, and does nothing otherwise. */
  public static void addIfPresent(HttpRequest.Builder request) {
    addIfPresent(request, System.getenv("QITS_TOKEN"));
  }

  /** The decision itself, with the token handed in so a test can make it without an environment. */
  static void addIfPresent(HttpRequest.Builder request, String token) {
    if (sent(token)) {
      request.header("Authorization", "Bearer " + token);
    }
  }

  /**
   * What a failed download says about the read that failed: a 401 or 403 is the door refusing the
   * read, not an artifact that was never published, and whether a token went with it is the first
   * thing whoever reads the failure needs to know.
   */
  public static String describe(int status) {
    return describe(status, System.getenv("QITS_TOKEN"));
  }

  static String describe(int status, String token) {
    if (status == 401 || status == 403) {
      return "The read was refused, which says nothing about whether the artifact exists: "
          + (sent(token)
              ? "QITS_TOKEN was sent as a bearer and the registry did not accept it."
              : "no token was sent, because QITS_TOKEN is unset — an anonymous read, which the"
                  + " public edge refuses.");
    }
    return "The pom pins a version whose daemon was never published or no longer exists.";
  }

  private static boolean sent(String token) {
    return token != null && !token.isBlank();
  }
}
