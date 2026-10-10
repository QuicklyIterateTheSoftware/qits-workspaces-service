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
 * {clean, dirty: [repository], unpushed: [{repository, branch|null, commits}]}}.
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
  void aBodyThatIsNoCheckIsNone() {
    assertNull(DaemonAgentClient.cleanupCheckOf(null));
    assertNull(DaemonAgentClient.cleanupCheckOf(new JsonObject("{\"error\":\"x\"}")));
  }
}
