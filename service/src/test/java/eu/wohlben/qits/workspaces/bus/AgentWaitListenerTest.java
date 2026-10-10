package eu.wohlben.qits.workspaces.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.workspaces.control.AgentWaits;
import eu.wohlben.qits.workspaces.dto.AgentWaitDto;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentWaitListener} (qits-1153) called the way the library's durable funnel calls it: the
 * bus is dark in {@code %test}, so frames are handed in directly.
 */
@QuarkusTest
class AgentWaitListenerTest {

  @Inject AgentWaitListener listener;
  @Inject AgentWaits waits;
  @Inject AgentWorktreeRepository agents;

  private static EventFrame frame(String id, String name, String payload) {
    return new EventFrame(id, name, Instant.now(), payload, null, null, "dev");
  }

  @Test
  void itSubscribesToEverythingAndSelectsOnlyWhatAnOpenWaitNames() {
    assertEquals(Set.of("*"), listener.signatures());
    assertEquals("qits-workspaces.agent-waits", listener.consumerId());

    UUID agentId = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = agentId;
              row.repositoryId = "listener-wrapper";
              row.workId = "w-listener-" + agentId;
              row.wrapperBranch = "ticket/w-listener";
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              agents.persist(row);
            });
    AgentWaitDto wait =
        waits.create(
            agentId,
            "ci run 7",
            EventSelection.of("BuildStatusChanged", EventSelection.Condition.exact("runId", "7")),
            null);

    assertFalse(listener.selects(frame("e-0", "SomethingElse", "{}")));
    assertTrue(listener.selects(frame("e-1", "BuildStatusChanged", "{\"runId\":\"6\"}")));

    listener.onFrame(frame("e-1", "BuildStatusChanged", "{\"runId\":\"6\"}"));
    assertEquals(AgentWaitState.OPEN, waits.get(agentId, UUID.fromString(wait.id())).state());

    listener.onFrame(frame("e-2", "BuildStatusChanged", "{\"runId\":\"7\",\"status\":\"SUCCESS\"}"));
    AgentWaitDto matched = waits.get(agentId, UUID.fromString(wait.id()));
    assertEquals(AgentWaitState.MATCHED, matched.state());
    assertEquals("e-2", matched.matchedEventId());
  }
}
