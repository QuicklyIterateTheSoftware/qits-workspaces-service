package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import eu.wohlben.qits.workspaces.stories.support.StoryIdentities;
import eu.wohlben.qits.workspaces.stories.support.StoryNetwork;
import eu.wohlben.qits.workspaces.stories.support.StoryPeers;
import eu.wohlben.qits.workspaces.stories.support.StoryProfile;
import eu.wohlben.qits.workspaces.stories.support.StoryTarget;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The whole service as it is <b>packaged</b>, with the machine-auth gate <b>on</b> — a posture no
 * {@code @QuarkusTest} in this repo can reach. {@code DaemonControlSocketMachineAuthTest} comes
 * closest and stops exactly where this one starts: it flips {@code qits.auth.machine.required} too,
 * but blanks {@code quarkus.oidc.auth-server-url} and hands Quarkus a static {@code
 * quarkus.oidc.public-key} instead — so the shipped pair that matters in a deployment, {@code
 * auth-server-url} plus {@code jwks-path=jwks}, fetched over a real listener before any caller
 * arrives, is exercised nowhere else. The far side here is {@link MockIdp}, which serves that JWKS
 * and <b>records</b> the fetch, so the interaction is assertable on <b>both ends</b>.
 *
 * <p><b>It is the first class of the story catalogue and it owns the boot.</b> Everything else lives
 * under {@code …workspaces.stories.*} and shares this class's {@link StoryProfile} — one profile is
 * one launched process, and two would be two boots, two JWKS fetches and two database sets. This
 * class stays in {@code …workspaces.api} for one reason and it is load-bearing: {@code
 * UserflowClassOrderer} sorts by fully-qualified class name, {@code api} sorts before {@code
 * stories}, and a cumulative recording is attributed by a cursor — so the startup JWKS fetch, which
 * happened before any story existed, lands in whichever story drains <b>first</b>. That has to be
 * the story about it.
 *
 * <p><b>What moved out.</b> The refusals used to be here, both of them, and the second one is now
 * the last story of the last class ({@code stories.refusals.MergeDoorRefusalIT}). The reason is
 * ordering insurance rather than a fact about this class: a token whose {@code kid} the cached key
 * set does not hold makes quarkus-oidc refetch the JWKS before refusing, and that arrow lands in
 * whichever story drains next — which, from here, was whatever ran first. Measured, the mock's
 * unknown key keeps the published {@code kid} and buys no refetch at all; the refusal story asserts
 * that absence, and stays last so a fixture that changed would not silently move an arrow into
 * somebody else's diagram. What stayed here is the wrong-audience refusal, which is about the token
 * rather than about the key.
 *
 * <p>Both stories are browserless (an {@code Interactions} parameter and no {@code Flow}), so the
 * framework's transitive Playwright never launches anything.
 *
 * <p><b>Why this is the sixth {@code *IT.java} and shares nothing with the other five.</b> Those are
 * the docker-backed {@code extended} suite — a real container, a real native daemon, a real image —
 * and they self-skip. The story catalogue needs no docker at all: an embedded postgres this JVM
 * spawns, a mock idp, a stubbed peer plane and a real {@code git http-backend} on loopback. So it is
 * opted in by name rather than by {@code skipITs}, and a run that wants it drags none of the five
 * along.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";

  static final String ACCEPTED_SLUG =
      "on-start-qits-workspaces-fetches-the-platform-s-signing-keys";

  static final String DENIED_SLUG = "a-bearer-cut-for-another-audience-opens-nothing-here";

  /** The route both stories present a bearer to. See the accept story for why it is this one. */
  static final String GUARDED_ROUTE =
      StoryTarget.HISTORY_PATH + "?repositoryId=" + StoryTarget.UNWORKED_REPO_ID;

  /**
   * The same route as the diagram carries it — path only, because the shipped tap labels an edge
   * with the scrubbed path and a query string is an argument rather than an address.
   */
  static final String GUARDED_PATH = StoryTarget.HISTORY_PATH;

  /** How the diagram names this service on both sides of an edge. */
  static final String SERVICE = StoryTarget.SERVICE;

  /** Every credential a story here minted, so the reports can be searched for all of them. */
  private static final java.util.List<String> MINTED = new java.util.ArrayList<>();

  @BeforeAll
  static void tapEveryEndOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(
      value = "On start, qits-workspaces fetches the platform's signing keys",
      category = CATEGORY)
  @UserStoryDescription(
      """
      A freshly deployed qits-workspaces must validate service bearers before any caller arrives:
      at startup it fetches the signing keys (JWKS) from qits-platform-idp — discovery stays off,
      the path is configured — so the very first machine request is judged on the platform's own
      keys. The callers that depend on it are the ones that cannot log in: a workspace daemon
      dialling its control socket, and the machine callers that drive this service's API.

      It asks the idp a second question at boot, and it is not about keys at all. Every workspace
      container holds an idp client commissioned for it, handed back when the container is torn
      down — and a teardown that crashed leaves one behind, holding an identity for a workspace
      that no longer exists. So a fresh process asks what credentials this service still owns and
      gives back every one no active workspace claims. That is the whole of the reconcile's
      outbound half, and it happens before anybody has asked this service for anything.
      """)
  @Order(1)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-workspaces starts with the machine-auth gate on, beside a live qits-platform-idp");
    given().get("/workspaces/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented any
    // token at all. That ordering is the whole claim. A service that fetched keys lazily, on the
    // first bearer, would look identical from this end and fail its first caller after a restart.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(request -> "/idp/jwks".equals(request.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story
        .note("the signing keys were fetched at startup, before this story presented any token")
        .as("jwks-fetched");

    // End (b), this service's side: those keys are what token validation now runs on. The route is a
    // plain REST GET over this context's own store — the workspace history of a repository nobody
    // has worked in — guarded by `qits:admin` like every other door in api/, and reaching NO peer at
    // all. That last property is why it is the route both stories use: what is under test here is
    // the token, and a route that fanned out to the git host would put four arrows in a diagram
    // about a signature.
    //
    // The actor is set BEFORE the call: the tap sees a request, never a narrative role, and this is
    // what makes the observed edge read `a platform service -> qits-workspaces`.
    NetworkCapture.actor("a platform service");
    String platformToken =
        StoryIdentities.machineToken(
            "qits-platform-orchestrator", StoryIdentities.SYSTEM_ROLE, StoryIdentities.ADMIN_ROLE);
    MINTED.add(platformToken);
    StoryIdentities.bearer(given(), platformToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(200)
        .body("entries", notNullValue());
    story
        .note(
            "a platform service's bearer (aud=qits-platform, groups=[qits:system,"
                + " qits:admin]) is accepted")
        .as("history-served");

    // The reconcile's listing. It runs from a StartupEvent observer on its own thread, so it races
    // this story rather than preceding it — and an edge that lands after the drain is an edge in
    // the NEXT story's diagram. Awaiting the far side's own recording is what pins it here, and it
    // is the reason this story's count is three rather than two.
    assertTrue(
        StoryPeers.awaitCall(
            "GET " + StoryPeers.CLIENTS_PATH,
            java.time.Duration.ofSeconds(30)),
        "the commission reconcile never asked qits-platform-idp what this service holds");
    story
        .note(
            "and at boot it also asks the idp which credentials it still holds for itself, so a"
                + " container whose teardown crashed does not leave an identity behind forever")
        .as("commissions-reconciled");
  }

  @UserStory(value = "A bearer cut for another audience opens nothing here", category = CATEGORY)
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys. A signature alone says nothing about who a token
      was cut for, so the audience claim draws that line, and it is checked at the door rather than
      anywhere a caller can reach.

      The line is drawn around the PLATFORM, not around this service. qits-platform-idp stamps
      qits-platform onto every token it mints, whichever client asked and whatever it asked for, so
      every machine caller on qits-net is addressed here — a sibling service's bearer included — and
      what each may do is decided by its roles at the door it knocks on. What must never get in is a
      token cut for somewhere that is not this platform, and that is what is presented here.
      """)
  @Order(2)
  void aBearerForAnotherAudienceIsRefused(Interactions story) {
    // Everything this story sends is an impostor's, so the actor is set once, up front.
    NetworkCapture.actor(StoryIdentities.IMPOSTOR);

    String wrongAudienceToken = StoryIdentities.outsideAudienceToken("an-outside-caller");
    MINTED.add(wrongAudienceToken);
    StoryIdentities.bearer(given(), wrongAudienceToken).get(GUARDED_ROUTE).then().statusCode(401);
    story
        .note(
            "a token addressed to an audience this platform never issues — correctly signed, by"
                + " this platform's own idp — is refused at the door")
        .as("wrong-audience-refused");

    // The negative claim is the point of the story: a refused caller cost this service nothing
    // beyond the 401. Nothing was read, nothing was asked of any peer, and the edge count is what
    // would notice a door that resolved a repository before checking who was asking.
    story
        .note("the refusal reached no store and no peer: one arrow in, and nothing out")
        .as("nothing-behind-the-door");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // assertComplete also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    // Observed on the far side, drained from the mock's recording, and attributed to this story
    // because it is the first one that ran (see the class javadoc on ordering).
    ReportAssertions.assertEdge(
        CATEGORY, ACCEPTED_SLUG, "http", SERVICE, MockIdp.SERVICE_NAME, "GET /idp/jwks -> 200");
    // Observed on the near side, by the framework's shipped tap, with the actor this story set.
    ReportAssertions.assertEdge(
        CATEGORY,
        ACCEPTED_SLUG,
        NetworkEdge.HTTP,
        "a platform service",
        SERVICE,
        "GET " + GUARDED_PATH + " -> 200");
    // …and the other thing a boot does, observed on the peer stub's recording rather than the
    // mock's: the reconcile's listing of the credentials this service holds.
    ReportAssertions.assertEdge(
        CATEGORY,
        ACCEPTED_SLUG,
        NetworkEdge.HTTP,
        SERVICE,
        MockIdp.SERVICE_NAME,
        StoryPeers.read(
            StoryPeers.CLIENTS_PATH));
    // THREE: one door, and the two questions a fresh process asks the idp before anybody arrives.
    ReportAssertions.assertEdgeCount(CATEGORY, ACCEPTED_SLUG, 3);
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "history-served");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "commissions-reconciled");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY,
        DENIED_SLUG,
        NetworkEdge.HTTP,
        StoryIdentities.IMPOSTOR,
        SERVICE,
        "GET " + GUARDED_PATH + " -> 401");
    // ONE edge: the refusal started nothing. Stated as a count and as four absences, because a
    // count alone would not say WHICH peer stayed untouched, and an absence alone would not notice
    // a fifth peer appearing.
    ReportAssertions.assertEdgeCount(CATEGORY, DENIED_SLUG, 1);
    ReportAssertions.assertNoEdgesFrom(CATEGORY, DENIED_SLUG, SERVICE);
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "nothing-behind-the-door");

    for (String slug : java.util.List.of(ACCEPTED_SLUG, DENIED_SLUG)) {
      for (String bearer : MINTED) {
        ReportAssertions.assertNotLeaked(CATEGORY, slug, bearer);
      }
    }
  }
}
