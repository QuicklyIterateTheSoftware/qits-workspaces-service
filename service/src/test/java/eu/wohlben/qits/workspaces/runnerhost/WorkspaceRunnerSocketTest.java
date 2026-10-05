package eu.wohlben.qits.workspaces.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Heartbeat;
import eu.wohlben.qits.runner.protocol.Nothing;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspacesrunner.protocol.Estate;
import eu.wohlben.qits.workspacesrunner.protocol.HealthCheck;
import eu.wohlben.qits.workspacesrunner.protocol.HealthChecked;
import eu.wohlben.qits.workspacesrunner.protocol.HeldContainer;
import eu.wohlben.qits.workspacesrunner.protocol.Inventory;
import eu.wohlben.qits.workspacesrunner.protocol.LoginPresence;
import eu.wohlben.qits.workspacesrunner.protocol.LoginState;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The runner socket and the registry behind it (qits-850), driven by a real WebSocket from a
 * scripted {@link FakeWorkspacesRunner} — qits-ci's {@code CiRunnerSocketTest} arrangement.
 *
 * <p><b>Under the machine gate</b> ({@link DaemonControlSocketMachineAuthTest.GateOn}, reused rather
 * than copied: one profile is one Quarkus start), because the runner is named by its bearer's
 * {@code sub} and with the gate off there is no token to read one from. The tokens are real RS256
 * JWTs the profile's key verifies, so the upgrade's role check is quarkus-oidc's real one.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class WorkspaceRunnerSocketTest {

  private static final String PIN = WorkspacesRunnerBinary.VERSION;

  private static final String OLD = "2000.101.1";

  @TestHTTPResource(WorkspacesRunnerProtocol.SOCKET_PATH)
  URI endpoint;

  @Inject Vertx vertx;

  @Inject WorkspaceRunnerRegistry registry;

  @Inject WorkspaceRunnerViews views;

  @Inject WorkspaceRunners runners;

  private RunnerRows rows;

  private final List<FakeWorkspacesRunner> dialled = new java.util.ArrayList<>();

  @BeforeEach
  void addressTheRunners() {
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.withDomain(WorkspaceRunnerAddressesFixture.DOMAIN),
        WorkspaceRunnerAddresses.class);
    rows = new RunnerRows();
  }

  @AfterEach
  void hangUp() {
    dialled.forEach(FakeWorkspacesRunner::close);
    dialled.clear();
    registry.reconnectGrace(WorkspaceRunnerRegistry.RECONNECT_GRACE);
    rows.clear();
  }

  private FakeWorkspacesRunner dial(String clientId) throws Exception {
    return dialWith(
        DaemonMachineTokens.tokenWithRoles(
            clientId, Set.of(WorkspaceRunnerSocket.RUNNER_ROLE), "qits-platform"));
  }

  private FakeWorkspacesRunner dialWith(String bearer) throws Exception {
    FakeWorkspacesRunner runner = FakeWorkspacesRunner.connect(vertx, endpoint, bearer);
    dialled.add(runner);
    return runner;
  }

  /** Dial, say hello at the pin, and read the greeting of a runner in service up to its backlog. */
  private FakeWorkspacesRunner greeted(String clientId) throws Exception {
    FakeWorkspacesRunner runner = dial(clientId);
    runner.send(FakeWorkspacesRunner.hello(PIN, List.of()));
    runner.expect(Ack.class);
    runner.expect(Estate.class);
    runner.expect(Backlog.class);
    return runner;
  }

  // --- who may dial -------------------------------------------------------------------------------

  /** A valid runner bearer naming no registered runner: the runner was deleted, and is told so. */
  @Test
  void anUnknownSubjectIsClosedRunnerDeleted() throws Exception {
    FakeWorkspacesRunner stranger = dial("a-client-no-runner-owns");

    assertEquals("1008 RUNNER_DELETED", stranger.awaitClose());
  }

  /** A bearer without the runner role never reaches the socket at all. */
  @Test
  void aBearerWithoutTheRunnerRoleIsRefusedAtTheUpgrade() {
    rows.eligible("wr-not-a-runner-role", 1);
    String token =
        DaemonMachineTokens.tokenWithRoles(
            "wr-not-a-runner-role", Set.of("qits:workspaces-runner-registration"), "qits-platform");

    assertThrows(Exception.class, () -> dialWith(token));
  }

  // --- the greeting, quarantine and the health check ----------------------------------------------

  /**
   * A freshly registered runner is quarantined: {@code ack{0}}, {@code quarantined}, its estate and
   * backlog, then {@code healthCheck}. A passing check lifts it — {@code reinstated} and {@code
   * ack} with the row's slots.
   */
  @Test
  void aNewRunnerIsQuarantinedUntilItsHealthCheckPasses() throws Exception {
    WorkspaceRunner row = rows.registered("wr-fresh", 2);
    Long owned = rows.placedOn(row.id, WorkspaceRuntimeStatus.STOPPED);
    FakeWorkspacesRunner runner = dial("wr-fresh");

    runner.send(FakeWorkspacesRunner.hello(PIN, List.of()));

    assertEquals(0, runner.expect(Ack.class).slots());
    assertEquals(
        WorkspaceRunners.AWAITING_FIRST_HEALTH_CHECK, runner.expect(Quarantined.class).reason());
    Estate estate = runner.expect(Estate.class);
    assertEquals(List.of(owned), estate.owned());
    assertEquals(
        "registry.qits."
            + WorkspaceRunnerAddressesFixture.DOMAIN
            + "/qits/workspace:"
            + rows.imageVersion(),
        estate.workspaceImage());
    runner.expect(Backlog.class);
    runner.expect(HealthCheck.class);
    // A quarantined runner asks and is answered nothing.
    runner.send(new Reserve());
    runner.expect(Nothing.class);

    runner.send(new HealthChecked(true, "pulled, ran, removed"));

    runner.expect(Reinstated.class);
    assertEquals(2, runner.expect(Ack.class).slots());
    runner.expect(Backlog.class);
    WorkspaceRunner now = rows.runner(row.id);
    assertFalse(now.quarantined());
    assertEquals(Boolean.TRUE, now.lastHealthCheckOk);
  }

  @Test
  void aFailedHealthCheckKeepsTheRunnerOutAndSaysWhy() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-sick", 1);
    FakeWorkspacesRunner runner = greeted("wr-sick");

    runner.send(new HealthChecked(false, "the image would not start"));

    Quarantined said = runner.expect(Quarantined.class);
    assertTrue(said.reason().contains("the image would not start"), said.reason());
    assertEquals(0, runner.expect(Ack.class).slots());
    assertTrue(rows.runner(row.id).quarantined());
    assertEquals(Boolean.FALSE, rows.runner(row.id).lastHealthCheckOk);
  }

  // --- the pin, upgrade and retire ----------------------------------------------------------------

  /**
   * A runner of another version is told to become the pin and holds no slot; its successor at the
   * pin is greeted, and the old connection is retired as superseded.
   */
  @Test
  void anOldVersionIsUpgradedAndRetiredByItsSuccessor() throws Exception {
    rows.eligible("wr-rollover", 1);
    FakeWorkspacesRunner old = dial("wr-rollover");
    old.send(FakeWorkspacesRunner.hello(OLD, List.of()));

    Upgrade upgrade = old.expect(Upgrade.class);
    assertEquals(PIN, upgrade.version());
    assertEquals(
        "registry.qits."
            + WorkspaceRunnerAddressesFixture.DOMAIN
            + "/qits/qits-workspaces-runner:"
            + PIN,
        upgrade.image());
    assertEquals(0, old.expect(Ack.class).slots());
    old.send(new Reserve());
    old.expect(Nothing.class);

    FakeWorkspacesRunner successor = greeted("wr-rollover");

    Retire retire = old.expect(Retire.class);
    assertEquals(Retire.Kind.SUPERSEDED, retire.kind());
    assertFalse(successor.isClosed());
  }

  /** The same runner at the same version dialling again replaces its first connection. */
  @Test
  void aSecondConnectionAtTheSameVersionReplacesTheFirst() throws Exception {
    rows.eligible("wr-twice", 1);
    FakeWorkspacesRunner first = greeted("wr-twice");

    greeted("wr-twice");

    assertEquals("1008 " + WorkspaceRunnerRegistry.ALREADY_CONNECTED, first.awaitClose());
  }

  // --- the bearer's lifetime and the grace --------------------------------------------------------

  /**
   * The socket outlives its bearer's {@code exp}: without {@link SocketBearerLifetime},
   * websockets-next closes it "Authentication expired" at the token's expiry (qits-545, in CI).
   */
  @Test
  void theSocketOutlivesItsBearer() throws Exception {
    rows.eligible("wr-long-lived", 1);
    String shortLived =
        DaemonMachineTokens.tokenWithRoles(
            "wr-long-lived",
            Set.of(WorkspaceRunnerSocket.RUNNER_ROLE),
            Duration.ofSeconds(3),
            "qits-platform");
    FakeWorkspacesRunner runner = dialWith(shortLived);
    runner.send(FakeWorkspacesRunner.hello(PIN, List.of()));
    runner.expect(Ack.class);

    Thread.sleep(5_000);

    assertFalse(runner.isClosed(), "closed at the bearer's exp");
    runner.drain();
    runner.send(new Reserve());
    runner.expect(Nothing.class);
  }

  /** A drop inside the grace keeps the runner present; once the grace is out, it is not. */
  @Test
  void aDroppedRunnerIsPresentForTheGraceAndThenNot() throws Exception {
    registry.reconnectGrace(Duration.ofSeconds(2));
    WorkspaceRunner row = rows.eligible("wr-blink", 1);
    FakeWorkspacesRunner runner = greeted("wr-blink");
    assertTrue(registry.connected(row.id));
    assertNotNull(registry.connectedSince(row.id));

    runner.close();
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (registry.connected(row.id) && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }

    assertFalse(registry.connected(row.id));
    assertNull(registry.connectedSince(row.id));
    assertTrue(registry.presence(row.id), "inside the grace");
    deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
    while (registry.presence(row.id) && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    assertFalse(registry.presence(row.id), "past the grace");
  }

  // --- what the runner reports --------------------------------------------------------------------

  /**
   * {@code loginState} and the inventory's agent home land on the row and stay there when the runner
   * goes away: the page shows the last known login, and the login command, while it is offline.
   */
  @Test
  void theLoginStateAndTheVolumeSurviveADisconnect() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-login-state", 1);
    FakeWorkspacesRunner runner = greeted("wr-login-state");

    runner.send(new Inventory(List.of(), List.of(), "qits-workspaces-runner-dot-claude-abcd1234"));
    // Checked comfortably after this connection started, so it proves the current image is there.
    runner.send(
        new LoginState(
            LoginPresence.PRESENT, LoginPresence.ABSENT, Instant.now().plusSeconds(60).toString()));
    runner.send(new Heartbeat());
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    WorkspaceRunnerDto seen = views.view(rows.runner(row.id));
    while ((seen.login() == null || seen.dotClaudeVolume() == null)
        && System.nanoTime() < deadline) {
      Thread.sleep(50);
      seen = views.view(rows.runner(row.id));
    }
    runner.close();
    deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (registry.connected(row.id) && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }

    WorkspaceRunnerDto offline = views.view(rows.runner(row.id));
    assertFalse(offline.connected());
    assertEquals("PRESENT", offline.login().claude());
    assertEquals("ABSENT", offline.login().kimi());
    assertEquals("qits-workspaces-runner-dot-claude-abcd1234", offline.dotClaudeVolume());
    assertTrue(
        offline.loginCommand().startsWith(
            "docker run --rm -it --user 1000 --entrypoint claude -v"
                + " qits-workspaces-runner-dot-claude-abcd1234:/claude-home"),
        offline.loginCommand());
  }

  /** The inventory reconciles the rows the runner owns: held running is RUNNING, not held STOPPED. */
  @Test
  void theInventoryReconcilesTheOwnedRows() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-inventory", 2);
    Long running = rows.placedOn(row.id, WorkspaceRuntimeStatus.PROVISIONING);
    Long gone = rows.placedOn(row.id, WorkspaceRuntimeStatus.RUNNING);
    FakeWorkspacesRunner runner = greeted("wr-inventory");

    runner.send(new Inventory(List.of(new HeldContainer(running, true, "h")), List.of(), null));

    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (rows.read(gone).runtimeStatus != WorkspaceRuntimeStatus.STOPPED
        && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertEquals(WorkspaceRuntimeStatus.RUNNING, rows.read(running).runtimeStatus);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, rows.read(gone).runtimeStatus);
  }

  /** A hello claiming a container of a row the runner owns is answered with it adopted. */
  @Test
  void theHelloIsAnsweredWithTheHeldRowsItOwns() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-adopt", 2);
    Long owned = rows.placedOn(row.id, WorkspaceRuntimeStatus.RUNNING);
    FakeWorkspacesRunner runner = dial("wr-adopt");

    runner.send(FakeWorkspacesRunner.hello(PIN, List.of(String.valueOf(owned), "999999999")));

    Ack ack = runner.expect(Ack.class);
    assertEquals(List.of(String.valueOf(owned)), ack.adopted());
  }
}
