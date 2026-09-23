package eu.wohlben.qits.workspaces.stories.editor;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import eu.wohlben.qits.workspaces.control.EditorWorkspace;
import eu.wohlben.qits.workspaces.stories.creation.WorkspaceProvisionIT;
import eu.wohlben.qits.workspaces.stories.support.StoryDaemon;
import eu.wohlben.qits.workspaces.stories.support.StoryGitHost;
import eu.wohlben.qits.workspaces.stories.support.StoryIdentities;
import eu.wohlben.qits.workspaces.stories.support.StoryNetwork;
import eu.wohlben.qits.workspaces.stories.support.StoryPeers;
import eu.wohlben.qits.workspaces.stories.support.StoryProfile;
import eu.wohlben.qits.workspaces.stories.support.StoryTarget;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The web editor's door, and the one workspace there is behind it</b> — a browser posts {@code
 * POST /workspaces/api/editor/ensure} with no parameters at all, and the service finds or writes the
 * platform's single editor workspace and begins ensuring its container.
 *
 * <h2>Why this is a story about an ABSENCE as much as a presence</h2>
 *
 * <p>The editor used to be a project's <b>wrapper</b> repository's main workspace — one per project,
 * launched from the richer image because of what that workspace <em>was</em> — and this story used to
 * register that wrapper, build its origin on the git host, and name it in the request. All of that
 * is gone, and what is left is the shape of the change: <b>no repository is named, none is cloned, and
 * the git host is not touched at all</b>. The registry IS read, twice, and that is the other half of
 * the same change rather than a leftover of the old one: the editor holds no repository, so what it
 * checks out is every project's wrapper, and naming those is what the two reads are. The editor belongs to no repository, so the
 * container spec carries no repository id, no project and no branch for the daemon to clone from,
 * and there is no branch-still-there check to make. A diagram with a single arrow to qits-githost in
 * it would be this change not having landed; the edge count is what says so.
 *
 * <p>The contrast with the provision story one category up is the point: that one forks a new branch
 * into being with a {@code git-receive-pack}, clones objects, and reads the registry to address the
 * repository. This one does neither of the first two, and reads the registry for a different
 * reason entirely — not to address a repository it is about to clone, but to enumerate the estate.
 *
 * <h2>The door begins the ensure, and the story plays the container to complete it</h2>
 *
 * <p>{@code beginEnsureContainer} answers at once with a technical process id and does the provision
 * on another thread, which then waits for the container's daemon to dial home — {@code
 * WorkspaceDaemonRegistry.awaitProvision}. So a story that stopped at the 201 would leave that thread
 * blocked, its container PUT and its commission racing whichever diagram is open next. {@link
 * StoryDaemon} dials the control socket and reports {@code Provisioned} exactly as {@code
 * WorkspaceProvisionIT} does, which completes the provision cleanly and drains its far-side traffic
 * before this story returns — the standard treatment for asynchronous far-side work, and the reason
 * the {@code networkHash} settles.
 *
 * <h2>Nothing in the editor's name is generated any more</h2>
 *
 * <p>The provision story authors its repository id because eight characters of it travel <i>inside</i>
 * a container name, where {@code Labels} correctly refuses to rewrite them and a generated value
 * would move the {@code networkHash} every run. The editor needs no such care: its container is
 * {@code qits-ws-editor-editor}, both halves constant, because there is one of it.
 *
 * <h2>It runs after the provision story, and that is a real dependency</h2>
 *
 * <p>quarkus-oidc-client caches this service's containers-client token for an hour, and {@code
 * WorkspaceProvisionIT} is where it is first minted. So the container PUT here reuses it and this
 * diagram carries <b>no</b> {@code POST /idp/token} arrow — the commission is HTTP Basic and mints
 * none either. Run this class on its own and it inherits that arrow and fails its own edge count,
 * loudly, which is the right way for the assumption to break.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EditorEnsureIT {

  static final String CATEGORY = "editor";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String OPENED = "Opening the editor starts the one editor workspace";

  static final String OPENED_SLUG = Slugs.slug(OPENED);

  /** The head the story's daemon reports — authored, so it survives into a step and no label. */
  private static final String PROVISIONED_HEAD = "story-editor-head";

  /** How long the story is willing to play the container before calling the provision broken. */
  private static final Duration PATIENCE = Duration.ofSeconds(90);

  /** Every credential a story here minted, so the reports can be searched for all of them. */
  private static final List<String> MINTED = new ArrayList<>();

  /** Where the launched process answers. Read per test: RestAssured's port is -1 in @BeforeAll. */
  private String baseUrl;

  @BeforeAll
  static void tapEveryEndOfTheNetwork() {
    StoryNetwork.install();
  }

  @BeforeEach
  void locateTheLaunchedProcess() {
    baseUrl = RestAssured.baseURI + ":" + RestAssured.port;
  }

  @UserStory(value = OPENED, category = CATEGORY)
  @UserStoryDescription(
      """
      Somebody opens the editor. There is one editor on this platform — one row, one container, one
      volume — so the door is one idempotent sentence with nothing in it: there should be an editor.
      No query parameter, an empty body, and two people coming in from two different projects' pages
      send the identical request and reach the identical container.

      The row is found or written. It belongs to no repository and claims no branch, which is what
      makes the rest of this diagram an absence: nothing is asked of the repository registry, and the
      git host is not touched at all — there is no branch to create, none to check is still there,
      and no objects to mirror. Contrast the ordinary provision, which forks a new branch into being
      with a push and reads the registry to address it.

      Then the container, begun the moment the door decides one is worth starting: this process holds
      no docker socket, so it commissions the workspace's own idp credential and asks qits-containers
      to put a container under a spec it composes — the EDITOR image, and the editor environment the
      in-container daemon reads to supervise openvscode-server. The spec deliberately carries BLANK
      repository, project and branch names: a daemon told to clone the row's sentinel id would go
      looking for a repository that does not exist. The verb answers at once with a technical process
      id, because an image pull is minutes of somebody else's work, and the provision is not complete
      until the daemon inside dials the control socket. Until then the editor is coming up, which is
      what a reader who reloaded mid-start rejoins rather than a second one being started.
      """)
  @UserflowRunsAfter(WorkspaceProvisionIT.class)
  @Order(1)
  void openingTheEditorStartsTheOneEditorWorkspace(Interactions story) throws Exception {
    // NO FIXTURE AT ALL, and that is the assertion this method opens with. There is no repository to
    // register and no origin to build: the editor belongs to none, so nothing about it can be
    // arranged anywhere but in this service's own database, by the door itself.
    NetworkCapture.actor(StoryIdentities.OPERATOR);
    JsonPath opened =
        StoryIdentities.person(given())
            // A body-less POST still has to declare a content type: the door is
            // @Consumes(APPLICATION_JSON), and RESTEasy answers 415 to a request that arrives with
            // none.
            .contentType(ContentType.JSON)
            .when()
            .post(StoryTarget.EDITOR_ENSURE_PATH)
            .then()
            // 201: this call started the editor. The body is BARE — four scalars a two-second poll
            // reads directly, not the {workspace: …} envelope the WorkspaceDto routes carry.
            .statusCode(201)
            .body("workspaceId", notNullValue())
            // Nothing is serving yet: the container is only now being asked for, and the daemon has
            // not dialled, so the editor is not ready and the caller waits.
            .body("editorState", org.hamcrest.Matchers.nullValue())
            .body("editorReady", equalTo(false))
            .extract()
            .jsonPath();
    long rowId = Long.parseLong(opened.getString("workspaceId"));
    story
        .note(
            "the browser posts nothing at all and gets 201 — there is one editor, so the door wrote"
                + " its row and began starting its container, answering the workspace row id the"
                + " container verbs are keyed by, with the editor not yet ready")
        .as("editor-requested");

    // What qits-containers was asked for — learned from the far side's own recording, because
    // nothing in this JVM is on that path and the provision runs on another thread.
    assertTrue(
        StoryPeers.awaitCall(
            "PUT " + StoryPeers.containerPath(StoryTarget.EDITOR_CONTAINER_NAME), PATIENCE),
        "qits-containers was never asked for the editor's container");
    String spec = StoryPeers.lastEnsureRequest();
    assertNotNull(spec, "no workload spec reached qits-containers");
    assertTrue(
        spec.contains("QITS_WORKSPACE_DAEMON_EDITOR_ENABLED"),
        "the container was not launched as an editor — the row's editor column was not read");
    assertTrue(
        spec.contains("QITS_WORKSPACE_DAEMON_URL")
            && spec.contains("/workspaces/daemon/" + rowId),
        "the editor container was not told where to dial home");
    // …and it was told to clone NOTHING. The sentinel repository id must not reach the daemon: it
    // names no repository, so a daemon handed it would fail on a clone nobody could explain.
    assertFalse(
        spec.contains("\"QITS_WORKSPACE_DAEMON_REPOSITORY_ID\":\"" + EditorWorkspace.REPOSITORY_ID),
        "the editor's container was told to clone its own sentinel repository id");
    story
        .note(
            "the container asked for is the one editor's — a constant name, so everybody's editor is"
                + " this container — and its spec carries the editor environment the in-container"
                + " daemon reads to supervise openvscode-server, with the repository, project and"
                + " branch names blank because the editor belongs to none of them")
        .as("editor-container-ensured");

    // From here the story plays the container, so the provision it began completes cleanly rather
    // than failing on a daemon that never arrives — which drains its far-side traffic before return.
    NetworkCapture.actor(StoryIdentities.DAEMON);
    String daemonBearer =
        StoryIdentities.machineToken("workspace-" + rowId, StoryIdentities.SYSTEM_ROLE);
    MINTED.add(daemonBearer);
    try (StoryDaemon daemon = StoryDaemon.dial(baseUrl, rowId, daemonBearer)) {
      daemon.hello(StoryTarget.EDITOR_LABEL, "", "");
      assertNotNull(daemon.awaitAck(), "the host did not acknowledge the daemon's Hello");
      story
          .note(
              "the editor's container dials the control socket with a qits:system bearer of its"
                  + " own and the host acknowledges — the same control plane every workspace"
                  + " container opens, because an editor IS a workspace container")
          .as("daemon-dialled-home");

      // Back to the operator before the wait: the browser polls the technical process, and the
      // shipped tap stamps whichever actor is current on every request it sees. The socket frames
      // are unaffected — StoryDaemon names both ends of a frame itself.
      NetworkCapture.actor(StoryIdentities.OPERATOR);
      awaitProvisionAccepted(daemon, rowId);
      story
          .note(
              "the daemon reports Provisioned and the host's wait ends — the editor's workspace is"
                  + " up, and a caller's next poll of the door rejoins it instead of starting a"
                  + " second one")
          .as("provisioned");
    }
  }

  @AfterAll
  static void theEditorStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, OPENED_SLUG, UserflowReport.PASSED);
    for (String step :
        List.of("editor-requested", "editor-container-ensured", "daemon-dialled-home", "provisioned")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, OPENED_SLUG, step);
    }

    // What the browser sent: the door once (201), and the process poll folded to one arrow (many
    // requests, one (kind, from, to, label) — the row id scrubs and the loop draws once).
    from(NetworkEdge.HTTP, "POST " + StoryTarget.EDITOR_ENSURE_PATH + " -> 201");
    from(NetworkEdge.HTTP, "GET " + StoryTarget.WORKSPACE_LABEL_PATH + "/active-process -> 200");

    // The credential this editor's container was commissioned with, minted at qits-platform-idp for
    // it alone. No token arrow beside it: the containers client's token was minted in the provision
    // story and is cached, and the commission itself authenticates with HTTP Basic.
    to(StoryPeers.IDP, StoryPeers.posted(StoryPeers.CLIENTS_PATH, 201));

    // The container orchestrator: the "is it there" that says it is not, and the put that asks for
    // it. No listing — the door reads no workspace list; it starts one container and waits.
    to(
        StoryPeers.CONTAINERS,
        StoryPeers.label("GET", StoryPeers.containerPath(StoryTarget.EDITOR_CONTAINER_NAME), 404));
    to(
        StoryPeers.CONTAINERS,
        StoryPeers.label("PUT", StoryPeers.containerPath(StoryTarget.EDITOR_CONTAINER_NAME), 200));

    // The plane the framework ships no tap for: the dial and the three frames that complete the
    // provision the door began.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        OPENED_SLUG,
        NetworkEdge.SOCKET,
        StoryIdentities.DAEMON,
        StoryTarget.SERVICE,
        "CONNECT " + StoryTarget.DAEMON_LABEL_PATH);
    for (String frame : List.of("hello", "provisioned")) {
      ReportAssertions.assertEdge(
          CATEGORY_SLUG,
          OPENED_SLUG,
          NetworkEdge.EVENT,
          StoryIdentities.DAEMON,
          StoryTarget.SERVICE,
          frame);
    }
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        OPENED_SLUG,
        NetworkEdge.EVENT,
        StoryTarget.SERVICE,
        StoryIdentities.DAEMON,
        "ack");

    // ELEVEN across three planes: two browser doors, one commission, two container calls, one dial,
    // three frames — and TWO registry reads, which are the editor's project list being composed.
    // Three planes and not four: there is still no arrow to qits-githost at all, which is the half
    // of the change the count is here to protect.
    //
    // THE TWO REGISTRY READS ARE THE EDITOR'S CLONE LIST, and they are a presence this story asserts
    // rather than an absence. The editor holds no repository of its own, so what it checks out is
    // every project's wrapper — and naming those takes the two doors qits-projects already has: one
    // GET per repository this platform is worked in, to learn the project that owns it, then one
    // listing per project, to learn which of its repositories is the wrapper. The workspace the
    // provision story left ACTIVE is the whole estate here, so that is one of each.
    //
    // It is still what would notice the thing this path must never start doing: any git operation
    // whatsoever. A status poll at qits-containers would show up here too; the design deliberately
    // waits on the socket instead.
    //
    // What is NO LONGER asserted is an absence of qits-projects arrows. That absence was right while
    // the editor belonged to no repository AND cloned nothing — a registry round trip could then only
    // have been the sentinel id being looked up and not found. It clones the estate now, so the
    // registry is exactly where the estate is named, and the assertion below pins WHICH reads those
    // are rather than that there are none.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, OPENED_SLUG, 11);
    // The named absence that survives, and it is the point of the change rather than incidental.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, OPENED_SLUG, StoryGitHost.SERVICE_NAME);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        OPENED_SLUG,
        List.of(StoryIdentities.OPERATOR, StoryIdentities.DAEMON, StoryTarget.SERVICE));
    // Named absence: the editor door announces nothing to qits-events. Only a RELEASE publishes an
    // SCMRelease, and opening an editor is neither a release nor an integrate.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, OPENED_SLUG, StoryPeers.EVENTS);

    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, OPENED_SLUG, StoryProfile.CLIENT_SECRET);
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, OPENED_SLUG, StoryPeers.MACHINE_TOKEN);
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, OPENED_SLUG, StoryPeers.COMMISSIONED_SECRET);
    for (String bearer : MINTED) {
      ReportAssertions.assertNotLeaked(CATEGORY_SLUG, OPENED_SLUG, bearer);
    }
  }

  // --- driving the container half -----------------------------------------------------------------

  /**
   * Report {@code Provisioned} until the host's provision is observably over — the repeat is
   * insurance against a terminal frame that arrives before {@code awaitProvision} has registered its
   * slot, and a repeat is the same {@code (kind, from, to, label)}, so it is one arrow either way.
   */
  private void awaitProvisionAccepted(StoryDaemon daemon, long rowId) throws Exception {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (true) {
      daemon.provisioned(StoryTarget.EDITOR_LABEL, PROVISIONED_HEAD);
      if (awaitProcessOver(rowId, Duration.ofSeconds(5))) {
        return;
      }
      if (System.nanoTime() >= deadline) {
        fail("the provision never completed; the editor workspace is still running a process");
      }
    }
  }

  /** Poll the workspace's active process until there is none — the provision's own "done". */
  private boolean awaitProcessOver(long rowId, Duration patience) throws Exception {
    long deadline = System.nanoTime() + patience.toNanos();
    while (true) {
      String active =
          StoryIdentities.person(given())
              .when()
              .get(StoryTarget.workspacePath(rowId) + "/active-process")
              .then()
              .statusCode(200)
              .extract()
              .path("technicalProcessId");
      if (active == null) {
        return true;
      }
      if (System.nanoTime() >= deadline) {
        return false;
      }
      Thread.sleep(250);
    }
  }

  private static void from(String kind, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, OPENED_SLUG, kind, StoryIdentities.OPERATOR, StoryTarget.SERVICE, label);
  }

  private static void to(String peer, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, OPENED_SLUG, NetworkEdge.HTTP, StoryTarget.SERVICE, peer, label);
  }
}
