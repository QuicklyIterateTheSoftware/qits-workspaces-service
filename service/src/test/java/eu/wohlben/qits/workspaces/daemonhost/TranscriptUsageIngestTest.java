package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.events.AgentUsageRecorded;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.transcripthost.ShippedClaudeTranscripts;
import eu.wohlben.qits.workspaces.transcripthost.TranscriptUsageBackfill;
import eu.wohlben.qits.workspacedaemon.protocol.TranscriptChunk;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-2675's usage, from the ingest to the bus: a stored chunk's newly appended assistant lines are
 * announced as {@link AgentUsageRecorded} once per session and model, the same chunk again
 * publishes nothing, and the boot backfill counts a stored session once however often it runs.
 * {@link TranscriptIngest} is called directly — the socket's part is {@link
 * WorkspaceDaemonRegistryTranscriptTest}'s — and the bus is recorded, as {@link
 * WorkspaceDaemonRegistryHarnessExitTest} records it.
 */
@QuarkusTest
class TranscriptUsageIngestTest {

  @Inject TranscriptIngest ingest;

  @Inject TranscriptUsageBackfill backfill;

  @Inject ShippedClaudeTranscripts transcripts;

  @Inject AgentWorktreeRepository agents;

  private final List<QitsEvent> published = new CopyOnWriteArrayList<>();

  @BeforeEach
  void recordTheBus() {
    QuarkusMock.installMockForType(
        new QitsEventBus() {
          @Override
          public void publish(QitsEvent event, UUID parentEventId) {
            published.add(event);
          }
        },
        QitsEventBus.class);
  }

  @Test
  void theSameChunkTwicePublishesOnce() throws Exception {
    long row = 9_100L + (System.nanoTime() & 0xFF);
    UUID agent = agent(row, Instant.now());
    String session = UUID.randomUUID().toString();
    String id = "msg_" + UUID.randomUUID();
    // Two content blocks of one message, and one of another model's.
    String chunk =
        assistant(id, "claude-opus-5-5", 10, 20, 30, 40, "2026-11-25T10:00:00Z")
            + assistant(id, "claude-opus-5-5", 10, 20, 30, 40, "2026-11-25T10:00:01Z")
            + "{\"type\":\"user\",\"message\":{\"content\":\"go\"}}\n"
            + assistant("msg_" + UUID.randomUUID(), "claude-haiku-5", 1, 2, 0, 0, "2026-11-25T10:00:02Z");

    ingest.accept(row, new TranscriptChunk(agent.toString(), "cmd", session, "main", 0, chunk));
    // The same chunk again: the store resyncs it and nothing is counted.
    ingest.accept(row, new TranscriptChunk(agent.toString(), "cmd", session, "main", 0, chunk));

    List<AgentUsageRecorded> events = await(agent, 2);
    TimeUnit.MILLISECONDS.sleep(300);
    events = of(agent);
    assertEquals(2, events.size(), events.toString());
    AgentUsageRecorded opus = byModel(events, "claude-opus-5-5");
    assertEquals(1, opus.messages());
    assertEquals(10, opus.inputTokens());
    assertEquals(20, opus.outputTokens());
    assertEquals(30, opus.cacheCreationTokens());
    assertEquals(40, opus.cacheReadTokens());
    assertEquals(session, opus.sessionId());
    assertEquals("w-" + agent, opus.workId());
    assertEquals(Long.toString(row), opus.workspaceId());
    // The message's first line wins, its time included.
    assertEquals(Instant.parse("2026-11-25T10:00:00Z"), opus.occurredAt());
    assertEquals(1, byModel(events, "claude-haiku-5").messages());

    // A message's line split across two chunks counts once, in the chunk that ends it; a
    // sidechain counts like the main file.
    String split = assistant("msg_" + UUID.randomUUID(), "claude-opus-5-5", 5, 6, 0, 0, "2026-11-25T10:01:00Z");
    long held = bytes(chunk);
    ingest.accept(row, new TranscriptChunk(agent.toString(), "cmd", session, "main", held, split.substring(0, 40)));
    ingest.accept(
        row,
        new TranscriptChunk(
            agent.toString(), "cmd", session, "main", held + bytes(split.substring(0, 40)), split.substring(40)));
    ingest.accept(
        row,
        new TranscriptChunk(
            agent.toString(), "cmd", session, "subagents/agent-a1.jsonl", 0,
            assistant("msg_" + UUID.randomUUID(), "claude-haiku-5", 7, 8, 0, 0, "2026-11-25T10:02:00Z")));

    events = await(agent, 4);
    assertEquals(4, events.size(), events.toString());
    assertEquals(5, events.get(2).inputTokens());
    assertEquals(7, events.get(3).inputTokens());
  }

  /**
   * The backfill counts a session the ingest never counted — stored, here, straight through the
   * store — and a second run publishes nothing; an agent created before its instant is not read.
   */
  @Test
  void theBackfillCountsAStoredSessionOnce() throws Exception {
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);
    UUID agent = agent(9_300L, Instant.now());
    UUID older = agent(9_301L, since.minus(1, ChronoUnit.DAYS));
    String session = UUID.randomUUID().toString();
    String line = assistant("msg_" + UUID.randomUUID(), "claude-opus-5-5", 1, 2, 3, 4, "2026-11-25T09:00:00Z");
    String sideLine = assistant("msg_" + UUID.randomUUID(), "claude-opus-5-5", 10, 20, 30, 40, "2026-11-25T09:30:00Z");
    for (UUID each : List.of(agent, older)) {
      transcripts.store().append(each, session, "main", 0, line + line);
      transcripts.store().append(each, session, "subagents/agent-b2.jsonl", 0, sideLine);
    }

    backfill.run(since);

    List<AgentUsageRecorded> events = await(agent, 1);
    assertEquals(1, events.size(), events.toString());
    assertEquals(2, events.get(0).messages());
    assertEquals(11, events.get(0).inputTokens());
    assertEquals(44, events.get(0).cacheReadTokens());
    assertEquals(Instant.parse("2026-11-25T09:30:00Z"), events.get(0).occurredAt());

    backfill.run(since);
    TimeUnit.MILLISECONDS.sleep(300);
    assertEquals(1, of(agent).size(), of(agent).toString());
    assertTrue(of(older).isEmpty(), "an agent created before the backfill's instant was read");
  }

  // --- fixtures ----------------------------------------------------------------------------------

  private static String assistant(
      String id, String model, long in, long out, long creation, long read, String at) {
    return "{\"type\":\"assistant\",\"timestamp\":\"" + at + "\",\"message\":{\"id\":\"" + id
        + "\",\"model\":\"" + model + "\",\"usage\":{\"input_tokens\":" + in
        + ",\"output_tokens\":" + out + ",\"cache_creation_input_tokens\":" + creation
        + ",\"cache_read_input_tokens\":" + read + "}}}\n";
  }

  private static long bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  private UUID agent(Long workspaceId, Instant createdAt) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = "repo-usage";
              row.workId = "w-" + id;
              row.wrapperBranch = "ticket/w-" + id;
              row.workspaceId = null;
              row.startedWorkspaceId = workspaceId;
              row.runState = AgentRunState.STOPPED;
              row.createdAt = createdAt;
              row.gitRefs = "[]";
              agents.persist(row);
            });
    return id;
  }

  private List<AgentUsageRecorded> of(UUID agent) {
    return published.stream()
        .filter(AgentUsageRecorded.class::isInstance)
        .map(AgentUsageRecorded.class::cast)
        .filter(e -> e.agentId().equals(agent.toString()))
        .toList();
  }

  private List<AgentUsageRecorded> await(UUID agent, int count) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000;
    while (of(agent).size() < count && System.currentTimeMillis() < deadline) {
      TimeUnit.MILLISECONDS.sleep(25);
    }
    return of(agent);
  }

  private static AgentUsageRecorded byModel(List<AgentUsageRecorded> events, String model) {
    return events.stream()
        .filter(e -> model.equals(e.model()))
        .min(Comparator.comparing(AgentUsageRecorded::occurredAt))
        .orElseThrow(() -> new AssertionError("no event for " + model + ": " + events));
  }
}
