package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.AgentDaemon;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The daemon's cleanup-check answer (qits-1152, D5) as {@link DaemonAgentClient} reads it: {@code
 * {clean, dirty: [repository], unpushed: [{repository, branch|null, commits}], busy?}}, and what its
 * status says (qits-1233): 200 a check, 404 an agent the daemon does not know, anything else none.
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
}
