package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.AgentActivityState;
import eu.wohlben.qits.workspaces.control.DispatchService;
import eu.wohlben.qits.workspaces.control.FakeAgentActivity;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The half of a delivery that needs config and collaborators the shipped defaults deliberately do
 * not have: the compaction knob turned <b>on</b>, a launch window short enough that giving up is a
 * thing a test can watch happen, and the turn-boundary rollup driven by hand instead of by a
 * daemon's lifecycle hooks.
 *
 * <p>A profile of its own, and therefore a Quarkus restart, because the two knobs are {@code
 * @ConfigProperty} fields on an application-scoped bean and the rollup is an enabled alternative —
 * there is no per-test way to move any of them. It is one restart for all of it rather than one
 * each, which is why waiting out a turn and a slash command share a class.
 *
 * <p><b>The wait is what the epic is about.</b> A phase prompt is produced by the agent's own last
 * act of a turn, so a delivery aimed at it arrives while that turn is still running unless something
 * stands in between. {@code WorkspaceAgentActivity}'s rollup is what says which: BUSY is generating,
 * IDLE is a turn finished and control yielded back, WAITING is blocked on the user. The cases below
 * pin all four of its values plus the absence.
 *
 * <p>It stubs the daemon on {@link AgentDispatchControllerTest#latchedPort()} — the same latch, so
 * the whole module still uses one port for this fake and two classes can never be bound to different
 * ones at once.
 */
@QuarkusTest
@TestProfile(AgentTurnCompactionAndWindowTest.CompactingProfile.class)
public class AgentTurnCompactionAndWindowTest {

  private static final String AN_AGENT_IS_RUNNING =
      "{\"entries\":[{\"command\":{\"id\":\"cmd-live\",\"status\":\"RUNNING\",\"kind\":\"CHAT\","
          + "\"agentSessions\":[{\"sessionId\":\"s-1\"}]}}]}";

  public static class CompactingProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-agent-turn-window-test-repos");
        return Map.of(
            "qits.test.origins-dir", tempDir.toString(),
            "qits.workspace.daemon-api-port",
                String.valueOf(AgentDispatchControllerTest.latchedPort()),
            "qits.workspace.daemon-api-token", "test-delivery-daemon-token",
            "qits.workspace.agent-dispatch.poll-interval-ms", "50",
            // Short enough to watch expire, long enough that a healthy daemon in the other test
            // answers inside it many times over.
            "qits.workspace.agent-dispatch.launch-window-ms", "1500",
            // The whole point of this profile: the knob the deployment ships OFF.
            "qits.workspace.agent-dispatch.compact-before-turn", "true");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public java.util.Set<Class<?>> getEnabledAlternatives() {
      // Opt into driving the turn-boundary rollup by hand without disturbing the real
      // WorkspaceDaemonRegistry the other service tests and the daemon ITs rely on.
      return java.util.Set.of(FakeAgentActivity.class);
    }
  }

  @Inject FakeRepositoryLookup repositories;
  @Inject FakeAgentActivity agentActivity;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private Vertx daemonVertx;

  private final AtomicReference<String> runningCommands =
      new AtomicReference<>(AN_AGENT_IS_RUNNING);

  /** Every turn the stub took, in arrival order — a map by path could not count two of them. */
  private final List<String> turnTexts = new CopyOnWriteArrayList<>();

  private final List<String> launchContexts = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startFakeDaemon() throws Exception {
    turnTexts.clear();
    launchContexts.clear();
    runningCommands.set(AN_AGENT_IS_RUNNING);
    daemonVertx = Vertx.vertx();
    daemonVertx
        .createHttpServer()
        .requestHandler(
            req -> {
              String path = req.path();
              if (path.endsWith("/agents/turn")) {
                req.bodyHandler(
                    buffer -> {
                      turnTexts.add(new JsonObject(buffer.toString()).getString("text"));
                      req.response()
                          .putHeader("Content-Type", "application/json")
                          .end("{\"delivered\":true,\"commandId\":\"c\",\"kind\":\"CHAT\"}");
                    });
                return;
              }
              if (path.endsWith("/agents")) {
                req.bodyHandler(
                    buffer -> {
                      launchContexts.add(
                          new JsonObject(buffer.toString()).getString("initialContext"));
                      req.response()
                          .putHeader("Content-Type", "application/json")
                          .end("{\"command\":{\"id\":\"cmd-1\",\"status\":\"RUNNING\"}}");
                    });
                return;
              }
              if (path.endsWith("/commands")) {
                req.response()
                    .putHeader("Content-Type", "application/json")
                    .end(runningCommands.get());
                return;
              }
              req.response().setStatusCode(404).end("{\"message\":\"No such endpoint\"}");
            })
        .listen(AgentDispatchControllerTest.latchedPort(), "127.0.0.1")
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
  }

  @AfterEach
  void stopFakeDaemon() throws Exception {
    closeDaemon();
  }

  private void closeDaemon() throws Exception {
    if (daemonVertx != null) {
      daemonVertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      daemonVertx = null;
    }
  }

  private Long aWorkspaceWithAContainer(String repoId, String label, String branch) {
    workspaceService.createWorkspace(repoId, label, "master", branch);
    Long rowId = workspaceIds.of(repoId, label);
    workspaceService.ensureContainer(rowId);
    return rowId;
  }

  private String seedRepository() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    return repoId;
  }

  private JsonPath deliver(String repoId, String branch, String text, boolean compactFirst) {
    Map<String, Object> body = new HashMap<>();
    body.put("repositoryId", repoId);
    body.put("branch", branch);
    body.put("text", text);
    body.put("compactFirst", Boolean.valueOf(compactFirst));
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/workspaces/api/agent-dispatches/delivery")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }

  private void awaitTurns(int count) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline && turnTexts.size() < count) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /**
   * With the knob on, a compaction is <b>one</b> turn and it is in front — not a mode, not a flag on
   * the prompt's own turn, and never two of them. What the agent then does with the string is the
   * open question the knob's default answers by staying off.
   */
  @Test
  public void theCompactKnobAddsExactlyOneTurnAheadOfThePrompt() throws Exception {
    String repoId = seedRepository();
    aWorkspaceWithAContainer(repoId, "ticket-compacting", "ticket/compacting");

    JsonPath answer = deliver(repoId, "ticket/compacting", "the phase prompt", true);
    assertThat(answer.getBoolean("delivered"), is(true));

    awaitTurns(2);
    assertEquals(List.of("/compact", "the phase prompt"), turnTexts);
    assertTrue(launchContexts.isEmpty(), "a compaction must not start a second agent");
  }

  /** A caller that did not ask gets no compaction, knob or no knob. */
  @Test
  public void aCallerThatDidNotAskForACompactionGetsOnlyItsPrompt() throws Exception {
    String repoId = seedRepository();
    aWorkspaceWithAContainer(repoId, "ticket-uncompacted", "ticket/uncompacted");

    deliver(repoId, "ticket/uncompacted", "just this", false);

    awaitTurns(1);
    Thread.sleep(300);
    assertEquals(List.of("just this"), turnTexts);
  }

  /**
   * <b>The wait the epic is actually about.</b> The phase prompt is produced by the agent's own last
   * act of a turn, so at the instant a delivery is made the agent is typically still generating —
   * BUSY. Saying it then is an interruption of the work it is meant to follow, so nothing is said
   * until the turn ends and control is back with the user.
   */
  @Test
  public void aBusyAgentIsNotSpokenToUntilItsTurnEnds() throws Exception {
    String repoId = seedRepository();
    Long rowId = aWorkspaceWithAContainer(repoId, "ticket-mid-turn", "ticket/mid-turn");
    agentActivity.report(rowId, AgentActivityState.BUSY);

    JsonPath answer = deliver(repoId, "ticket/mid-turn", "the ticket moved to VERIFY", false);
    assertThat(answer.getBoolean("delivered"), is(true));

    // Long enough for many poll ticks at 50 ms: a delivery that ignored the rollup would be here.
    Thread.sleep(500);
    assertTrue(turnTexts.isEmpty(), "the turn was delivered while the agent was mid-turn");

    // The hook the harness fires when it yields control back.
    agentActivity.report(rowId, AgentActivityState.IDLE);

    awaitTurns(1);
    assertEquals(List.of("the ticket moved to VERIFY"), turnTexts);
  }

  /**
   * WAITING is control being with the user too — a permission prompt, or an idle input box. It is
   * not a turn in flight, so there is nothing to wait out and delivering is what a person at that
   * keyboard would do.
   */
  @Test
  public void anAgentWaitingOnTheUserIsSpokenToAtOnce() throws Exception {
    String repoId = seedRepository();
    Long rowId = aWorkspaceWithAContainer(repoId, "ticket-blocked", "ticket/blocked");
    agentActivity.report(rowId, AgentActivityState.WAITING);

    deliver(repoId, "ticket/blocked", "here is the answer", false);

    awaitTurns(1);
    assertEquals(List.of("here is the answer"), turnTexts);
  }

  /**
   * A session reported as over has nobody to interrupt and nobody to hear it: the text becomes a new
   * session's first turn instead. The rollup keeps ENDED for half an hour after the fact, so this is
   * the ordinary shape of a ticket whose agent finished a phase and stopped.
   */
  @Test
  public void aSessionReportedAsEndedIsRelaunchedRatherThanInterrupted() throws Exception {
    String repoId = seedRepository();
    Long rowId = aWorkspaceWithAContainer(repoId, "ticket-finished", "ticket/finished");
    agentActivity.report(rowId, AgentActivityState.ENDED);

    deliver(repoId, "ticket/finished", "next phase, please", false);

    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline && launchContexts.isEmpty()) {
      Thread.sleep(20);
    }
    assertEquals(List.of("next phase, please"), launchContexts);
    assertTrue(turnTexts.isEmpty(), "an ended session was spoken to");
  }

  /**
   * <b>An absent tracker is not a busy agent.</b> A rollup is empty when the daemon has not reported
   * since its socket came back and when the session was launched with {@code activityTracking} off —
   * a per-launch knob, so a live agent can be untracked for its whole life. Blocking on a signal
   * that is never coming would turn every one of those into a delivery dropped a quarter of an hour
   * later, so absence delivers. This is also the shape of an app with no activity backend at all
   * (cli), where the port is unsatisfied and takes the same branch one step earlier.
   */
  @Test
  public void aWorkspaceWithNoReportedActivityIsSpokenToWithoutWaiting() throws Exception {
    String repoId = seedRepository();
    Long rowId = aWorkspaceWithAContainer(repoId, "ticket-untracked", "ticket/untracked");
    agentActivity.forget(rowId);

    long before = System.currentTimeMillis();
    deliver(repoId, "ticket/untracked", "nobody is tracking this one", false);

    awaitTurns(1);
    assertEquals(List.of("nobody is tracking this one"), turnTexts);
    // Well inside the 1500 ms window: it delivered rather than waiting the window out.
    assertTrue(
        System.currentTimeMillis() - before < 1_200,
        "an untracked workspace was made to wait for a signal that is never coming");
  }

  /**
   * A container that never answers inside the window: the wait gives up, says so at WARN, and
   * delivers nothing. The alternative — a thread that waits forever, or a delivery fired at a daemon
   * that is not there — is how a text is lost with nothing in the log about it.
   */
  @Test
  public void aWorkspaceThatNeverAnswersInsideTheWindowGivesUpWithAWarnAndNoDelivery()
      throws Exception {
    String repoId = seedRepository();
    aWorkspaceWithAContainer(repoId, "ticket-deaf", "ticket/deaf");
    // The container row stays; the daemon behind it stops answering, which is exactly what a
    // container that died out of band looks like from here.
    closeDaemon();

    List<LogRecord> warnings =
        warningsWhile(
            () -> {
              JsonPath answer = deliver(repoId, "ticket/deaf", "anybody there?", false);
              assertThat(answer.getBoolean("delivered"), is(false));
              assertThat(answer.getBoolean("launched"), is(false));
            });

    assertTrue(
        warnings.stream()
            .anyMatch(record -> String.valueOf(record.getMessage()).contains("never got a daemon")),
        "giving up must be loud: " + warnings.stream().map(LogRecord::getMessage).toList());
    assertTrue(turnTexts.isEmpty(), "a turn was delivered to a daemon that never answered");
    assertTrue(launchContexts.isEmpty(), "an agent was launched in a container that never answered");
  }

  /**
   * The same give-up one wire over: the daemon answers fine and the agent simply never stops
   * generating. Bounded by the same window, because a wait for a turn that does not end and a wait
   * for a container that does not come up cost the caller the same thing.
   */
  @Test
  public void anAgentThatNeverLeavesBusyGivesUpWithAWarnAndNoDelivery() throws Exception {
    String repoId = seedRepository();
    Long rowId = aWorkspaceWithAContainer(repoId, "ticket-endless", "ticket/endless");
    agentActivity.report(rowId, AgentActivityState.BUSY);

    List<LogRecord> warnings =
        warningsWhile(() -> deliver(repoId, "ticket/endless", "are you done yet?", false));

    assertTrue(
        warnings.stream()
            .anyMatch(record -> String.valueOf(record.getMessage()).contains("still mid-turn")),
        "giving up must be loud: " + warnings.stream().map(LogRecord::getMessage).toList());
    assertTrue(turnTexts.isEmpty(), "a turn was delivered to an agent that never stopped");
    assertTrue(launchContexts.isEmpty(), "a second agent was started beside a busy one");
  }

  /**
   * Run {@code action} with a handler on {@link DispatchService}'s logger and wait for the wait
   * thread's WARN. The give-up is the only thing either caller can observe — it happens after the
   * response, on a thread of the service's own — so the log line is the assertion, which is also why
   * it has to be a WARN and not a debug.
   */
  private List<LogRecord> warningsWhile(Runnable action) throws Exception {
    List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord logged) {
            if (logged.getLevel().intValue() >= Level.WARNING.intValue()) {
              warnings.add(logged);
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(DispatchService.class.getName());
    logger.addHandler(capture);
    try {
      action.run();
      long deadline = System.currentTimeMillis() + 20_000;
      while (System.currentTimeMillis() < deadline && warnings.isEmpty()) {
        Thread.sleep(50);
      }
    } finally {
      logger.removeHandler(capture);
    }
    return warnings;
  }
}
