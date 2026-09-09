package eu.wohlben.qits.workspaces.stories.editor;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
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
 * <b>The web editor's door, and the one workspace it rides</b> — a browser posts {@code POST
 * /workspaces/api/editor/ensure} for a project's <b>wrapper</b> repository, and the service resolves
 * that wrapper's main workspace and begins ensuring its container.
 *
 * <h2>Why this is a story about an ABSENCE as much as a presence</h2>
 *
 * <p>The editor is not a new thing with a lifecycle: it is the wrapper's main workspace {@code
 * WorkspaceService.createMainWorkspace} already maintains ({@code WorkspacePostures.isWrapperMain}
 * — archetype {@code PROJECT} and branch == the repository's main branch), launched from the richer
 * editor image. So the door <b>creates nothing new</b>: it makes sure that one row exists and asks
 * for its container the way every other caller does. That is exactly what makes the diagram worth
 * pinning. A fresh workspace on a NEW branch pushes the branch into being — the {@code
 * git-receive-pack} arrow the provision story carries. This one does <b>not push at all</b>: the
 * main branch already exists, so the only git this ensure does is the {@code ls-remote} that asks
 * whether the branch is still there. That single check is two arrows — the ref advertisement and,
 * under git's protocol v2, the {@code git-upload-pack} the {@code ls-refs} command rides — and there
 * is <b>no {@code git-receive-pack}</b>, because a wrapper-main ensure creates no ref. Nothing else
 * touches the host: only one {@code info/refs} and one {@code git-upload-pack} appear, so there is
 * not even a mirror clone of objects (a clone opens its own advertisement) — just the one ref query.
 * "A wrapper-main ensure is idempotent, and it neither clones objects nor pushes" is a claim only a
 * count can make, and this is the count that makes it.
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
 * <h2>The wrapper's id is AUTHORED, for the reason the provision story's is</h2>
 *
 * <p>The editor container is {@code qits-ws-main-<repoId[0:8]>}, and eight characters of an id
 * <i>inside</i> a longer segment is something {@code Labels} refuses to rewrite — so a generated id
 * there would put a run-local value in a hashed label. {@link StoryTarget#WRAPPER_REPO_ID} is a
 * literal for exactly that reason.
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

  static final String OPENED = "Opening a project's editor starts its wrapper-main workspace";

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
      Somebody opens a project's editor. There is no "editor" to create: a project's editor is the
      workspace that rides its WRAPPER repository's main branch — the per-project singleton the
      service already maintains — started from the editor image because of what that workspace IS.
      So the door is one idempotent sentence: there should be an editor for this project.

      The scope is the wrapper repository, named as a query parameter the way a workspace listing
      names its repository and for the same reason — a workspace is not a sub-resource of a
      repository here, which holds the id as an opaque string in another database. The body is empty.
      The door refuses a repository that is not a wrapper with a 400, rather than starting a plain
      workspace nobody could ever poll to ready; this repository is a wrapper, so it resolves.

      Then the main workspace. It already exists or is written on the branch it claims — and because
      that branch is the main branch, which the git host already holds, there is NOTHING to create
      on the host: no branch ref pushed, no mirror cloned. The one thing asked of the git host is
      whether the branch is still there at all. Contrast the ordinary provision, which forks a new
      branch into being with a push.

      Then the container, begun the moment the door decides one is worth starting: this process holds
      no docker socket, so it commissions the workspace's own idp credential and asks qits-containers
      to put a container under a spec it composes — the EDITOR image, and the editor environment the
      in-container daemon reads to supervise openvscode-server. The verb answers at once with a
      technical process id, because a pull and a clone are minutes of somebody else's work, and the
      provision is not complete until the daemon inside dials the control socket and reports that the
      checkout is populated. Until then the editor is coming up, which is what a reader who reloaded
      mid-start rejoins rather than a second one being started.
      """)
  @UserflowRunsAfter(WorkspaceProvisionIT.class)
  @Order(1)
  void openingAProjectsEditorStartsItsWrapperMainWorkspace(Interactions story) throws Exception {
    // A project wrapper: archetype PROJECT with a main branch, which is the whole of being an
    // editor's repository. Its origin exists on the git host so the branch-existence check finds it.
    StoryGitHost.createRepository(StoryTarget.PROJECT, StoryTarget.WRAPPER_REPO, false);
    StoryPeers.register(
        new StoryPeers.Repository(
            StoryTarget.WRAPPER_REPO_ID,
            StoryTarget.PROJECT,
            StoryTarget.WRAPPER_REPO,
            StoryTarget.MAIN,
            StoryPeers.WRAPPER_ARCHETYPE));

    NetworkCapture.actor(StoryIdentities.OPERATOR);
    JsonPath opened =
        StoryIdentities.person(given())
            // A body-less POST still has to declare a content type: the door is
            // @Consumes(APPLICATION_JSON), and RESTEasy answers 415 to a request that arrives with
            // none.
            .contentType(ContentType.JSON)
            .when()
            .post(StoryTarget.EDITOR_ENSURE_PATH + "?repositoryId=" + StoryTarget.WRAPPER_REPO_ID)
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
            "the browser posts the wrapper's id and gets 201 — the door resolved the wrapper's main"
                + " workspace and began starting its container, answering the workspace row id the"
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
        "the container was not launched as an editor — isWrapperMain was not recognised");
    assertTrue(
        spec.contains("QITS_WORKSPACE_DAEMON_URL")
            && spec.contains("/workspaces/daemon/" + rowId),
        "the editor container was not told where to dial home");
    story
        .note(
            "the container asked for is the wrapper-main one, and its spec carries the editor"
                + " environment the in-container daemon reads to supervise openvscode-server — which"
                + " is how the same createMainWorkspace path launches an editor rather than a plain"
                + " workspace, decided by what the workspace IS and not by a flag on the call")
        .as("editor-container-ensured");

    // From here the story plays the container, so the provision it began completes cleanly rather
    // than failing on a daemon that never arrives — which drains its far-side traffic before return.
    NetworkCapture.actor(StoryIdentities.DAEMON);
    String daemonBearer =
        StoryIdentities.machineToken("workspace-" + rowId, StoryIdentities.SYSTEM_ROLE);
    MINTED.add(daemonBearer);
    try (StoryDaemon daemon = StoryDaemon.dial(baseUrl, rowId, daemonBearer)) {
      daemon.hello(StoryTarget.MAIN_WORKSPACE_LABEL, StoryTarget.WRAPPER_REPO_ID, StoryTarget.MAIN);
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

    // The registry. ONE arrow for the handful of reads the door and the provision make — the door's
    // require, createMainWorkspace's, and the posture lookup that decides the editor image — all the
    // same (kind, from, to, label), and the id authored so it survives verbatim.
    to(StoryPeers.PROJECTS, StoryPeers.repositoryRead(StoryTarget.WRAPPER_REPO_ID));
    // The agent configuration this container is born with — one read per provision, the same one
    // WorkspaceProvisionIT records. The editor's workspace is an ordinary workspace in every way
    // that matters here, so it is born with a document exactly as any other container is.
    to(StoryPeers.PROJECTS, StoryPeers.read(StoryPeers.AGENT_CONFIGURATION_PATH));

    // The git host, and that is the whole point. The only git operation is the `ls-remote` that asks
    // whether the main branch is still there — the wire read `ensureContainer` guards on. It renders
    // as TWO arrows: the ref advertisement, and the `git-upload-pack` the `ls-refs` command rides
    // under protocol v2. There is NO git-receive-pack — no push, because a wrapper-main ensure
    // creates no ref — and only one advertisement, so no mirror clone of objects either.
    to(
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.advertisement(StoryTarget.PROJECT, StoryTarget.WRAPPER_REPO));
    to(StoryGitHost.SERVICE_NAME, StoryGitHost.read(StoryTarget.PROJECT, StoryTarget.WRAPPER_REPO));

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

    // THIRTEEN across four planes: two browser doors, one registry read, one agent-configuration
    // read, the ls-remote's two git arrows, one commission, two container calls, one dial and three
    // frames. The count is what would notice a PUSH creeping in — a git-receive-pack the
    // wrapper-main path must not make — or a second advertisement (a mirror clone of objects), or a
    // status poll the design deliberately does not make because the wait is on the socket instead.
    // It moved from twelve when every container started being born with its agent configuration.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, OPENED_SLUG, 13);
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
      daemon.provisioned(StoryTarget.MAIN_WORKSPACE_LABEL, PROVISIONED_HEAD);
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
