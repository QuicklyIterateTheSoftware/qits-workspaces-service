package eu.wohlben.qits.workspaces.bus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.eventstream.control.EventFrame;
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
 * {@link DeskMentionListener} (epic qits-1681) called the way the library's durable funnel calls it:
 * the bus is dark in {@code %test}, so frames are handed in directly, with qits-projects' whole
 * {@code EntityCommented} payload.
 */
@QuarkusTest
class DeskMentionListenerTest {

  @Inject DeskMentionListener listener;
  @Inject AgentWorktreeRepository agents;

  private static EventFrame frame(String payload) {
    return new EventFrame(UUID.randomUUID().toString(), "EntityCommented", Instant.now(), payload, null, null, "dev");
  }

  /** The payload as qits-projects publishes it: every field, of which three are read. */
  private static String comment(String entityId, String mentions, String commentedAt) {
    return "{\"commentId\":\"" + UUID.randomUUID() + "\",\"entityId\":\"" + entityId + "\","
        + "\"projectId\":\"p-1\",\"archetype\":\"TICKET\",\"number\":12,\"title\":\"A ticket\","
        + "\"author\":\"anna\",\"authorAgentOf\":null,\"changedBy\":\"anna\","
        + "\"mentions\":" + mentions + ",\"commentedAt\":\"" + commentedAt + "\"}";
  }

  private UUID agent(String workId) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = "desk-mention-wrapper";
              row.workId = workId;
              row.wrapperBranch = "ticket/" + workId;
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              agents.persist(row);
            });
    return id;
  }

  private Instant askedAt(UUID id) {
    return QuarkusTransaction.requiringNew().call(() -> agents.findById(id).deskAskedAt);
  }

  @Test
  void itListensForCommentsUnderItsOwnConsumer() {
    assertEquals(Set.of("EntityCommented"), listener.signatures());
    assertEquals("qits-workspaces.desk-mentions", listener.consumerId());
  }

  @Test
  void aDeskMentionRecordsWhenTheItemsAgentAskedTheDesk() {
    String workId = "w-desk-" + UUID.randomUUID();
    UUID id = agent(workId);

    listener.onFrame(frame(comment(workId, "[\"anna\"]", "2026-10-28T10:00:00Z")));
    assertNull(askedAt(id), "no @desk in it");

    listener.onFrame(frame(comment("another-" + workId, "[\"desk\"]", "2026-10-28T10:05:00Z")));
    assertNull(askedAt(id), "another item's comment");

    listener.onFrame(frame(comment(workId, "[\"anna\",\"desk\"]", "2026-10-28T10:10:00Z")));
    assertEquals(Instant.parse("2026-10-28T10:10:00Z"), askedAt(id));
  }

  @Test
  void aPayloadThatDoesNotBindIsSettled() {
    assertDoesNotThrow(() -> listener.onFrame(frame("not json")));
    assertDoesNotThrow(() -> listener.onFrame(frame("{\"entityId\":\"x\",\"mentions\":\"desk\"}")));
    assertDoesNotThrow(() -> listener.onFrame(frame("{\"entityId\":\"x\"}")));
  }
}
