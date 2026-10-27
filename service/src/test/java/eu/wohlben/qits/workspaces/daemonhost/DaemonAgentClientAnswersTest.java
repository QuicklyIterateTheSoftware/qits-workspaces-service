package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.AgentDaemon;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The daemon's cleanup-check answer (qits-1152, D5) as {@link DaemonAgentClient} reads it: {@code
 * {clean, dirty: [repository], unpushed: [{repository, branch|null, commits}], busy?}}, what its
 * status says (qits-1233): 200 a check, 404 an agent the daemon does not know, anything else none;
 * an accepted start's {@code launched} flag (qits-1225); and a start's own body, with and without
 * the agent-config checkout (qits-1382).
 */
class DaemonAgentClientAnswersTest {

  @Test
  void aCleanAnswerIsClean() {
    AgentDaemon.CleanupCheck check =
        DaemonAgentClient.cleanupCheckOf(
            new JsonObject("{\"clean\":true,\"dirty\":[],\"unpushed\":[]}"));
    assertTrue(check.clean());
    assertEquals(List.of(), check.dirty());
  }

  @Test
  void busyIsReadAndADaemonThatDoesNotSayItIsNotBusy() {
    // qits-1233: optional on the wire, so the daemon and this service release in either order.
    assertTrue(
        DaemonAgentClient.cleanupCheckOf(
                new JsonObject("{\"clean\":true,\"dirty\":[],\"unpushed\":[],\"busy\":true}"))
            .busy());
    assertFalse(
        DaemonAgentClient.cleanupCheckOf(
                new JsonObject("{\"clean\":true,\"dirty\":[],\"unpushed\":[],\"busy\":false}"))
            .busy());
    assertFalse(
        DaemonAgentClient.cleanupCheckOf(
                new JsonObject("{\"clean\":true,\"dirty\":[],\"unpushed\":[]}"))
            .busy());
  }

  @Test
  void leftoverWorkIsNamedRepositoryByRepository() {
    AgentDaemon.CleanupCheck check =
        DaemonAgentClient.cleanupCheckOf(
            new JsonObject(
                "{\"clean\":false,\"dirty\":[\"qits-ci-service\"],\"unpushed\":["
                    + "{\"repository\":\"wrapper\",\"branch\":\"ticket/qits-1\",\"commits\":1},"
                    + "{\"repository\":\"qits-ci-service\",\"branch\":null,\"commits\":3}]}"));
    assertFalse(check.clean());
    assertEquals(List.of("qits-ci-service"), check.dirty());
    assertEquals(
        List.of(
            new AgentDaemon.CleanupCheck.Unpushed("wrapper", "ticket/qits-1", 1),
            new AgentDaemon.CleanupCheck.Unpushed("qits-ci-service", null, 3)),
        check.unpushed());
  }

  @Test
  void workInAReleaseIsReadAndAnOlderDaemonReadsDeliveredAsClean() {
    // qits-1688.
    AgentDaemon.CleanupCheck check =
        DaemonAgentClient.cleanupCheckOf(
            new JsonObject(
                "{\"clean\":true,\"delivered\":false,\"dirty\":[],\"unpushed\":[],"
                    + "\"inRelease\":[{\"repository\":\"wrapper\",\"branch\":\"ticket/qits-1\","
                    + "\"commits\":2}],\"busy\":false}"));
    assertTrue(check.clean());
    assertFalse(check.delivered());
    assertEquals(
        List.of(new AgentDaemon.CleanupCheck.Unpushed("wrapper", "ticket/qits-1", 2)),
        check.inRelease());

    assertTrue(
        DaemonAgentClient.cleanupCheckOf(
                new JsonObject("{\"clean\":true,\"dirty\":[],\"unpushed\":[]}"))
            .delivered());
    assertFalse(
        DaemonAgentClient.cleanupCheckOf(
                new JsonObject("{\"clean\":false,\"dirty\":[\"wrapper\"],\"unpushed\":[]}"))
            .delivered());
  }

  @Test
  void aCheckAnswered404IsAnUnknownAgentNotAnUnreachableDaemon() {
    // qits-1233: the daemon answers 404 for an agent it already removed.
    AgentDaemon.CleanupAnswer answer =
        DaemonAgentClient.cleanupAnswerOf(404, "{\"message\":\"Unknown agent: a1\"}");
    assertTrue(answer.isUnknownAgent());
    assertFalse(answer.isFound());
  }

  @Test
  void aMissingEndpointA5xxOrAnUnreadableBodyIsUnreachable() {
    for (AgentDaemon.CleanupAnswer answer :
        List.of(
            DaemonAgentClient.cleanupAnswerOf(404, "{\"message\":\"No such endpoint\"}"),
            DaemonAgentClient.cleanupAnswerOf(503, "{\"message\":\"Starting\"}"),
            DaemonAgentClient.cleanupAnswerOf(401, null),
            DaemonAgentClient.cleanupAnswerOf(200, "not json"))) {
      assertEquals(AgentDaemon.Outcome.UNREACHABLE, answer.outcome(), answer.toString());
      assertFalse(answer.isFound());
      assertFalse(answer.isUnknownAgent());
    }
  }

  @Test
  void aCheckAnswered200IsFound() {
    AgentDaemon.CleanupAnswer answer =
        DaemonAgentClient.cleanupAnswerOf(200, "{\"clean\":true,\"dirty\":[],\"unpushed\":[]}");
    assertTrue(answer.isFound());
    assertTrue(answer.check().clean());
  }

  @Test
  void aBodyThatIsNoCheckIsNone() {
    assertNull(DaemonAgentClient.cleanupCheckOf(null));
    assertNull(DaemonAgentClient.cleanupCheckOf(new JsonObject("{\"error\":\"x\"}")));
  }

  @Test
  void aStartThatLaunchedNothingSaysSo() {
    AgentDaemon.Started started =
        DaemonAgentClient.startedOf(
            new JsonObject(
                "{\"commandId\":\"c\",\"sessionId\":\"s\",\"resumed\":true,\"launched\":false}"));
    assertEquals(AgentDaemon.Outcome.OK, started.outcome());
    assertEquals("c", started.commandId());
    assertEquals("s", started.sessionId());
    assertTrue(started.resumed());
    assertFalse(started.launched());
  }

  @Test
  void aStartThatLaunchedAHarnessSaysSo() {
    assertTrue(
        DaemonAgentClient.startedOf(new JsonObject("{\"commandId\":\"c\",\"launched\":true}"))
            .launched());
  }

  @Test
  void aStartWithoutTheFlagReadsAsLaunched() {
    assertTrue(DaemonAgentClient.startedOf(new JsonObject("{\"commandId\":\"c\"}")).launched());
    assertTrue(DaemonAgentClient.startedOf(null).launched(), "no body, or none that parses");
  }

  @Test
  void aStartWithNoAgentConfigOmitsTheKey() {
    JsonObject body = DaemonAgentClient.startBody(start(null));
    assertFalse(body.containsKey("agentConfig"), body.encode());
  }

  @Test
  void aStartWithAgentConfigCarriesItsTwoFields() {
    JsonObject body =
        DaemonAgentClient.startBody(
            start(new AgentDaemon.AgentConfig("repo-1", "proj-1/qits-agent-config")));
    JsonObject agentConfig = body.getJsonObject("agentConfig");
    assertEquals("repo-1", agentConfig.getString("repositoryId"));
    assertEquals("proj-1/qits-agent-config", agentConfig.getString("repositoryPath"));
  }

  /** A start otherwise shaped like a fresh agent's, carrying {@code agentConfig} or null. */
  private static AgentDaemon.Start start(AgentDaemon.AgentConfig agentConfig) {
    return new AgentDaemon.Start(
        "a1", "w1", "e1", "ticket/w1", "CLAUDE", null, Map.of(), "Work the ticket.", null, null,
        agentConfig);
  }
}
