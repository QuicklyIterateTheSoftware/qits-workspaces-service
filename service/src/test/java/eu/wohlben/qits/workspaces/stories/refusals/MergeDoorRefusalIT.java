package eu.wohlben.qits.workspaces.stories.refusals;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import eu.wohlben.qits.workspaces.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.workspaces.stories.support.StoryGitHost;
import eu.wohlben.qits.workspaces.stories.support.StoryIdentities;
import eu.wohlben.qits.workspaces.stories.support.StoryNetwork;
import eu.wohlben.qits.workspaces.stories.support.StoryPeers;
import eu.wohlben.qits.workspaces.stories.support.StoryProfile;
import eu.wohlben.qits.workspaces.stories.support.StoryTarget;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>Four ways not to get through a door</b>, and every one of them is a claim only a <b>packaged</b>
 * run with the machine-auth gate on can make.
 *
 * <p>The door they are refused at is {@code POST /branches/merge} — one of the two verbs left under
 * {@code /branches} since the release door went to qits-projects. It stands for the whole JSON API
 * here: the claims below are about the gate in front of every route, not about merging, which is why
 * they survived the door they used to be told through.
 *
 * <p>No {@code @QuarkusTest} in this repository can. qits-auth-core's synthetic dev identity holds
 * every platform role and is {@code LaunchMode}-guarded, so under {@code @QuarkusTest} an anonymous
 * request is not anonymous and a role check is not a role check. A launched artifact runs in {@code
 * NORMAL} mode, where the credential really is the only thing opening a door.
 *
 * <p>Three of the four are about the caller and one is about the platform, and the split is the
 * point: a caller who may not act, a caller who is nobody, a token that is not this platform's —
 * and a registry that could not be asked, which is the one case where the honest answer is a 5xx.
 * Folding that into the 404 the door already has would tell a caller its repository had been
 * deleted.
 *
 * <p><b>Every story here is also a negative claim about the git host</b>, and that is what makes
 * them stories rather than status-code assertions. A door that resolved a repository or refreshed a
 * mirror before checking who was asking would look identical from the outside and would let an
 * unauthenticated caller cost this platform a clone.
 *
 * <p><b>This is the last class of the catalogue, and the impostor's is the last story in it.</b>
 * That placement is insurance, and the measurement behind it is worth writing down: a token whose
 * {@code kid} is one the cached key set does not hold makes quarkus-oidc refetch the JWKS before
 * refusing — {@code quarkus.oidc.token.forced-jwk-refresh-interval} is what bounds how often — and
 * that arrow would land in whichever story drained next. The mock's "unknown key" mints a fresh
 * keypair while keeping the <i>published</i> {@code kid}, so the key is found, the signature simply
 * fails, and <b>no refetch is bought</b> (measured: the recording holds exactly the startup fetch
 * and no more). The story below asserts that absence rather than assuming it — and it stays last,
 * because a fixture that one day carried a genuinely unknown {@code kid} would move an arrow into
 * somebody else's diagram and nothing would say so.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MergeDoorRefusalIT {

  static final String CATEGORY = "refusals";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String WRONG_ROLE = "A caller without qits:admin cannot move a branch";

  static final String ANONYMOUS = "An unauthenticated caller never reaches the branch doors";

  static final String OUTAGE = "A registry that cannot be asked is not a repository that is not there";

  static final String STRANGER = "A stranger's token never opens the workspaces API";

  static final String WRONG_ROLE_SLUG = Slugs.slug(WRONG_ROLE);

  static final String ANONYMOUS_SLUG = Slugs.slug(ANONYMOUS);

  static final String OUTAGE_SLUG = Slugs.slug(OUTAGE);

  static final String STRANGER_SLUG = Slugs.slug(STRANGER);

  /** The door every story here is refused at, and the scope it is addressed by. */
  private static final String DOOR =
      StoryTarget.BRANCH_MERGE_PATH + StoryTarget.repositoryQuery(StoryTarget.REFUSAL_REPO_ID);

  /** Every credential a story here minted, so the reports can be searched for all of them. */
  private static final List<String> MINTED = new ArrayList<>();

  @BeforeAll
  static void tapEveryEndOfTheNetwork() {
    StoryNetwork.install();
  }

  /**
   * Nothing armed outlives a story. A refusal that leaked would be a broken peer in somebody else's
   * diagram, and the two would look exactly alike — so this runs whatever the story did, on top of
   * the {@code finally} the one story that arms it already has.
   */
  @AfterEach
  void everyPeerAnswersAgain() {
    StoryPeers.answerNormally();
  }

  @UserStory(value = WRONG_ROLE, category = CATEGORY)
  @UserStoryDescription(
      """
      Merging one branch into another moves a ref in somebody's repository, so it is the platform
      admin's verb and nobody else's — a person's door, and the two that are left under /branches are
      both a person's. A caller here is real — the idp signed its token, the audience is the
      platform's, the request is well formed — and it carries qits:reader, a role this platform
      genuinely issues and this service names on no route at all.

      The answer is 403 rather than 401, and the difference matters to a client: 401 says "I do not
      know who you are", which a caller fixes by presenting a credential, and 403 says "I know
      exactly who you are and it is not enough", which a caller fixes by asking somebody for a role.

      What it costs this platform is one refusal and nothing else. The repository is not resolved,
      the mirror is not refreshed, and the git host never hears about it.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void aCallerWithTheWrongRoleIsRefused(Interactions story) {
    NetworkCapture.actor(StoryIdentities.WRONG_ROLE);
    String bearer =
        StoryIdentities.machineToken("story-reader", StoryIdentities.UNPRIVILEGED_ROLE);
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(StoryTarget.mergeBody(StoryTarget.WORK_BRANCH, "some-epic"))
        .when()
        .post(DOOR)
        .then()
        .statusCode(403);
    story
        .note(
            "a correctly signed bearer for the platform audience, carrying a real platform role"
                + " this service names nowhere, is refused 403 — authenticated, and covered by"
                + " nothing")
        .as("wrong-role-refused");

    story
        .note(
            "and it cost the platform one refusal: no registry lookup, no mirror refresh, nothing"
                + " reached the git host")
        .as("nothing-behind-the-door");
  }

  @UserStory(value = ANONYMOUS, category = CATEGORY)
  @UserStoryDescription(
      """
      Anonymous is not a security state in this service — it means "no name for the audit row", and
      most of what this process does happens behind an edge that has already authenticated somebody.
      What makes this door different is that it is annotated, and an annotated route asks the
      identity for a role.

      With the machine-auth gate on there are exactly two ways to be somebody: an idp-minted bearer,
      or the X-Qits-User / X-Qits-Roles pair the platform edge asserts for a session. That namespace
      is stripped from every inbound request at the edge, unconditionally, which is the entire reason
      the header can be trusted inside. A caller with neither is nobody, and nobody gets a 401.

      It is worth stating out loud because the synthetic dev identity would answer this request
      happily: it holds every platform role, and it exists in dev and test only. A packaged process
      runs in NORMAL mode, and this story is the proof that the difference is real.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(2)
  void anAnonymousCallerIsRefused(Interactions story) {
    NetworkCapture.actor(StoryIdentities.ANONYMOUS);

    given()
        .contentType(ContentType.JSON)
        .body(StoryTarget.mergeBody(StoryTarget.WORK_BRANCH, "some-epic"))
        .when()
        .post(DOOR)
        .then()
        .statusCode(401);
    story
        .note(
            "no bearer and no forwarded session pair: the caller is nobody, and the door answers"
                + " 401 rather than serving the dev identity that would have opened it in %dev")
        .as("anonymous-refused");
  }

  @UserStory(value = OUTAGE, category = CATEGORY)
  @UserStoryDescription(
      """
      Every branch verb resolves its repository through qits-projects before it touches git. Two
      things can go wrong there and they must not answer the same way.

      A repository the registry does not know is a 404: the caller asked about something that is not
      there. A registry that could not be ASKED is a 5xx — because a caller reading a 404 concludes
      its repository has been deleted, and acts on it. Only a 404 from the registry becomes "empty";
      every other status and every transport failure throws, and that distinction is most of what
      the RepositoryLookup port exists for.

      The refusal is armed on the far side rather than requested by the caller: nothing in the URL
      could say "the registry is down tonight", because the registry being down is a property of the
      registry. And it stops the flow at the first hop — the git host is never reached, so an outage
      costs this platform a failed request and not a clone.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(3)
  void aRegistryOutageIsNotAMissingRepository(Interactions story) {
    NetworkCapture.actor(StoryIdentities.PIPELINE);
    String bearer = StoryIdentities.machineToken("qits-ci-step", StoryIdentities.ADMIN_ROLE);
    MINTED.add(bearer);

    try {
      StoryPeers.refuse("/projects/");
      StoryIdentities.bearer(given(), bearer)
          .contentType(ContentType.JSON)
          .body(StoryTarget.mergeBody(StoryTarget.WORK_BRANCH, "some-epic"))
          .when()
          .post(DOOR)
          .then()
          .statusCode(500);
    } finally {
      StoryPeers.answerNormally();
    }
    story
        .note(
            "qits-projects answers 503 and the door answers 5xx — never the 404 it uses for a"
                + " repository that resolves to nothing, because a caller told 404 concludes its"
                + " repository has been deleted")
        .as("outage-is-not-absence")
        ;

    story
        .note(
            "and it stopped at the first hop: the mirror was not refreshed and no ref was read, so"
                + " an unreachable registry costs a failed request rather than a clone")
        .as("stopped-at-the-registry");
  }

  @UserStory(value = STRANGER, category = CATEGORY)
  @UserStoryDescription(
      """
      The last shape of a refusal, and the one that is about the platform's keys rather than about a
      role. A token addressed to this service, carrying the admin role, well formed in every visible
      way — and signed by a private key that has no counterpart in the JWKS this service fetched at
      startup.

      This is the claim the whole startup fetch exists to make. A signature is checked against a key
      set taken from qits-platform-idp before any caller arrived, so "who signed this" has exactly
      one answer and it is not the caller's to supply. Nothing else about the token matters: the
      audience is right, the role is right, and the refusal happens anyway.

      It costs the platform nothing. The signature is checked before the route runs, so the
      repository is never resolved, the mirror is never refreshed, and no ref is read — and the idp
      is not asked either, because the key that failed was one this service already held.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(4)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();
    int fetchesBefore = jwksFetches(idp);

    NetworkCapture.actor(StoryIdentities.IMPOSTOR);
    String bearer = StoryIdentities.strangersToken("story-stranger");
    MINTED.add(bearer);

    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(StoryTarget.mergeBody(StoryTarget.WORK_BRANCH, "some-epic"))
        .when()
        .post(DOOR)
        .then()
        .statusCode(401);
    story
        .note(
            "a token signed by a key with no counterpart in the published JWKS is refused 401,"
                + " however well formed it looks — right audience, right role, wrong signature")
        .as("unknown-key-refused");

    // The measured half, and the reason this story is where it is. The mock's unknown key keeps the
    // PUBLISHED kid, so quarkus finds a key, the signature fails, and no refresh is triggered — a
    // token carrying a kid the cached set does not hold WOULD trigger one, and that arrow would
    // land in whichever story drained next. Asserted rather than assumed, so a fixture that
    // changed would say so here instead of in somebody else's edge count.
    assertEquals(
        fetchesBefore,
        jwksFetches(idp),
        "the refusal cost a JWKS round trip; this story's edge count is no longer the truth about"
            + " what a bad signature costs");
    story
        .note(
            "and the idp was not asked: the key was one this service already held, so the whole"
                + " refusal is local — one arrow in and none out")
        .as("no-round-trip");
  }

  @AfterAll
  static void everyRefusalStoryIsComplete() {
    // --- the wrong role ---------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, WRONG_ROLE_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, WRONG_ROLE_SLUG, "wrong-role-refused");
    ReportAssertions.assertStepId(CATEGORY_SLUG, WRONG_ROLE_SLUG, "nothing-behind-the-door");
    refusedAt(WRONG_ROLE_SLUG, StoryIdentities.WRONG_ROLE, 403);
    startedNothing(WRONG_ROLE_SLUG);

    // --- nobody at all ----------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, ANONYMOUS_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, ANONYMOUS_SLUG, "anonymous-refused");
    refusedAt(ANONYMOUS_SLUG, StoryIdentities.ANONYMOUS, 401);
    startedNothing(ANONYMOUS_SLUG);

    // --- the registry outage -----------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, OUTAGE_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, OUTAGE_SLUG, "outage-is-not-absence");
    ReportAssertions.assertStepId(CATEGORY_SLUG, OUTAGE_SLUG, "stopped-at-the-registry");
    refusedAt(OUTAGE_SLUG, StoryIdentities.PIPELINE, 500);
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        OUTAGE_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryPeers.PROJECTS,
        StoryPeers.label(
            "GET",
            StoryPeers.REPOSITORY_PATH + StoryTarget.REFUSAL_REPO_ID,
            StoryPeers.REFUSED_STATUS));
    // TWO: the door, and the one hop it got to before stopping. The git host is named as an absence
    // because "it stopped at the registry" is a claim about where it did NOT get to.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, OUTAGE_SLUG, 2);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, OUTAGE_SLUG, StoryGitHost.SERVICE_NAME);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, OUTAGE_SLUG, StoryPeers.EVENTS);

    // --- the stranger ------------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, STRANGER_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, STRANGER_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY_SLUG, STRANGER_SLUG, "no-round-trip");
    refusedAt(STRANGER_SLUG, StoryIdentities.IMPOSTOR, 401);
    startedNothing(STRANGER_SLUG);
    // The idp is a fifth absence here and only here: every other refusal in this class could not
    // have reached it, and this one could — a key the cached set did not hold would have.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, STRANGER_SLUG, MockIdp.SERVICE_NAME);

    for (String slug :
        List.of(WRONG_ROLE_SLUG, ANONYMOUS_SLUG, OUTAGE_SLUG, STRANGER_SLUG)) {
      ReportAssertions.assertNotLeaked(CATEGORY_SLUG, slug, StoryProfile.CLIENT_SECRET);
      for (String bearer : MINTED) {
        ReportAssertions.assertNotLeaked(CATEGORY_SLUG, slug, bearer);
      }
    }
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static void refusedAt(String slug, String actor, int status) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        slug,
        NetworkEdge.HTTP,
        actor,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.BRANCH_MERGE_PATH + " -> " + status);
  }

  /** One arrow in and none out — the shape of a refusal that cost the platform nothing. */
  private static void startedNothing(String slug) {
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, slug, 1);
    ReportAssertions.assertNoEdgesFrom(CATEGORY_SLUG, slug, StoryTarget.SERVICE);
    for (String peer :
        List.of(
            StoryPeers.PROJECTS,
            StoryPeers.CONTAINERS,
            StoryPeers.EVENTS,
            StoryGitHost.SERVICE_NAME)) {
      ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, slug, peer);
    }
  }

  private static int jwksFetches(MockIdp idp) {
    return (int)
        idp.recordedRequests().stream().filter(request -> "/idp/jwks".equals(request.path())).count();
  }

}
