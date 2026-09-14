package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.DispatchService;
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
 * The two halves of a delivery that need config the shipped defaults deliberately do not have: the
 * compaction knob turned <b>on</b>, and a launch window short enough that giving up is a thing a
 * test can watch happen.
 *
 * <p>A profile of its own, and therefore a Quarkus restart, because both values are read by a
 * {@code @ConfigProperty} field on an application-scoped bean — there is no per-test way to move
 * one. It is one restart for both cases rather than one each, which is why an idle wait expiring and
 * a slash command are in the same class despite having nothing else to do with each other.
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
  }

  @Inject FakeRepositoryLookup repositories;
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
      JsonPath answer = deliver(repoId, "ticket/deaf", "anybody there?", false);
      assertThat(answer.getBoolean("delivered"), is(false));
      assertThat(answer.getBoolean("launched"), is(false));

      long deadline = System.currentTimeMillis() + 20_000;
      while (System.currentTimeMillis() < deadline && warnings.isEmpty()) {
        Thread.sleep(50);
      }
    } finally {
      logger.removeHandler(capture);
    }

    assertTrue(
        warnings.stream()
            .anyMatch(record -> String.valueOf(record.getMessage()).contains("never got a daemon")),
        "giving up must be loud: " + warnings.stream().map(LogRecord::getMessage).toList());
    assertTrue(turnTexts.isEmpty(), "a turn was delivered to a daemon that never answered");
    assertTrue(launchContexts.isEmpty(), "an agent was launched in a container that never answered");
  }
}
