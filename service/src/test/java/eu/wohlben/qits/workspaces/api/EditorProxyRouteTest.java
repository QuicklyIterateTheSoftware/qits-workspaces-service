package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import eu.wohlben.qits.workspaces.control.EditorHost;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.daemonhost.WorkspaceDaemonRegistry;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspacedaemon.protocol.EditorState;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * <b>How the editor route decides what to answer</b> — the five answers and nothing about the
 * forwarding.
 *
 * <p>No daemon connects in this class, so no tunnel is ever opened, and that is deliberate rather
 * than a gap: the reverse tunnel is the ONLY way into an editor (the daemon binds
 * openvscode-server to the container's loopback, so no address on {@code qits-net} reaches it), so
 * everything about the data path — the verbatim path, the header strip on both transports, the
 * bounded pipe, the upgrade — is {@link eu.wohlben.qits.workspaces.daemonhost.EditorTunnelRouteTest}'s
 * subject, over the transport production traffic actually takes. What is left here is the decision
 * in front of it: who is refused, what is a 404, what is a splash, and the two distinct 502s.
 *
 * <p>This class used to prove the forwarding against a loopback stand-in reached through the
 * route's direct-origin arm. That arm was unreachable in the shipped topology — the port it dialled
 * is inside the container's network namespace — so the hardening was proved on the one path
 * production never takes. The arm is gone and so is the fake editor.
 */
@QuarkusTest
// NO @TestProfile: it overrode qits.test.origins-dir with a fresh temp directory and nothing else,
// so it was a whole second Quarkus application bought for an isolation TestOrigin already gives —
// every origin goes under a UUID of its own, which is why the default profile's thirty-odd classes
// share the shipped target/workspaces-test-data without colliding. See
// daemonhost/TunnelNonceProfile for what a restart costs in metaspace; bug e6f0bdfa.
public class EditorProxyRouteTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceDaemonRegistry registry;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  // --- fixtures -----------------------------------------------------------------------------------

  /** A project whose wrapper has a main workspace with a (fake) container running. */
  private Workspace editorWorkspace(String slug) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.registerWrapper(repoId, "master", EditorHost.wrapperRepositoryName(slug));
    Workspace main = workspaceService.createMainWorkspace(repoId, "master");
    workspaceService.ensureContainer(main.id);
    return main;
  }

  private static String host(String slug) {
    return "editor." + slug + ".dev.example.eu";
  }

  /** What the daemon would have said, without a daemon: the registry caches the frame either way. */
  private void reportEditor(Long rowId, String state) {
    registry.onMessage(rowId, null, new EditorState(state));
  }

  // --- cases --------------------------------------------------------------------------------------

  @Test
  public void aRequestWithoutThePlatformsIdentityIsRefusedBeforeAnythingIsDialled()
      throws Exception {
    Workspace main = editorWorkspace("refusal");
    reportEditor(main.id, EditorState.State.RUNNING);

    // The edge strips the X-Qits-* namespace from every inbound request unconditionally, so the
    // header cannot be forged and its ABSENCE is evidence too: this request did not come through the
    // session gate that is this platform's auth boundary. 403 rather than 401 because this hop has
    // no challenge to issue — the login is at the edge.
    given()
        .header("X-Forwarded-Host", host("refusal"))
        .get("/")
        .then()
        .statusCode(403)
        .body(containsString("did not come that way"));
  }

  @Test
  public void aLabelNobodyRegisteredIs404WithNothingDialled() throws Exception {
    editorWorkspace("known");

    // Not a redirect, not a default project, not the only project this platform happens to have.
    given()
        .header("X-Forwarded-Host", host("nosuchproject"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(404)
        .body(containsString("no editor for this address"));
  }

  @Test
  public void aStoppedContainerIsASplashAndNotAnError() throws Exception {
    Workspace main = editorWorkspace("stopped");
    reportEditor(main.id, EditorState.State.RUNNING);
    workspaceService.stopContainer(main.id);

    // A container that is not up is not a broken editor. The page says so and refreshes itself, so
    // opening the editor while it starts is the same act as opening it once it has.
    given()
        .header("X-Forwarded-Host", host("stopped"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("not running"))
        .body(containsString("http-equiv=\"refresh\""));
  }

  @Test
  public void aStartingEditorAndAnUnreportedOneAreTheSameSplash() throws Exception {
    Workspace main = editorWorkspace("starting");

    // Nothing reported: the container is up, and no frame has arrived. A reader cannot act on the
    // difference between that and STARTING, so they are one answer.
    given()
        .header("X-Forwarded-Host", host("starting"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("starting"))
        .body(containsString("http-equiv=\"refresh\""));

    reportEditor(main.id, EditorState.State.STARTING);
    given()
        .header("X-Forwarded-Host", host("starting"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("starting"));
  }

  @Test
  public void anEndedEditorStopsTheWaitingWithAStatusOfItsOwn() throws Exception {
    Workspace main = editorWorkspace("ended");
    reportEditor(main.id, EditorState.State.ENDED);

    // Terminal, so it must NOT be the refreshing splash: the editor is not coming back in this
    // container, and a page that kept waiting would spin for the container's lifetime.
    given()
        .header("X-Forwarded-Host", host("ended"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(502)
        .body(containsString("recreate the container"));
  }

  /**
   * The answer that replaced the direct-origin arm, and the reason it is an answer rather than a
   * dial.
   *
   * <p>Everything says serve: the container is up and the daemon's last frame says the editor is
   * RUNNING. There is still no tunnel, because no daemon holds a control socket — which is exactly
   * what the shipped topology looks like when the daemon has died, when it is an image that predates
   * the editor stream, or when {@code qits.workspace.daemon-tunnel.enabled} is off. The old arm
   * answered that by dialling the container's editor port, which is bound on the container's
   * LOOPBACK: connection refused, every time, and then a 502 that named the container rather than
   * the thing that is actually missing. Naming it is the whole change.
   */
  @Test
  public void aRunningEditorWithNoTunnelSaysSoRatherThanDiallingSomethingUnreachable()
      throws Exception {
    Workspace main = editorWorkspace("notunnel");
    reportEditor(main.id, EditorState.State.RUNNING);

    given()
        .header("X-Forwarded-Host", host("notunnel"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(502)
        .body(containsString("daemon tunnel"))
        // Not the refreshing splash: nothing this side does will make a tunnel appear, so a page
        // that kept reloading would be waiting on the wrong thing.
        .body(not(containsString("http-equiv=\"refresh\"")));
  }

  @Test
  public void theMachineSurfaceIsUntouchedByThisRoute() throws Exception {
    Workspace main = editorWorkspace("ordering");
    reportEditor(main.id, EditorState.State.RUNNING);

    // A request that names no editor origin falls straight through — this route claims nothing it
    // was not addressed by name. ContainerProxyRoute's own JSON 404 is the proof that it, and not
    // this catch-all, answered.
    given()
        .get("/workspaces/container/999999/files")
        .then()
        .statusCode(404)
        .body(containsString("No workspace here."));

    // And the machine surface keeps its paths even UNDER an editor host, where a running editor
    // would otherwise have served them: those routes take Vert.x's auto-sequence from 0 and this one
    // is ordered at 1000, deliberately behind them. Nothing on an editor origin ever asks for
    // /workspaces/*, so the ordering costs nothing and keeps this route out of the way of a surface
    // it has nothing to do with.
    given()
        .header("X-Forwarded-Host", host("ordering"))
        .header("X-Qits-User", "alice")
        .get("/workspaces/container/999999/files")
        .then()
        .statusCode(404)
        .body(containsString("No workspace here."));
  }
}
