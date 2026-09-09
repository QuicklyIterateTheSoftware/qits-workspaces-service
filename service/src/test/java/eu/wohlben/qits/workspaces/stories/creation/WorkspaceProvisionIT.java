package eu.wohlben.qits.workspaces.stories.creation;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import eu.wohlben.qits.workspaces.api.TokenValidationBootstrapIT;
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
 * <b>A workspace, provisioned end to end</b> — a branch created on the git host, a credential
 * commissioned for the container, the container asked for, and the daemon inside it dialling home
 * to say the checkout is populated.
 *
 * <p>It is the widest diagram in the catalogue and the only one with four planes on it at once:
 * HTTP in from an operator, HTTP out to three peers, git out to the host, and a <b>socket</b> the
 * far side dialled — because the whole design of the control plane is that a container dials
 * <b>out</b>. qits-workspaces never dials in, which is why a workspace container needs no inbound
 * route, no address and no port of its own.
 *
 * <h2>The story plays the container, and it has to</h2>
 *
 * <p>{@code WorkspaceDaemonRegistry.awaitProvision} waits for a live connection and then for a
 * terminal {@code Provisioned} frame; with neither, the launch fails after the connect window with
 * "no workspace-daemon dialed home — is the container running an image with the daemon?". So a
 * story that stopped at the container request would be a story about a provision that <b>failed</b>.
 * {@link StoryDaemon} is a real WebSocket client speaking the real vendored protocol, and the story
 * learns that the container was asked for by watching qits-containers' own recording rather than by
 * asking the launched process anything.
 *
 * <p>The <b>credential</b> is the one place the two halves are proved separately. A real container
 * exchanges the pair the host commissioned for it — {@code QITS_COMMISSIONED_CLIENT_ID}/{@code
 * …_SECRET}, injected into its environment — for a bearer at qits-platform-idp, which is a stub
 * here and answers an opaque string no gate could validate. So the story reads the pair out of the
 * workload spec (the only place it exists, and exactly where a container finds it) and mints its
 * dial bearer directly. What is proved is that the credential travelled; what is not is the exchange
 * itself, which is qits-platform-idp's own claim to make.
 *
 * <h2>The repository id here is AUTHORED, unlike every other fixture in this catalogue</h2>
 *
 * <p>A container's name is {@code qits-ws-<label>-<repoId[0:8]>} and that name is a path segment of
 * every qits-containers call. Eight characters of a uuid <i>inside</i> a longer segment is something
 * {@code Labels} correctly refuses to rewrite — it scrubs whole segments — so a generated id there
 * would put a run-local value in a hashed label and the only symptom would be a {@code networkHash}
 * that never settles. The rule: an id that reaches a label inside a segment has to be authored.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WorkspaceProvisionIT {

  static final String CATEGORY = "workspaces";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String PROVISIONED = "A workspace is provisioned for a branch";

  static final String PROVISIONED_SLUG = Slugs.slug(PROVISIONED);

  /** The head the story's daemon reports — authored, so it survives into a step and no label. */
  private static final String PROVISIONED_HEAD = "story-head";

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

  @UserStory(value = PROVISIONED, category = CATEGORY)
  @UserStoryDescription(
      """
      Somebody wants to work on something. They name a repository, a parent branch and a label, and
      what comes back is a place to work in: a branch of their own on the git host, a container with
      the toolchain in it, and a checkout of that branch inside the container.

      The branch comes first, and it is created by a PUSH like every other ref this service moves —
      `push refs/heads/<parent>:refs/heads/<new>`, quiet (`-o qits.no-ci`), because the new ref
      points at a commit the host already holds and building it again is measurable waste. It used
      to be a filesystem write into the served bare, which fired no post-receive and is why no
      workspace anybody ever created produced a CI run.

      Then the container. This process holds no docker socket: it asks qits-containers to put a
      container at a place, under a spec it composes — the pinned workspace image, the volumes, the
      network, and the environment the daemon inside will read. Two things in that environment are
      the point. `QITS_WORKSPACE_DAEMON_URL` is the address the daemon dials back on, which is why
      the container needs no inbound route. And `QITS_COMMISSIONED_CLIENT_ID`/`_SECRET` are an idp
      client of the container's OWN, commissioned for this workspace a moment earlier — the credential
      that later lets an agent inside it drive the release door. It lives as long as the container,
      not as long as a token, and it is handed back at teardown.

      Then the host WAITS. A container that started is not a workspace: the daemon inside has to
      clone the branch, and only it knows when that is done. So the provision is not complete until
      the daemon dials the control socket, says Hello, and reports Provisioned with the head it
      checked out. Until then the workspace is PROVISIONING, and a daemon that never arrives fails
      the launch rather than leaving a container nobody can use.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void aBranchAContainerAndADaemonThatDialsHome(Interactions story) throws Exception {
    StoryGitHost.createRepository(StoryTarget.PROJECT, StoryTarget.WORKSPACE_REPO, true);
    StoryPeers.register(
        new StoryPeers.Repository(
            StoryTarget.WORKSPACE_REPO_ID,
            StoryTarget.PROJECT,
            StoryTarget.WORKSPACE_REPO,
            StoryTarget.MAIN));
    String trunk =
        StoryGitHost.shaOf(StoryTarget.PROJECT, StoryTarget.WORKSPACE_REPO, StoryTarget.MAIN);

    NetworkCapture.actor(StoryIdentities.OPERATOR);
    JsonPath created =
        StoryIdentities.person(given())
            .contentType(ContentType.JSON)
            .body(createBody())
            .when()
            .post(StoryTarget.WORKSPACES_PATH)
            .then()
            .statusCode(200)
            .body("workspace.workspaceId", equalTo(StoryTarget.WORKSPACE_LABEL))
            .body("workspace.status", equalTo("ACTIVE"))
            // The create answers a THIN view: `branch`, the ahead/behind counts, the daemon fields
            // and the repository's default branch are all computed rather than stored, and none of
            // them is knowable at the instant a row is written. The full shape is the read below.
            .body("workspace.branch", org.hamcrest.Matchers.nullValue())
            .extract()
            .jsonPath();
    long rowId = created.getLong("workspace.id");
    story
        .note(
            "the operator names a repository, a parent and a label; the workspace comes back ACTIVE"
                + " with an id of its own — not the label, which is only unique per repository and"
                + " is reusable once a workspace resolves — and as a THIN view, because everything"
                + " else on a workspace row is computed and none of it is knowable yet")
        .as("workspace-created");

    assertEquals(
        trunk,
        StoryGitHost.shaOf(
            StoryTarget.PROJECT, StoryTarget.WORKSPACE_REPO, StoryTarget.WORKSPACE_LABEL),
        "the workspace's branch is not at the parent's tip on the git host");
    assertEquals(
        List.of("qits.no-ci"),
        StoryGitHost.pushOptionsFor(
            StoryTarget.PROJECT,
            StoryTarget.WORKSPACE_REPO,
            "refs/heads/" + StoryTarget.WORKSPACE_LABEL),
        "the branch create was not a quiet push");
    story
        .note(
            "the branch exists on the git host at the parent's tip, and it got there by a PUSH"
                + " carrying qits.no-ci — a create points at a commit the host already holds, so"
                + " there is nothing new to build")
        .as("branch-pushed");

    // The container. The verb answers at once with a technical process id — the work is a pull, a
    // start and a clone, which is minutes and is the wrong thing to hold an HTTP request open for.
    String process =
        StoryIdentities.person(given())
            // A body-less POST still has to declare one: the resource is @Consumes(APPLICATION_JSON)
            // and RESTEasy answers 415 to a request that arrives without a content type.
            .contentType(ContentType.JSON)
            .when()
            .post(StoryTarget.workspacePath(rowId) + "/ensure-container")
            .then()
            .statusCode(200)
            .body("technicalProcessId", notNullValue())
            .extract()
            .path("technicalProcessId");
    story
        .note(
            "the start answers 202-shaped — the workspace as it stands plus the id of the technical"
                + " process the work streams over — because a pull and a clone are minutes of"
                + " somebody else's work")
        .as("provision-started");

    // What qits-containers was asked for. The story learns this from the far side's own recording:
    // nothing in this JVM is on that path, and asking the launched process would only get its own
    // account of what it did.
    assertTrue(
        StoryPeers.awaitCall(
            "PUT " + StoryPeers.containerPath(StoryTarget.CONTAINER_NAME), PATIENCE),
        "qits-containers was never asked for the workspace's container");
    String spec = StoryPeers.lastEnsureRequest();
    assertNotNull(spec, "no workload spec reached qits-containers");
    assertTrue(
        spec.contains("QITS_COMMISSIONED_CLIENT_ID")
            && spec.contains(StoryPeers.COMMISSIONED_CLIENT_ID),
        "the container was launched without the credential commissioned for it");
    assertTrue(
        spec.contains("QITS_WORKSPACE_DAEMON_URL")
            && spec.contains("/workspaces/daemon/" + rowId),
        "the container was not told where to dial home");
    assertTrue(
        spec.contains("QITS_WORKSPACE_DAEMON_PROJECT_ID")
            && spec.contains(StoryTarget.WORKSPACE_REPO),
        "the container was not told its repository's public identity");
    // The agent configuration the container was BORN with: the document qits-projects answered a
    // moment earlier, in the container's own environment, plus the path the daemon is to
    // materialize it at. This is the whole of the injection — no volume, no second call, and
    // nothing the container has to ask anyone for.
    assertTrue(
        spec.contains("QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION_PATH")
            && spec.contains("QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION")
            && spec.contains("epic.chat"),
        "the container was not born with the agent configuration qits-projects resolved for it");
    story
        .note(
            "the spec carries the idp client commissioned for THIS workspace a moment earlier, the"
                + " control-socket address keyed on the workspace's row id, and the repository's"
                + " public (project, name) pair so the daemon can self-clone name-addressed")
        .as("container-requested");

    // The daemon dials home. From here the story is the container.
    NetworkCapture.actor(StoryIdentities.DAEMON);
    String daemonBearer =
        StoryIdentities.machineToken("workspace-" + rowId, StoryIdentities.SYSTEM_ROLE);
    MINTED.add(daemonBearer);
    try (StoryDaemon daemon = StoryDaemon.dial(baseUrl, rowId, daemonBearer)) {
      daemon.hello(StoryTarget.WORKSPACE_LABEL, StoryTarget.WORKSPACE_REPO_ID,
          StoryTarget.WORKSPACE_LABEL);
      assertNotNull(daemon.awaitAck(), "the host did not acknowledge the daemon's Hello");
      story
          .note(
              "the daemon dials the control socket with a qits:system bearer of its own, names"
                  + " itself and its branch, and the host acknowledges — the socket is the only"
                  + " thing that makes a container reachable, and the container opened it")
          .as("daemon-dialled-home");

      daemon.provisionOutput("Cloning " + StoryTarget.WORKSPACE_REPO + "…\n");
      // Back to the operator BEFORE the wait: the browser is what polls the technical process, and
      // the shipped RestAssured tap stamps whichever actor is current on every request it sees.
      // The socket frames below are unaffected — a frame's initiator is which end pushed it, which
      // is knowable without an actor, so StoryDaemon names both ends itself.
      NetworkCapture.actor(StoryIdentities.OPERATOR);
      awaitProvisionAccepted(daemon, rowId);
      story
          .note(
              "the daemon streams the clone's output onto the process's own segment and then"
                  + " reports Provisioned with the head it checked out — which is what ends the"
                  + " host's wait")
          .as("provisioned");

      StoryIdentities.person(given())
          .when()
          .get(StoryTarget.workspacePath(rowId))
          .then()
          .statusCode(200)
          .body("workspace.runtimeStatus", equalTo("RUNNING"))
          .body("workspace.runtimeError", org.hamcrest.Matchers.nullValue())
          .body("workspace.daemonConnectedAt", notNullValue())
          .body("workspace.daemonVersion", equalTo("story-daemon"));
      story
          .note(
              "the workspace reads RUNNING with no runtime error, and the row carries what the"
                  + " daemon announced about itself — which is how the surface can say a workspace"
                  + " is on an outdated daemon build without asking the container")
          .as("workspace-running");
    }
  }

  @AfterAll
  static void theProvisionStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, PROVISIONED_SLUG, UserflowReport.PASSED);
    for (String step :
        List.of(
            "workspace-created",
            "branch-pushed",
            "provision-started",
            "container-requested",
            "daemon-dialled-home",
            "provisioned",
            "workspace-running")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, PROVISIONED_SLUG, step);
    }

    // What the operator sent. The polling loop is many requests and ONE arrow: the row id is a bare
    // number and the label is templated, so what the diagram says is that a provision is watched
    // over this route rather than how impatient the watching was.
    from(StoryIdentities.OPERATOR, NetworkEdge.HTTP, "POST " + StoryTarget.WORKSPACES_PATH + " -> 200");
    from(
        StoryIdentities.OPERATOR,
        NetworkEdge.HTTP,
        "POST " + StoryTarget.ENSURE_CONTAINER_LABEL_PATH + " -> 200");
    from(
        StoryIdentities.OPERATOR,
        NetworkEdge.HTTP,
        "GET " + StoryTarget.WORKSPACE_LABEL_PATH + "/active-process -> 200");
    from(StoryIdentities.OPERATOR, NetworkEdge.HTTP, "GET " + StoryTarget.WORKSPACE_LABEL_PATH + " -> 200");

    // The registry. ONE arrow for what is really a handful of reads: the lookup is deliberately not
    // cached (a repository's main branch can change), and every git address this flow builds resolves
    // the row id again — but they are one (kind, from, to, label) and draw once. The id is AUTHORED
    // here, so unlike the release stories' uuid it survives into the label verbatim.
    to(StoryPeers.PROJECTS, StoryPeers.repositoryRead(StoryTarget.WORKSPACE_REPO_ID));

    // The agent configuration, read ONCE at provision and never again for this container's life —
    // a snapshot, not a subscription. What comes back is written into the container's environment
    // (asserted off the workload spec above), so a session launched inside it renders locally with
    // no call back to qits-projects, and an edit there reaches the NEXT container.
    to(StoryPeers.PROJECTS, StoryPeers.read(StoryPeers.AGENT_CONFIGURATION_PATH));

    // The git host: the mirror's advertisement and pack read, and the branch create as a push.
    to(
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.advertisement(StoryTarget.PROJECT, StoryTarget.WORKSPACE_REPO));
    to(StoryGitHost.SERVICE_NAME, StoryGitHost.read(StoryTarget.PROJECT, StoryTarget.WORKSPACE_REPO));
    to(
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.written(StoryTarget.PROJECT, StoryTarget.WORKSPACE_REPO));

    // The credential this workspace's container was given, minted at qits-platform-idp for it alone.
    to(StoryPeers.IDP, StoryPeers.posted(StoryPeers.CLIENTS_PATH, 201));
    // …and this service's OWN credential for qits-containers, which is a different token for a
    // different reason and draws as the same arrow the release stories' two clients drew. It is
    // here rather than there because the containers client is the one of the three that no release
    // touches, so its first call is this story's — and it is cached for an hour afterwards, which
    // is why the operator's listing in the next class does not carry it.
    to(StoryPeers.IDP, StoryPeers.posted(StoryPeers.TOKEN_PATH, 200));

    // The container orchestrator: the listing a workspace read costs, the "is it there" that says
    // it is not, and the put that asks for it.
    to(StoryPeers.CONTAINERS, StoryPeers.read(StoryPeers.listingPath()));
    to(
        StoryPeers.CONTAINERS,
        StoryPeers.label("GET", StoryPeers.containerPath(StoryTarget.CONTAINER_NAME), 404));
    to(
        StoryPeers.CONTAINERS,
        StoryPeers.label("PUT", StoryPeers.containerPath(StoryTarget.CONTAINER_NAME), 200));

    // The plane the framework ships no tap for. The dial is a `socket`; every frame over it is an
    // `event`, in whichever direction it was pushed — and `ack` is the host's, which is the only
    // arrow in this whole diagram that leaves this service for the container.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        PROVISIONED_SLUG,
        NetworkEdge.SOCKET,
        StoryIdentities.DAEMON,
        StoryTarget.SERVICE,
        "CONNECT " + StoryTarget.DAEMON_LABEL_PATH);
    for (String frame : List.of("hello", "stepChunk provision", "provisioned")) {
      ReportAssertions.assertEdge(
          CATEGORY_SLUG,
          PROVISIONED_SLUG,
          NetworkEdge.EVENT,
          StoryIdentities.DAEMON,
          StoryTarget.SERVICE,
          frame);
    }
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        PROVISIONED_SLUG,
        NetworkEdge.EVENT,
        StoryTarget.SERVICE,
        StoryIdentities.DAEMON,
        "ack");

    // NINETEEN across four planes: four doors, one registry read, one agent-configuration read,
    // three git calls, one commission, one token, three container calls, one dial and four frames.
    // The count is what would notice a peer call creeping into a path that is supposed to be
    // finished — a status poll after the ensure, say, which the design deliberately does not make
    // because the wait is on the socket instead. It moved from eighteen when the container started
    // being born with its agent configuration, and that ONE arrow is the whole runtime cost of the
    // feature: one read per provision, none per launch.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, PROVISIONED_SLUG, 19);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        PROVISIONED_SLUG,
        List.of(StoryIdentities.OPERATOR, StoryIdentities.DAEMON, StoryTarget.SERVICE));

    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, PROVISIONED_SLUG, StoryProfile.CLIENT_SECRET);
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, PROVISIONED_SLUG, StoryPeers.MACHINE_TOKEN);
    // The one that matters most here: the commissioned secret really was on this JVM's disk, in the
    // workload spec the story read. A report that carried it would be a credential published as
    // documentation.
    ReportAssertions.assertNotLeaked(
        CATEGORY_SLUG, PROVISIONED_SLUG, StoryPeers.COMMISSIONED_SECRET);
    for (String bearer : MINTED) {
      ReportAssertions.assertNotLeaked(CATEGORY_SLUG, PROVISIONED_SLUG, bearer);
    }
  }

  // --- driving the container half -----------------------------------------------------------------

  /**
   * Report {@code Provisioned} until the host's provision is observably over.
   *
   * <p>The repeat is insurance rather than narrative: {@code completeProvision} drops a terminal
   * frame that arrives before {@code awaitProvision} has registered its slot, and the only way to
   * observe that registration from outside the process is that the provision finishes. In the
   * ordinary case the first frame lands — {@link StoryPeers} answers the container request half a
   * second slow precisely so the host is waiting by the time the daemon has shaken hands. A repeat
   * is the same {@code (kind, from, to, label)} either way, so it is one arrow.
   */
  private void awaitProvisionAccepted(StoryDaemon daemon, long rowId) throws Exception {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (true) {
      daemon.provisioned(StoryTarget.WORKSPACE_LABEL, PROVISIONED_HEAD);
      if (awaitProcessOver(rowId, Duration.ofSeconds(5))) {
        return;
      }
      if (System.nanoTime() >= deadline) {
        fail("the provision never completed; the workspace is still running a technical process");
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

  /** The create request, as the workspaces client sends it. */
  private static String createBody() {
    return "{\"repositoryId\":\""
        + StoryTarget.WORKSPACE_REPO_ID
        + "\",\"id\":\""
        + StoryTarget.WORKSPACE_LABEL
        + "\",\"parent\":\""
        + StoryTarget.MAIN
        + "\",\"branch\":\""
        + StoryTarget.WORKSPACE_LABEL
        + "\",\"preamble\":null,\"adoptExisting\":false,\"branchTree\":false,\"admin\":false}";
  }

  private static void from(String actor, String kind, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, PROVISIONED_SLUG, kind, actor, StoryTarget.SERVICE, label);
  }

  private static void to(String peer, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, PROVISIONED_SLUG, NetworkEdge.HTTP, StoryTarget.SERVICE, peer, label);
  }
}
