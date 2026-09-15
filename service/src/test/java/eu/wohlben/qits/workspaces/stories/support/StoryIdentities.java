package eu.wohlben.qits.workspaces.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.restassured.specification.RequestSpecification;

/**
 * The two identity tracks a launched qits-workspaces accepts, one helper each.
 *
 * <ul>
 *   <li><b>{@code qits:admin} is a PERSON's</b>, and it arrives as the {@code X-Qits-User} / {@code
 *       X-Qits-Roles} pair the platform edge asserts for an authenticated admin session. That
 *       namespace is stripped from every inbound request at the edge, unconditionally, which is the
 *       entire reason a header can be trusted inside.
 *   <li><b>A MACHINE's is an idp-minted bearer</b>, and it carries whichever roles the idp copied
 *       into its {@code groups} claim — quarkus-oidc reads them as roles with no configuration at
 *       all. A pipeline step's commissioned credential carries whatever it was granted; the daemon
 *       control socket wants {@code qits:system}.
 * </ul>
 *
 * <p><b>Almost every door here is {@code @RolesAllowed("qits:admin")}</b> and the two tracks open it
 * alike: an operator works in the branch list, and a commissioned container drives the same routes
 * with a bearer of its own. The exceptions are {@code /gc/branches}, which adds
 * {@code qits:system} because its caller is qits-platform-orchestrator's nightly run, and the daemon
 * control socket, which is {@code qits:system} <b>only</b> because its caller is a container.
 *
 * <p><b>The synthetic {@code %test} dev user is not available here, and that is the point.</b>
 * qits-auth-core's dev identity holds every platform role and is {@code LaunchMode}-guarded, while a
 * launched artifact runs in {@code NORMAL} mode — so an anonymous request really is anonymous and
 * the credentials below are the only thing opening these doors. No {@code @QuarkusTest} in this
 * repository can make a refusal claim about a packaged process.
 *
 * <p>Minting is local crypto against the keypair {@link MockIdp} parked at startup: it makes no
 * request to the mock, which is why no story's diagram carries an arrow for GETTING a token. The
 * tokens that <b>are</b> fetched over the wire are this service's own three outbound ones — see
 * {@link StoryPeers}.
 */
public final class StoryIdentities {

  /**
   * The one audience this service enforces, and the shipped value of {@code
   * quarkus.oidc.token.audience} — the profile overrides nothing, so what a launched artifact really
   * ships is what these stories present a token for. qits-idp stamps it onto every token it mints,
   * whichever client asked for whatever, so every machine caller on the platform is addressed here
   * and its ROLES decide what it may do.
   */
  public static final String AUDIENCE = "qits-platform";

  /**
   * An audience no token of this platform's carries. A sibling service's name would not do: a peer's
   * bearer carries {@code qits-platform} too and is let in, judged on its roles from there. What the
   * door refuses is a token cut somewhere else entirely.
   */
  public static final String OUTSIDE_AUDIENCE = "somebody-elses-platform";

  /** The role every door in {@code api/} names, and the one a person's session carries. */
  public static final String ADMIN_ROLE = "qits:admin";

  /** The machine role: the gc door's second name, and the daemon control socket's only one. */
  public static final String SYSTEM_ROLE = "qits:system";

  /** A real platform role this service names on no route — the 403 that is not a 401. */
  public static final String UNPRIVILEGED_ROLE = "qits:reader";

  /** The header the edge names the logged-in person in. */
  public static final String USER_HEADER = "X-Qits-User";

  /** …and the one it asserts that person's roles in, comma-separated. */
  public static final String ROLES_HEADER = "X-Qits-Roles";

  // --- how a diagram names each initiator ---------------------------------------------------------

  /** The person who creates a workspace, reads the list and merges a branch in it. */
  public static final String OPERATOR = "an operator";

  /** A build container driving this API with its own commissioned credential. */
  public static final String PIPELINE = "a pipeline step";

  /** The in-container workspace-daemon, which dials out and is never dialled. */
  public static final String DAEMON = "a workspace daemon";

  /** Nobody at all: no bearer, no forwarded pair. */
  public static final String ANONYMOUS = "an unauthenticated caller";

  /** A real caller, correctly authenticated, holding a role this service never names. */
  public static final String WRONG_ROLE = "a caller with the wrong role";

  /** A credential that looks right and is not: signed by a key the published JWKS never carried. */
  public static final String IMPOSTOR = "an impostor";

  /** The account the person stories log in as. Authored, so it survives label scrubbing. */
  public static final String OPERATOR_ACCOUNT = "story-operator";

  private StoryIdentities() {}

  /**
   * A machine's bearer for the platform audience.
   *
   * <p>Minted fresh per call rather than cached: a token is a credential, and a helper that handed
   * the same string to two stories would make {@link
   * eu.wohlben.qits.userflows.report.ReportAssertions#assertNotLeaked} a weaker claim than it reads
   * as.
   */
  public static String machineToken(String subject, String... roles) {
    return MockIdp.attach().token().subject(subject).audience(AUDIENCE).groups(roles).mint();
  }

  /** A token addressed to somewhere that is not this platform — see {@link #OUTSIDE_AUDIENCE}. */
  public static String outsideAudienceToken(String subject) {
    return MockIdp.attach()
        .token()
        .subject(subject)
        .audience(OUTSIDE_AUDIENCE)
        .groups(ADMIN_ROLE)
        .mint();
  }

  /** Addressed here, well formed, signed by a key the published JWKS never carried. */
  public static String strangersToken(String subject) {
    return MockIdp.attach()
        .token()
        .subject(subject)
        .audience(AUDIENCE)
        .groups(ADMIN_ROLE)
        .signedByUnknownKey()
        .mint();
  }

  /** {@code given()} with one machine's bearer on it. */
  public static RequestSpecification bearer(RequestSpecification request, String token) {
    return request.header("Authorization", "Bearer " + token);
  }

  /** {@code given()} with the pair the edge asserts for a logged-in admin session. */
  public static RequestSpecification person(RequestSpecification request) {
    return person(request, OPERATOR_ACCOUNT, ADMIN_ROLE);
  }

  /** …and the same pair for a session holding some other role, which is how a 403 is asked for. */
  public static RequestSpecification person(
      RequestSpecification request, String user, String roles) {
    return request.header(USER_HEADER, user).header(ROLES_HEADER, roles);
  }
}
