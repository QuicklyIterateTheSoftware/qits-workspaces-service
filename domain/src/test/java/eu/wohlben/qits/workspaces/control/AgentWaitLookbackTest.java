package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import eu.wohlben.qits.workspaces.dto.AgentWaitDto;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.persistence.AgentWaitRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The look-back (qits-1668): a wait declared after its event happened fires at once; an event older
 * than {@code qits.workspaces.waits.lookback} (15 minutes) does not fire it; a log that cannot be
 * read leaves the wait OPEN.
 */
@QuarkusTest
public class AgentWaitLookbackTest {

  private static final String EVENT = "LookbackTestBuildStatusChanged";

  @Inject AgentWaits waits;
  @Inject AgentWorktreeRepository agents;
  @Inject AgentWaitRepository waitRows;
  @Inject FakeRecentEvents log;

  @AfterEach
  void tearDown() {
    log.reset();
  }

  private static EventSelection failed(String runId) {
    return EventSelection.of(EVENT, Condition.exact("runId", runId), Condition.exact("status", "FAILED"));
  }

  private static String payload(String runId, String status) {
    return "{\"runId\":\"" + runId + "\",\"status\":\"" + status + "\"}";
  }

  private UUID agent() {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = "lookback-wrapper";
              row.workId = "w-lookback-" + id.toString().substring(0, 8);
              row.wrapperBranch = "ticket/" + row.workId;
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              row.commissionedTokenSubject = "tok-workspace-agent-" + id;
              agents.persist(row);
            });
    return id;
  }

  private AgentWait waitRow(String id) {
    return QuarkusTransaction.requiringNew().call(() -> waitRows.findById(UUID.fromString(id)));
  }

  @Test
  void aWaitDeclaredAfterItsEventFiresAtOnce() {
    UUID id = agent();
    Instant failedAt = Instant.now().minusSeconds(31);
    log.add("ev-other", EVENT, failedAt.minusSeconds(5), payload("run-0", "FAILED"));
    log.add("ev-failed", EVENT, failedAt, payload("run-1", "FAILED"));

    AgentWaitDto wait = waits.create(id, "run-1 failed", failed("run-1"), Duration.ofHours(1));

    assertEquals(AgentWaitState.MATCHED, wait.state());
    assertEquals("ev-failed", waitRow(wait.id()).matchedEventId);
    String turn = QuarkusTransaction.requiringNew().call(() -> agents.findById(id).pendingText);
    assertTrue(turn.contains("\"run-1 failed\" matched: " + EVENT), turn);
    Duration askedBefore = Duration.between(log.asked().get(0), wait.createdAt());
    assertTrue(Duration.ofMinutes(15).minusMillis(1).compareTo(askedBefore) <= 0, askedBefore::toString);
    assertTrue(askedBefore.compareTo(Duration.ofMinutes(15).plusMillis(1)) <= 0, askedBefore::toString);
  }

  @Test
  void anEventOlderThanTheLookbackDoesNotFireTheWait() {
    UUID id = agent();
    log.add("ev-old", EVENT, Instant.now().minus(Duration.ofMinutes(16)), payload("run-2", "FAILED"));

    AgentWaitDto wait = waits.create(id, null, failed("run-2"), Duration.ofHours(1));

    assertEquals(AgentWaitState.OPEN, wait.state());
    assertNull(QuarkusTransaction.requiringNew().call(() -> agents.findById(id).pendingText));
    // A later event still matches it, as before.
    assertEquals(1, waits.onEvent("ev-new", EVENT, Instant.now(), payload("run-2", "FAILED")));
    assertEquals(AgentWaitState.MATCHED, waitRow(wait.id()).state);
  }

  @Test
  void aDeskWaitDeclaredAfterItsEventFiresAtOnce() {
    UUID project = UUID.randomUUID();
    log.add("ev-desk", EVENT, Instant.now().minusSeconds(10), payload("run-3", "FAILED"));

    AgentWaitDto wait = waits.createForDesk(project, "run-3 failed", failed("run-3"), null);

    assertEquals(AgentWaitState.MATCHED, wait.state());
    assertEquals("ev-desk", waitRow(wait.id()).matchedEventId);
  }

  @Test
  void aLogThatCannotBeReadLeavesTheWaitOpen() {
    UUID id = agent();
    log.failWith(new IllegalStateException("qits-events answered 503"));

    AgentWaitDto wait = waits.create(id, null, failed("run-4"), null);

    assertEquals(AgentWaitState.OPEN, wait.state());
  }
}
