package eu.wohlben.qits.workspaces.runnerhost;

import static eu.wohlben.qits.workspaces.runnerhost.RunnerFrameHarness.PIN;
import static eu.wohlben.qits.workspaces.runnerhost.RunnerFrameHarness.answer;
import static eu.wohlben.qits.workspaces.runnerhost.RunnerFrameHarness.clientId;
import static eu.wohlben.qits.workspaces.runnerhost.RunnerFrameHarness.eventually;
import static eu.wohlben.qits.workspaces.runnerhost.RunnerFrameHarness.throughout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.workspaces.control.ProvisionResult;
import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunnerCapabilities;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.testing.contracts.ContractRow;
import eu.wohlben.qits.workspaces.testing.contracts.ContractTable;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import eu.wohlben.qits.workspaces.testing.contracts.PactMessage;
import eu.wohlben.qits.workspacesrunner.protocol.Delete;
import eu.wohlben.qits.workspacesrunner.protocol.Estate;
import eu.wohlben.qits.workspacesrunner.protocol.HealthCheck;
import eu.wohlben.qits.workspacesrunner.protocol.ProbeLogin;
import eu.wohlben.qits.workspacesrunner.protocol.Stop;
import eu.wohlben.qits.workspacesrunner.protocol.Take;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * <b>What a workspace runner tells this service, and what the service does with it</b> (qits-950,
 * A2): one row per frame qits-workspaces-runner-daemon records in a runner-side state, built from
 * its pinned golden masters ({@code qits.workspaces-runner-golden-masters.version}) into the message
 * pact {@code pacts/qits-workspaces-service_qits-workspaces-runner-daemon.json} ({@code
 * ConsumerPactFilesTest} writes it; the runner verifies it).
 *
 * <p><b>Each row is the frame fed to the real thing.</b> {@link RunnerFramesConsumerPactTest} runs
 * it inside the application: the pact's own message text goes onto a real socket from a {@link
 * FakeWorkspacesRunner}, {@link WorkspaceRunnerSocket} decodes it with the real codec and dispatches
 * it, and the row asserts the effect on the rows, the runner row, the frames sent back and the
 * request the frame answers. The setting up — the runner greeted, a row queued, a stop pressed — is
 * the service's own side and uses typed frames; the frame under contract never is.
 *
 * <p><b>The runner's frozen values become the row's own</b> ({@link PactMessage#text(Map)}): the
 * runner records {@code rowId} 5 and request {@code 00000000-…-0001}; a row seeds its rows and
 * swaps in their ids, and in the request id the service actually sent. {@code hello}'s {@code
 * runnerVersion} (a frozen string) is set to the pin, so the hello is greeted rather than upgraded.
 * Every text fed is first matched against the interaction's rules.
 *
 * <p><b>The trigger is the runner's</b>: {@code qits-trigger} names what on the runner made it send
 * the frame — a frame of this service's ({@code take}, {@code stop}, {@code ack}, …), an event
 * ({@code connect}, {@code reconnect}) or a schedule ({@code heartbeat}, {@code exit-watch}) — as
 * its index records it; {@link GoldenMasters#messageInteraction} refuses a row naming another.
 */
public final class RunnerFramesContract {

  // qits-workspaces-runner-daemon's states (golden masters 2026.1021.200248).
  static final String NO_WORKSPACES = "a runner whose node holds no workspaces";
  static final String RECONNECTING =
      "a runner reconnecting while its node holds a running workspace";
  static final String HOLDS_RUNNING = "a runner whose node holds a running workspace";
  static final String HOLDS_STOPPED = "a runner whose node holds a stopped workspace";
  static final String FREE_SLOT = "a runner with a free slot and queued work";
  static final String ASKED_TO_TAKE = "a runner asked to take a new workspace";
  static final String LAUNCH_FAILS = "a runner asked to take a workspace whose launch fails";
  static final String EXITS = "a runner whose running workspace exits by itself";
  static final String HEALTHY = "a runner whose node is healthy and logged in";
  static final String NOT_LOGGED_IN = "a runner whose node is not logged in";
  static final String NO_ESTATE = "a runner that has heard no estate yet";

  private static final GoldenMasters.Provider RUNNER = GoldenMasters.WORKSPACES_RUNNER;

  public static final ContractTable TABLE =
      new ContractTable(
          RUNNER,
          List.of(
              // --- the greeting --------------------------------------------------------------
              row(event("connect"), NO_WORKSPACES, "hello", RunnerFramesContract::helloIsGreeted),
              row(
                  event("reconnect"),
                  RECONNECTING,
                  "hello",
                  RunnerFramesContract::helloAdoptsTheHeldRow),
              row(
                  schedule("heartbeat"),
                  NO_WORKSPACES,
                  "heartbeat",
                  RunnerFramesContract::heartbeatKeepsThePresence),
              // --- the inventory -------------------------------------------------------------
              row(
                  frame("ack"),
                  NO_WORKSPACES,
                  "inventory",
                  RunnerFramesContract::anEmptyInventoryChangesNothingOwned),
              row(
                  frame("ack"),
                  HOLDS_RUNNING,
                  "inventory",
                  RunnerFramesContract::aRunningContainerMakesItsRowRunning),
              row(
                  frame("ack"),
                  HOLDS_STOPPED,
                  "inventory",
                  RunnerFramesContract::aStoppedContainerMakesItsRowStopped),
              // --- the claim -----------------------------------------------------------------
              row(frame("backlog"), FREE_SLOT, "reserve", RunnerFramesContract::reserveTakesARow),
              row(
                  frame("take"),
                  ASKED_TO_TAKE,
                  "launched",
                  RunnerFramesContract::launchedWaitsForTheDaemon),
              row(
                  frame("take"),
                  LAUNCH_FAILS,
                  "launchFailed",
                  RunnerFramesContract::launchFailedFailsTheRow),
              row(
                  schedule("exit-watch"),
                  EXITS,
                  "exited",
                  RunnerFramesContract::exitedStopsTheRow),
              // --- the verbs routed to the owner ---------------------------------------------
              row(
                  frame("stop"),
                  HOLDS_RUNNING,
                  "stopped",
                  RunnerFramesContract::stoppedAnswersTheStop),
              row(
                  frame("delete"),
                  HOLDS_RUNNING,
                  "deleted",
                  RunnerFramesContract::deletedAnswersTheDelete),
              // --- health and login ----------------------------------------------------------
              row(
                  frame("healthCheck"),
                  HEALTHY,
                  "healthChecked",
                  RunnerFramesContract::aPassingCheckIsStoredAndReinstates),
              row(
                  frame("healthCheck"),
                  NO_ESTATE,
                  "healthChecked",
                  RunnerFramesContract::aFailingCheckIsStoredAndQuarantines),
              row(
                  frame("probeLogin"),
                  NO_ESTATE,
                  "loginState",
                  RunnerFramesContract::aProbedLoginStateIsKept),
              row(frame("estate"), HEALTHY, "loginState", RunnerFramesContract::theLoginStateIsKept),
              row(
                  frame("estate"),
                  NOT_LOGGED_IN,
                  "loginState",
                  RunnerFramesContract::theLoginStateIsKept)));

  private RunnerFramesContract() {}

  private static ContractRow row(
      Trigger trigger, String state, String frame, ContractRow.Receive receive) {
    return ContractRow.message(trigger, state, frame, receive);
  }

  private static Trigger frame(String type) {
    return Trigger.sent(RUNNER, "frame", type);
  }

  private static Trigger event(String event) {
    return Trigger.sent(RUNNER, "event", event);
  }

  private static Trigger schedule(String schedule) {
    return Trigger.sent(RUNNER, "schedule", schedule);
  }

  // --- the greeting ------------------------------------------------------------------------------

  /**
   * {@code hello} at the pin: {@code ack} with the ROW's slots (the hello's own are advisory),
   * {@code estate} of the rows it owns — none — and {@code backlog}; the version and the machine it
   * describes are recorded on the runner row.
   */
  static void helloIsGreeted(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("hello");
    WorkspaceRunner runner = h.rows.eligible(client, 1);
    FakeWorkspacesRunner socket = h.dial(client);

    socket.sendText(message.text(Map.of(), atThePin()));

    Ack ack = socket.expect(Ack.class);
    assertEquals(1, ack.slots(), "the row's slots, never the hello's");
    assertEquals(List.of(), ack.adopted());
    assertEquals(List.of(), socket.expect(Estate.class).owned());
    socket.expect(Backlog.class);
    WorkspaceRunnerDto seen = h.views.view(h.rows.runner(runner.id));
    assertEquals(PIN, seen.version());
    assertEquals(message.example().path("capabilities").path("arch").asText(), seen.arch());
    assertTrue(seen.connected());
  }

  /**
   * A reconnecting {@code hello} holding a container of a row it owns: {@code ack} adopts it, the
   * {@code estate} lists it, and the row is untouched — still RUNNING on this runner.
   */
  static void helloAdoptsTheHeldRow(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("reconnect");
    WorkspaceRunner runner = h.rows.eligible(client, 2);
    Long held = h.rows.placedOn(runner.id, WorkspaceRuntimeStatus.RUNNING);
    FakeWorkspacesRunner socket = h.dial(client);

    socket.sendText(message.text(Map.of("rowId", held), atThePin()));

    assertEquals(List.of(String.valueOf(held)), socket.expect(Ack.class).adopted());
    assertEquals(List.of(held), socket.expect(Estate.class).owned());
    Workspace row = h.rows.read(held);
    assertEquals(WorkspaceRuntimeStatus.RUNNING, row.runtimeStatus);
    assertEquals(runner.id, row.runnerId);
  }

  /** {@code heartbeat}: answered with nothing, the socket kept and the runner present. */
  static void heartbeatKeepsThePresence(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("heartbeat");
    WorkspaceRunner runner = h.rows.eligible(client, 1);
    FakeWorkspacesRunner socket = h.greeted(client);

    socket.sendText(message.text());

    assertNull(socket.poll(Duration.ofMillis(500)), "a heartbeat is answered with nothing");
    assertFalse(socket.isClosed());
    assertTrue(h.registry.connected(runner.id));
    assertTrue(h.registry.presence(runner.id));
  }

  // --- the inventory -----------------------------------------------------------------------------

  /**
   * An inventory holding nothing, from a runner whose rows hold no container — one STOPPED, one
   * QUEUED for it: the agent home volume is recorded and neither row moves. (An owned RUNNING row
   * would be STOPPED and a PROVISIONING one queued again: what "nothing held" means for a live row.)
   */
  static void anEmptyInventoryChangesNothingOwned(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("inventory-empty");
    WorkspaceRunner runner = h.rows.eligible(client, 2);
    Long stopped = h.rows.placedOn(runner.id, WorkspaceRuntimeStatus.STOPPED);
    Long queued = h.rows.queued(runner.id, Instant.now());
    FakeWorkspacesRunner socket = h.greeted(client);

    socket.sendText(message.text());

    assertVolumeRecorded(h, runner, message);
    throughout(
        Duration.ofMillis(500),
        () ->
            List.of(
                h.rows.read(stopped).runtimeStatus,
                h.rows.read(queued).runtimeStatus,
                Objects.toString(h.rows.read(stopped).runnerId),
                Objects.toString(h.rows.read(queued).runnerId)),
        seen ->
            seen.equals(
                List.of(
                    WorkspaceRuntimeStatus.STOPPED,
                    WorkspaceRuntimeStatus.QUEUED,
                    runner.id.toString(),
                    runner.id.toString())),
        "the runner's rows");
  }

  /**
   * An inventory holding the row's container running: a row PROVISIONING with no daemon awaited (a
   * start this process no longer follows) is RUNNING — and still this runner's, never reassigned.
   */
  static void aRunningContainerMakesItsRowRunning(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("inventory-running");
    WorkspaceRunner runner = h.rows.eligible(client, 2);
    Long held = h.rows.placedOn(runner.id, WorkspaceRuntimeStatus.PROVISIONING);
    FakeWorkspacesRunner socket = h.greeted(client);

    socket.sendText(message.text(Map.of("rowId", held)));

    h.awaitStatus(held, WorkspaceRuntimeStatus.RUNNING);
    assertEquals(runner.id, h.rows.read(held).runnerId, "never reassigned");
    assertVolumeRecorded(h, runner, message);
  }

  /** An inventory holding the row's container stopped: the RUNNING row is STOPPED, still its own. */
  static void aStoppedContainerMakesItsRowStopped(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("inventory-stopped");
    WorkspaceRunner runner = h.rows.eligible(client, 2);
    Long held = h.rows.placedOn(runner.id, WorkspaceRuntimeStatus.RUNNING);
    FakeWorkspacesRunner socket = h.greeted(client);

    socket.sendText(message.text(Map.of("rowId", held)));

    h.awaitStatus(held, WorkspaceRuntimeStatus.STOPPED);
    assertEquals(runner.id, h.rows.read(held).runnerId, "still sticky");
    assertVolumeRecorded(h, runner, message);
  }

  private static void assertVolumeRecorded(
      RunnerFrameHarness h, WorkspaceRunner runner, PactMessage message) throws Exception {
    String volume = message.example().path("dotClaudeVolume").asText();
    assertEquals(
        volume,
        eventually(
            () -> h.views.view(h.rows.runner(runner.id)).dotClaudeVolume(),
            volume::equals,
            "the agent home volume"));
  }

  // --- the claim ---------------------------------------------------------------------------------

  /** {@code reserve} with a free slot and a never-placed QUEUED row: it is taken — the CAS. */
  static void reserveTakesARow(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("reserve");
    WorkspaceRunner runner = h.rows.eligible(client, 1);
    Long queued = h.rows.queued(null, Instant.now());
    FakeWorkspacesRunner socket = h.greeted(client);

    socket.sendText(message.text());

    Take take = (Take) answer(socket);
    assertEquals(queued.longValue(), take.rowId());
    Workspace row = h.rows.read(queued);
    assertEquals(WorkspaceRuntimeStatus.PROVISIONING, row.runtimeStatus);
    assertEquals(runner.id, row.runnerId);
  }

  /**
   * {@code launched} after a take: the row stays PROVISIONING while its daemon is awaited, and only
   * the daemon's report makes it RUNNING.
   */
  static void launchedWaitsForTheDaemon(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    CountDownLatch awaited = new CountDownLatch(1);
    CompletableFuture<ProvisionResult> daemon = new CompletableFuture<>();
    h.provisioner.answerRunnerRows(
        rowId -> {
          awaited.countDown();
          try {
            return Optional.of(daemon.get(20, TimeUnit.SECONDS));
          } catch (Exception e) {
            return Optional.empty();
          }
        });
    String client = clientId("launched");
    WorkspaceRunner runner = h.rows.eligible(client, 1);
    Long queued = h.rows.queued(null, Instant.now());
    FakeWorkspacesRunner socket = h.greeted(client);
    socket.send(new Reserve());
    assertEquals(queued.longValue(), ((Take) answer(socket)).rowId());

    socket.sendText(message.text(Map.of("rowId", queued)));

    assertTrue(awaited.await(10, TimeUnit.SECONDS), "the daemon is awaited after launched");
    assertTrue(h.claims.awaitingDaemon(queued));
    Workspace launched = h.rows.read(queued);
    assertEquals(WorkspaceRuntimeStatus.PROVISIONING, launched.runtimeStatus);
    assertEquals(runner.id, launched.runnerId);
    daemon.complete(ProvisionResult.ok(""));
    h.awaitStatus(queued, WorkspaceRuntimeStatus.RUNNING);
  }

  /** {@code launchFailed} after a take: FAILED with the runner's words as the runtime error. */
  static void launchFailedFailsTheRow(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("launch-failed");
    WorkspaceRunner runner = h.rows.eligible(client, 1);
    Long queued = h.rows.queued(null, Instant.now());
    FakeWorkspacesRunner socket = h.greeted(client);
    socket.send(new Reserve());
    assertEquals(queued.longValue(), ((Take) answer(socket)).rowId());

    socket.sendText(message.text(Map.of("rowId", queued)));

    h.awaitStatus(queued, WorkspaceRuntimeStatus.FAILED);
    Workspace failed = h.rows.read(queued);
    assertEquals(message.example().path("detail").asText(), failed.runtimeError);
    assertEquals(runner.id, failed.runnerId);
  }

  /**
   * {@code exited}: the RUNNING row is STOPPED and stays its runner's. Its exit code is decoded and
   * not kept: no runtime error is written.
   */
  static void exitedStopsTheRow(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("exited");
    WorkspaceRunner runner = h.rows.eligible(client, 1);
    Long running = h.rows.placedOn(runner.id, WorkspaceRuntimeStatus.RUNNING);
    FakeWorkspacesRunner socket = h.greeted(client);

    socket.sendText(message.text(Map.of("rowId", running)));

    h.awaitStatus(running, WorkspaceRuntimeStatus.STOPPED);
    Workspace exited = h.rows.read(running);
    assertEquals(runner.id, exited.runnerId, "still sticky");
    assertNull(exited.runtimeError);
  }

  // --- the verbs routed to the owner -------------------------------------------------------------

  /** {@code stopped} answers the routed stop: the awaiting verb returns, and the row is STOPPED. */
  static void stoppedAnswersTheStop(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("stopped");
    WorkspaceRunner runner = h.rows.eligible(client, 1);
    Long running = h.rows.placedOn(runner.id, WorkspaceRuntimeStatus.RUNNING);
    FakeWorkspacesRunner socket = h.greeted(client);
    CompletableFuture<Void> stopping =
        CompletableFuture.runAsync(() -> h.workspaces.stopContainer(running));
    assertEquals(running.longValue(), socket.await(Stop.class).rowId());
    assertFalse(stopping.isDone(), "the verb waits for the runner");

    socket.sendText(message.text(Map.of("rowId", running)));

    stopping.get(10, TimeUnit.SECONDS);
    Workspace stopped = h.rows.read(running);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, stopped.runtimeStatus);
    assertEquals(runner.id, stopped.runnerId, "still sticky");
  }

  /**
   * {@code deleted} answers the routed delete: the awaiting verb returns, the row is STOPPED on no
   * runner, and the runner is sent its new estate without it.
   */
  static void deletedAnswersTheDelete(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("deleted");
    WorkspaceRunner runner = h.rows.eligible(client, 1);
    Long running = h.rows.placedOn(runner.id, WorkspaceRuntimeStatus.RUNNING);
    FakeWorkspacesRunner socket = h.greeted(client);
    CompletableFuture<Void> deleting =
        CompletableFuture.runAsync(() -> h.workspaces.deleteContainer(running));
    assertEquals(running.longValue(), socket.await(Delete.class).rowId());
    assertFalse(deleting.isDone(), "the verb waits for the runner");

    socket.sendText(message.text(Map.of("rowId", running)));

    deleting.get(10, TimeUnit.SECONDS);
    Workspace deleted = h.rows.read(running);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, deleted.runtimeStatus);
    assertNull(deleted.runnerId);
    assertEquals(List.of(), socket.await(Estate.class).owned());
  }

  // --- health and login --------------------------------------------------------------------------

  /**
   * A passing {@code healthChecked} answering a quarantined runner's first check: the report is
   * stored as the runner sent it — every check, its data and the slots it reports included
   * (qits-948's class) — and the runner is reinstated with its row's slots.
   */
  static void aPassingCheckIsStoredAndReinstates(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("health-pass");
    WorkspaceRunner runner = h.rows.registered(client, 2);
    FakeWorkspacesRunner socket = h.dial(client);
    socket.send(FakeWorkspacesRunner.hello(PIN, List.of()));
    assertEquals(0, socket.expect(Ack.class).slots());
    socket.expect(Quarantined.class);
    socket.expect(Estate.class);
    socket.expect(Backlog.class);
    String requestId = socket.expect(HealthCheck.class).requestId();

    String sent = message.text(Map.of("requestId", requestId));
    socket.sendText(sent);

    assertEquals(WorkspaceRunnerHealth.BY_HEALTH_CHECK, socket.await(Reinstated.class).by());
    assertEquals(2, socket.expect(Ack.class).slots());
    assertFalse(h.rows.runner(runner.id).quarantined());
    assertStoredAsReported(h, runner, message, requestId);
  }

  /**
   * A failing {@code healthChecked} answering a check the service asked of a runner in service:
   * stored as reported, and the runner quarantined — {@code quarantined}, then {@code ack{0}}.
   */
  static void aFailingCheckIsStoredAndQuarantines(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("health-fail");
    WorkspaceRunner runner = h.rows.eligible(client, 2);
    FakeWorkspacesRunner socket = h.greeted(client);
    String requestId = h.health.request(runner.id);
    assertEquals(requestId, socket.await(HealthCheck.class).requestId());

    socket.sendText(message.text(Map.of("requestId", requestId)));

    Quarantined quarantined = socket.await(Quarantined.class);
    String detail = message.example().path("detail").asText();
    assertTrue(
        quarantined.reason().contains(detail.substring(0, Math.min(40, detail.length()))),
        "the runner's words are the quarantine's reason: " + quarantined.reason());
    assertEquals(0, socket.expect(Ack.class).slots());
    assertTrue(h.rows.runner(runner.id).quarantined());
    assertStoredAsReported(h, runner, message, requestId);
  }

  private static void assertStoredAsReported(
      RunnerFrameHarness h, WorkspaceRunner runner, PactMessage message, String requestId)
      throws Exception {
    ObjectNode reported = message.example();
    JsonNode stored =
        eventually(
            () -> h.capabilities(runner.id).path(WorkspaceRunnerCapabilities.HEALTH),
            health -> requestId.equals(health.path("requestId").asText()),
            "the stored health check");
    assertEquals(requestId, stored.path("requestId").asText());
    assertEquals(reported.path("ok").asBoolean(), stored.path("ok").asBoolean());
    assertEquals(reported.path("detail").asText(), stored.path("detail").asText());
    assertEquals(reported.path("checks"), stored.path("checks"), "every check, as the runner said");
    for (JsonNode check : reported.path("checks")) {
      if (check.path("data").has("slots")) {
        JsonNode kept = null;
        for (JsonNode s : stored.path("checks")) {
          if (s.path("name").equals(check.path("name"))) {
            kept = s;
          }
        }
        assertEquals(
            check.path("data").path("slots"),
            kept == null ? null : kept.path("data").path("slots"),
            check.path("name").asText() + ": the slots the runner reported");
      }
    }
    WorkspaceRunnerDto.Health listed = h.views.view(h.rows.runner(runner.id)).health();
    assertEquals(reported.path("detail").asText(), listed.detail());
    assertEquals(reported.path("checks").size(), listed.checks().size());
  }

  /** {@code loginState} answering the service's {@code probeLogin}: the runner DTO's login. */
  static void aProbedLoginStateIsKept(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("login-probed");
    WorkspaceRunner runner = h.rows.eligible(client, 1);
    FakeWorkspacesRunner socket = h.greeted(client);
    assertTrue(h.registry.send(runner.id, new ProbeLogin()));
    socket.await(ProbeLogin.class);

    socket.sendText(message.text());

    assertLoginKept(h, runner, message);
  }

  /** {@code loginState} the runner sent on its own, after an estate: the runner DTO's login. */
  static void theLoginStateIsKept(PactMessage message) throws Exception {
    RunnerFrameHarness h = RunnerFrameHarness.current();
    String client = clientId("login");
    WorkspaceRunner runner = h.rows.eligible(client, 1);
    FakeWorkspacesRunner socket = h.greeted(client);

    socket.sendText(message.text());

    assertLoginKept(h, runner, message);
  }

  private static void assertLoginKept(
      RunnerFrameHarness h, WorkspaceRunner runner, PactMessage message) throws Exception {
    ObjectNode said = message.example();
    WorkspaceRunnerDto.Login login =
        eventually(
            () -> h.views.view(h.rows.runner(runner.id)).login(), Objects::nonNull, "the login");
    assertEquals(
        new WorkspaceRunnerDto.Login(
            said.path("claude").asText(),
            said.path("kimi").asText(),
            Instant.parse(said.path("checkedAt").asText())),
        login);
  }

  /** A {@code hello} in the pin's version: the runner's frozen {@code runnerVersion} swapped. */
  private static java.util.function.Consumer<ObjectNode> atThePin() {
    return frame -> frame.put("runnerVersion", PIN);
  }
}
