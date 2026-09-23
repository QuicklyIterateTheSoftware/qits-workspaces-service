package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.daemonhost.WorkspaceDaemonRegistry;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspacedaemon.protocol.EditorState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
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
// control/SharedTuningProfile for what a restart costs in metaspace; bug e6f0bdfa.
public class EditorProxyRouteTest {

  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaces;
  @Inject WorkspaceDaemonRegistry registry;

  // --- fixtures -----------------------------------------------------------------------------------

  /**
   * THE editor, with a (fake) container running — and nothing else, which is the fixture the change
   * collapsed. Every case used to mint a repository, register it as a project's wrapper under the
   * name that project's slug derives, and open its main workspace, because the origin named a
   * project and the lookup had to find it. There is one editor row now and no project anywhere in
   * the path.
   *
   * <p>It is one row for the whole database, so the state each case needs is arranged per case
   * rather than assumed: the container is ensured here, and the daemon's last word about the editor
   * is dropped, because "nothing reported" is one of the five answers and a previous case's frame
   * would otherwise still be standing.
   */
  private Workspace editorWorkspace() {
    Workspace editor = workspaceService.createEditorWorkspace();
    workspaceService.ensureContainer(editor.id);
    // A state this host cannot name drops the entry — the registry's own rule, and the only way to
    // say "the daemon has said nothing" from outside a disconnect.
    registry.onMessage(editor.id, null, new EditorState("NOTHING_THIS_HOST_CAN_NAME"));
    return editor;
  }

  /**
   * The editor's origin. The old per-project grammar, deliberately: it is what is deployed today,
   * and the route is grammar-agnostic behind the first label precisely so the origin can move on its
   * own.
   */
  private static String host() {
    return "editor.qits.dev.example.eu";
  }

  /** What the daemon would have said, without a daemon: the registry caches the frame either way. */
  private void reportEditor(Long rowId, String state) {
    registry.onMessage(rowId, null, new EditorState(state));
  }

  // --- cases --------------------------------------------------------------------------------------

  @Test
  public void aRequestWithoutThePlatformsIdentityIsRefusedBeforeAnythingIsDialled()
      throws Exception {
    Workspace main = editorWorkspace();
    reportEditor(main.id, EditorState.State.RUNNING);

    // The edge strips the X-Qits-* namespace from every inbound request unconditionally, so the
    // header cannot be forged and its ABSENCE is evidence too: this request did not come through the
    // session gate that is this platform's auth boundary. 403 rather than 401 because this hop has
    // no challenge to issue — the login is at the edge.
    given()
        .header("X-Forwarded-Host", host())
        .get("/")
        .then()
        .statusCode(403)
        .body(containsString("did not come that way"));
  }

  @Test
  public void anEditorNobodyHasOpenedYetIs404WithNothingDialled() throws Exception {
    // The fresh-platform answer, and the case that replaced "a project label nobody registered".
    // The row is written by the DOOR, so a browser that navigates straight to the origin finds
    // nothing — and a GET at an origin deliberately does not start a container for it.
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaces
                    .findActiveEditor()
                    .ifPresent(
                        editor -> {
                          editor.status = WorkspaceStatus.ABANDONED;
                          editor.resolvedAt = Instant.now();
                        }));

    given()
        .header("X-Forwarded-Host", host())
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(404)
        .body(containsString("no editor for this address"));
  }

  @Test
  public void aStoppedContainerIsASplashAndNotAnError() throws Exception {
    Workspace main = editorWorkspace();
    reportEditor(main.id, EditorState.State.RUNNING);
    workspaceService.stopContainer(main.id);

    // A container that is not up is not a broken editor. The page says so and refreshes itself, so
    // opening the editor while it starts is the same act as opening it once it has.
    given()
        .header("X-Forwarded-Host", host())
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("not running"))
        .body(containsString("http-equiv=\"refresh\""));
  }

  @Test
  public void aStartingEditorAndAnUnreportedOneAreTheSameSplash() throws Exception {
    Workspace main = editorWorkspace();

    // Nothing reported: the container is up, and no frame has arrived. A reader cannot act on the
    // difference between that and STARTING, so they are one answer.
    given()
        .header("X-Forwarded-Host", host())
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("starting"))
        .body(containsString("http-equiv=\"refresh\""));

    reportEditor(main.id, EditorState.State.STARTING);
    given()
        .header("X-Forwarded-Host", host())
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("starting"));
  }

  @Test
  public void anEndedEditorStopsTheWaitingWithAStatusOfItsOwn() throws Exception {
    Workspace main = editorWorkspace();
    reportEditor(main.id, EditorState.State.ENDED);

    // Terminal, so it must NOT be the refreshing splash: the editor is not coming back in this
    // container, and a page that kept waiting would spin for the container's lifetime.
    given()
        .header("X-Forwarded-Host", host())
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
    Workspace main = editorWorkspace();
    reportEditor(main.id, EditorState.State.RUNNING);

    given()
        .header("X-Forwarded-Host", host())
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
    Workspace main = editorWorkspace();
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
        .header("X-Forwarded-Host", host())
        .header("X-Qits-User", "alice")
        .get("/workspaces/container/999999/files")
        .then()
        .statusCode(404)
        .body(containsString("No workspace here."));
  }
}
