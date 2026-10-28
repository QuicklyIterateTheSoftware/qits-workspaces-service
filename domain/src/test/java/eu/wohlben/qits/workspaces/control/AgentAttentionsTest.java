package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import eu.wohlben.qits.workspaces.dto.AgentDto;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.events.AgentAttentionChanged;
import eu.wohlben.qits.workspaces.persistence.AgentWaitRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentAttentions} (epic qits-1681) on a real database: what is stored, that a change is
 * announced once and a clear with {@code code} absent, that the scheduler pass evaluates every
 * ACTIVE agent and clears a leaver quietly, the block's time, the {@code @desk} comment, a declared
 * wait ending idleness, and the listing's {@code projectId}, {@code waits} and {@code attention}.
 * Agents are rows with no slot, so no daemon is involved.
 */
@QuarkusTest
public class AgentAttentionsTest {

  @Inject AgentAttentions attentions;
  @Inject AgentService service;
  @Inject AgentWaits waits;
  @Inject AgentWorktreeRepository agents;
  @Inject AgentWaitRepository waitRows;
  @Inject RecordingLifecycleAnnouncer announced;
  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceRepository workspaces;

  private UUID agent(String repositoryId, Consumer<AgentWorktree> shape) {
    UUID id = UUID.randomUUID();
    // RUNNING needs a slot: a TARGET one that no lease holds, so no daemon is ever asked.
    Long slot =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace ws = new Workspace();
                  ws.repositoryId = repositoryId;
                  ws.workspaceId = "ws-attention-" + id.toString().substring(0, 8);
                  ws.status = WorkspaceStatus.ACTIVE;
                  ws.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                  ws.placement = WorkspacePlacement.TARGET;
                  workspaces.persist(ws);
                  return ws.id;
                });
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.workspaceId = slot;
              row.id = id;
              row.repositoryId = repositoryId;
              row.workId = "attention-" + id;
              row.wrapperBranch = "ticket/" + row.workId;
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              row.runState = AgentRunState.RUNNING;
              row.entityStatus = "IMPLEMENTING";
              row.entityBlocked = false;
              row.commissionedTokenSubject = "tok-workspace-agent-" + id;
              shape.accept(row);
              agents.persist(row);
            });
    return id;
  }

  private UUID idleAgent(long minutes) {
    return agent(
        "attention-wrapper",
        a -> a.waitingSince = Instant.now().minus(Duration.ofMinutes(minutes)).minusSeconds(5));
  }

  private AgentWorktree row(UUID id) {
    return QuarkusTransaction.requiringNew().call(() -> agents.findById(id));
  }

  private void update(UUID id, Consumer<AgentWorktree> change) {
    QuarkusTransaction.requiringNew().run(() -> change.accept(agents.findById(id)));
  }

  private List<AgentAttentionChanged> events(UUID id) {
    return announced.of(AgentAttentionChanged.class, e -> e.agentId().equals(id.toString()));
  }

  @Test
  void aChangeIsAnnouncedOnceAndAClearWithNoCode() throws Exception {
    repositories.register("attention-wrapper");
    UUID id = idleAgent(42);
    attentions.reevaluate(id);
    attentions.reevaluate(id);

    AgentWorktree stored = row(id);
    assertEquals("IDLE_NO_WAIT", stored.attentionCode);
    assertEquals("Idle 42 min with no wait and no block", stored.attentionReason);
    assertEquals(stored.waitingSince.truncatedTo(ChronoUnit.MICROS), stored.attentionSince);
    assertNull(stored.attentionWaitId);
    List<AgentAttentionChanged> raised = announced.await(AgentAttentionChanged.class, e -> e.agentId().equals(id.toString()), 1);
    assertEquals(1, raised.size(), raised.toString());
    AgentAttentionChanged first = raised.get(0);
    assertEquals("IDLE_NO_WAIT", first.code());
    assertEquals(stored.workId, first.workId());
    assertEquals("attention-wrapper", first.repositoryId());
    assertEquals(FakeRepositoryLookup.PROJECT_ID, first.projectId());
    assertEquals(stored.attentionSince.toString(), first.since());

    // Only the minutes moved: written silently, announced nothing.
    update(id, a -> a.waitingSince = a.waitingSince.minus(Duration.ofMinutes(8)));
    attentions.reevaluate(id);
    assertEquals("Idle 50 min with no wait and no block", row(id).attentionReason);
    assertEquals(1, events(id).size());

    // It works again: cleared, and announced with no code.
    update(id, a -> a.waitingSince = null);
    attentions.reevaluate(id);
    AgentWorktree cleared = row(id);
    assertNull(cleared.attentionCode);
    assertNull(cleared.attentionReason);
    assertNull(cleared.attentionSince);
    List<AgentAttentionChanged> all = events(id);
    assertEquals(2, all.size(), all.toString());
    assertNull(all.get(1).code());
    assertNull(all.get(1).reason());
    assertNull(all.get(1).since());
  }

  @Test
  void thePassEvaluatesEveryActiveAgentAndALeaverClearsQuietly() throws Exception {
    UUID id = idleAgent(35);
    service.reconcile();
    assertEquals("IDLE_NO_WAIT", row(id).attentionCode);
    assertEquals(1, announced.await(AgentAttentionChanged.class, e -> e.agentId().equals(id.toString()), 1).size());

    // A second pass with nothing changed writes and says nothing.
    attentions.reevaluateAll();
    assertEquals(1, events(id).size());

    update(id, a -> {
      a.state = AgentState.OBSOLETE;
      a.obsoleteAt = Instant.now();
    });
    attentions.reevaluateAll();
    assertNull(row(id).attentionCode);
    assertEquals(1, events(id).size(), "leaving ACTIVE clears without an event");
  }

  @Test
  void markBlockedTimesAnExplicitBlockAndClearsItWithTheBlock() {
    UUID id = agent("attention-wrapper", a -> {});
    String workId = row(id).workId;

    service.markBlocked(workId, true, "EXPLICIT", "anna");
    Instant began = row(id).blockedAt;
    assertNotNull(began);

    service.markBlocked(workId, true, "BOTH", "anna");
    assertEquals(began, row(id).blockedAt, "still explicit: the block's start stays");

    service.markBlocked(workId, true, "AGENT_WAITING", null);
    assertNull(row(id).blockedAt, "only the derived block is left");

    service.markEntity(workId, new EntityFacts("t", "IMPLEMENTING", true), "EXPLICIT", "anna");
    assertNotNull(row(id).blockedAt);

    service.markBlocked(workId, false, null, null);
    assertNull(row(id).blockedAt);
  }

  @Test
  void aBlockWithNoDeskCommentNeedsTheDeskUntilOneArrives() throws Exception {
    UUID id = agent("attention-wrapper", a -> {});
    String workId = row(id).workId;
    service.markBlocked(workId, true, "EXPLICIT", "anna");
    // Fifty minutes ago, as far as the row knows.
    update(id, a -> a.blockedAt = Instant.now().minus(Duration.ofMinutes(50)).minusSeconds(5));
    attentions.reevaluate(id);
    assertEquals("BLOCKED_NO_DESK", row(id).attentionCode);
    assertEquals("Blocked 50 min by anna, no @desk comment since", row(id).attentionReason);

    // A comment from before the block does not answer it.
    Instant before = row(id).blockedAt.minusSeconds(600);
    assertEquals(id, attentions.onDeskMention(workId, before));
    assertEquals(before.truncatedTo(ChronoUnit.MICROS), row(id).deskAskedAt);
    assertEquals("BLOCKED_NO_DESK", row(id).attentionCode);

    Instant asked = Instant.now().truncatedTo(ChronoUnit.MICROS);
    attentions.onDeskMention(workId, asked);
    assertEquals(asked, row(id).deskAskedAt);
    assertNull(row(id).attentionCode);
    List<AgentAttentionChanged> all =
        announced.await(AgentAttentionChanged.class, e -> e.agentId().equals(id.toString()), 2);
    assertEquals("BLOCKED_NO_DESK", all.get(0).code());
    assertNull(all.get(all.size() - 1).code());

    // An older comment never moves the recorded one back; an item with no ACTIVE agent records nothing.
    attentions.onDeskMention(workId, before);
    assertEquals(asked, row(id).deskAskedAt);
    assertNull(attentions.onDeskMention("no-such-item-" + UUID.randomUUID(), asked));
  }

  @Test
  void declaringAWaitEndsIdlenessAndAPassedSubjectNamesTheWait() throws Exception {
    UUID id = idleAgent(45);
    attentions.reevaluate(id);
    assertEquals("IDLE_NO_WAIT", row(id).attentionCode);

    var wait =
        waits.create(
            id,
            "rr 7 to end",
            EventSelection.of(
                "ReleaseRequestStateChanged",
                Condition.exact("releaseRequestQualifiedId", "x-rr-7"),
                Condition.exact("state", "FINALIZED")),
            null);
    assertNull(row(id).attentionCode, "a declared wait is not idleness");

    // The request ended another way: the wait will never match.
    UUID waitId = UUID.fromString(wait.id());
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWait w = waitRows.findById(waitId);
              w.subjectState = "WITHDRAWN";
              w.subjectStateAt = Instant.parse("2026-10-28T10:42:00Z");
            });
    attentions.reevaluate(id);
    AgentWorktree passed = row(id);
    assertEquals("WAIT_SUBJECT_PASSED", passed.attentionCode);
    assertEquals(waitId, passed.attentionWaitId);
    assertEquals("Waits on x-rr-7 to end; it is WITHDRAWN since 10:42", passed.attentionReason);
    List<AgentAttentionChanged> all =
        announced.await(AgentAttentionChanged.class, e -> e.agentId().equals(id.toString()), 3);
    assertEquals(List.of("IDLE_NO_WAIT", "-", "WAIT_SUBJECT_PASSED"),
        all.stream().map(e -> e.code() == null ? "-" : e.code()).toList());
    assertEquals(wait.id(), all.get(2).waitId());

    // Cancelling it clears the code and is announced.
    waits.cancel(id, waitId);
    assertTrue(row(id).attentionCode == null || "IDLE_NO_WAIT".equals(row(id).attentionCode));
  }

  @Test
  void theListingFiltersByProjectAndCarriesWaitsAndAttention() {
    String mine = "attention-repo-" + UUID.randomUUID();
    String other = "attention-repo-" + UUID.randomUUID();
    String project = "attention-project-" + UUID.randomUUID();
    repositories.registerWrapper(mine, project, "mine");
    repositories.registerWrapper(other, "attention-other-" + UUID.randomUUID(), "other");
    UUID idle =
        agent(mine, a -> a.waitingSince = Instant.now().minus(Duration.ofMinutes(40)));
    UUID waiting = agent(mine, a -> {});
    UUID elsewhere = agent(other, a -> {});
    waits.create(
        waiting,
        "run to end",
        EventSelection.of("BuildFailed", Condition.exact("runId", "r-" + UUID.randomUUID())),
        null);
    waits.create(
        waiting,
        "item verified",
        EventSelection.of(
            "EntityTransitioned",
            Condition.exact("entities[].entityId", "e-" + UUID.randomUUID()),
            Condition.exact("entities[].status", "VERIFIED")),
        null);
    attentions.reevaluate(idle);

    List<AgentDto> listed = service.list(null, null, AgentState.ACTIVE, project);
    assertEquals(
        java.util.Set.of(idle.toString(), waiting.toString()),
        listed.stream().map(AgentDto::agentId).collect(java.util.stream.Collectors.toSet()));
    assertTrue(listed.stream().noneMatch(a -> a.agentId().equals(elsewhere.toString())));

    AgentDto idleDto = listed.stream().filter(a -> a.agentId().equals(idle.toString())).findFirst().orElseThrow();
    assertEquals("IDLE_NO_WAIT", idleDto.attention().code());
    assertEquals(List.of(), idleDto.waits());
    AgentDto waitingDto =
        listed.stream().filter(a -> a.agentId().equals(waiting.toString())).findFirst().orElseThrow();
    assertNull(waitingDto.attention());
    assertEquals(List.of("run to end", "item verified"), waitingDto.waits().stream().map(w -> w.label()).toList());
    assertEquals("CI_RUN", waitingDto.waits().get(0).subjectKind());
    assertEquals(List.of("VERIFIED"), waitingDto.waits().get(1).targetStates());
    assertTrue(waitingDto.waits().get(0).selection().isObject());
    assertEquals("run to end; item verified", waitingDto.waitingOn());

    assertTrue(service.list(null, null, AgentState.ACTIVE, "no-such-project").isEmpty());
  }
}
