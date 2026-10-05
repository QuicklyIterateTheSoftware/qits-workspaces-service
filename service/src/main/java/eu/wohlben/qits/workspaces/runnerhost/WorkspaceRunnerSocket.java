package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.runner.protocol.Heartbeat;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspacesrunner.protocol.Deleted;
import eu.wohlben.qits.workspacesrunner.protocol.Exited;
import eu.wohlben.qits.workspacesrunner.protocol.HealthChecked;
import eu.wohlben.qits.workspacesrunner.protocol.Inventory;
import eu.wohlben.qits.workspacesrunner.protocol.LaunchFailed;
import eu.wohlben.qits.workspacesrunner.protocol.Launched;
import eu.wohlben.qits.workspacesrunner.protocol.LoginState;
import eu.wohlben.qits.workspacesrunner.protocol.Stopped;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The endpoint a registered workspace runner holds open for as long as its host is up (epic
 * qits-624, qits-850). It owns the WebSocket lifecycle and the framing; {@link
 * WorkspaceRunnerRegistry} owns the sessions — qits-ci's {@code CiRunnerSocket}, kept in shape.
 *
 * <p><b>Identity is the bearer, and only the bearer.</b> {@code @RolesAllowed("qits:workspaces-runner")}
 * is enforced at the HTTP upgrade, so a dial without a runner's role never reaches {@link #onOpen}.
 * There the token's {@code sub} — which qits-idp sets to the client id of a {@code
 * client_credentials} token — is looked up against {@code workspace_runner.client_id}. No row is a
 * 1008 {@link RunnerWire.CloseReason#RUNNER_DELETED}, which a runner reads as its deletion and
 * decommissions itself on. Nothing on the wire names a runner.
 *
 * <p><b>The subject is read off the validated token and nowhere else</b>, so the machine gate
 * ({@code qits.auth.machine.required}, on in every deployed environment) must be on for a runner to
 * connect at all; with it off there is no token, and the forward-auth headers are not an identity a
 * runner may be named by. A dial with no subject is closed {@link #UNKNOWN_RUNNER}, which says
 * nothing about whether the runner exists and never makes one remove itself.
 *
 * <p><b>The path literal carries {@code /workspaces} itself</b>: a {@code @WebSocket} path does not
 * follow {@code quarkus.rest.path}. It is the protocol jar's {@link
 * WorkspacesRunnerProtocol#SOCKET_PATH}, the one spelling the register door hands out; {@code
 * quarkus.quinoa.ignored-path-prefixes=/workspaces} already keeps the SPA fallback off it. {@link
 * SocketBearerLifetime} keeps the connection past its bearer's {@code exp}.
 *
 * <p>Frames are handled on virtual threads, and an undecodable one is dropped and logged rather than
 * allowed to close the socket.
 */
@WebSocket(path = WorkspacesRunnerProtocol.SOCKET_PATH)
@RolesAllowed(WorkspaceRunnerSocket.RUNNER_ROLE)
public class WorkspaceRunnerSocket {

  private static final Logger LOG = Logger.getLogger(WorkspaceRunnerSocket.class);

  /** The role qits-idp grants a {@code workspaces-runner} client, and the only one admitted. */
  public static final String RUNNER_ROLE = "qits:workspaces-runner";

  /** A dial whose bearer carries no subject: a host that cannot read identity. */
  public static final String UNKNOWN_RUNNER = "UNKNOWN_RUNNER";

  /** A runner speaking another capability version at the pinned version. */
  public static final String CAPABILITY_MISMATCH = "CAPABILITY_MISMATCH";

  private static final UserData.TypedKey<WorkspaceRunnerRegistry.Session> SESSION =
      new UserData.TypedKey<>("workspacesRunnerSession");

  @Inject WorkspaceRunnerRegistry registry;

  @Inject WorkspaceRunnerMessageCodec codec;

  @Inject WorkspaceRunners runners;

  @Inject RunnerReservations reservations;

  @Inject SecurityIdentity identity;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(WebSocketConnection connection) {
    Optional<String> subject = MachineIdentity.claim(identity, "sub");
    Optional<WorkspaceRunner> runner = subject.flatMap(runners::findByClientId);
    if (runner.isEmpty()) {
      LOG.warnf(
          "Refused a workspace runner dial from %s: subject %s is no registered runner",
          connection.handshakeRequest().remoteAddress(), subject.orElse("(none)"));
      refuse(
          connection, subject.isPresent() ? RunnerWire.CloseReason.RUNNER_DELETED : UNKNOWN_RUNNER);
      return;
    }
    connection.userData().put(SESSION, registry.admit(runner.orElseThrow(), connection));
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(String message, WebSocketConnection connection) {
    WorkspaceRunnerRegistry.Session session = connection.userData().get(SESSION);
    if (session == null) {
      return;
    }
    RunnerMessage decoded;
    try {
      decoded = codec.decode(message);
    } catch (RuntimeException e) {
      LOG.debugf(
          "Dropped an undecodable frame from runner %s: %s", session.runnerName(), e.getMessage());
      return;
    }
    switch (decoded) {
      case Hello hello -> {
        switch (registry.onHello(session, hello)) {
          case GREETED -> {}
          case VERSION_MISMATCH -> refuse(connection, CAPABILITY_MISMATCH);
          case RUNNER_GONE -> refuse(connection, RunnerWire.CloseReason.RUNNER_DELETED);
        }
      }
      case Heartbeat ignored -> registry.onHeartbeat(session);
      case Reserve ignored -> reservations.onReserve(session);
      case Inventory inventory -> registry.onInventory(session, inventory);
      case Launched launched -> registry.onLaunched(session, launched);
      case LaunchFailed failed -> registry.onLaunchFailed(session, failed);
      case Exited exited -> registry.onExited(session, exited);
      case Stopped stopped -> registry.onStopped(session, stopped);
      case Deleted deleted -> registry.onDeleted(session, deleted);
      case LoginState login -> registry.onLoginState(session, login);
      case HealthChecked checked -> registry.onHealthChecked(session, checked);
      default ->
          // Host → runner frames are never received here; ignored rather than trusted.
          LOG.debugf(
              "Runner %s sent a host frame %s — ignored",
              session.runnerName(), decoded.getClass().getSimpleName());
    }
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    WorkspaceRunnerRegistry.Session session = connection.userData().get(SESSION);
    if (session != null) {
      registry.onClose(session);
    }
  }

  private void refuse(WebSocketConnection connection, String reason) {
    WorkspaceRunnerRegistry.closeBounded(
        connection,
        new CloseReason(WorkspaceRunnerRegistry.CLOSE_POLICY, reason),
        "a refused workspace runner dial");
  }
}
