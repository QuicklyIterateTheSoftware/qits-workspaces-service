package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.AgentAttention.Attention;
import eu.wohlben.qits.workspaces.control.AgentAttention.Code;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link AgentAttention} (epic qits-1681): a table per code, the exclusions, the priority order, the
 * {@code @desk} comment before and after the block, and the reason's formats. Pure: no container.
 */
class AgentAttentionTest {

  private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

  private static final AgentAttention.Config CONFIG =
      new AgentAttention.Config(Duration.ofMinutes(30), Duration.ofMinutes(30), Duration.ofHours(3));

  private static AgentWorktree agent() {
    AgentWorktree a = new AgentWorktree();
    a.id = UUID.randomUUID();
    a.state = AgentState.ACTIVE;
    a.runState = AgentRunState.RUNNING;
    a.entityStatus = "IMPLEMENTING";
    a.entityBlocked = false;
    return a;
  }

  /** An agent RUNNING and waiting for input since {@code minutes} ago, nothing declared. */
  private static AgentWorktree idle(long minutes) {
    AgentWorktree a = agent();
    a.waitingSince = NOW.minus(Duration.ofMinutes(minutes));
    return a;
  }

  private static AgentWait wait(String kind, String ref, String targets, String state, Instant stateAt) {
    AgentWait w = new AgentWait();
    w.id = UUID.randomUUID();
    w.agentId = UUID.randomUUID();
    w.label = "a wait";
    w.state = AgentWaitState.OPEN;
    w.createdAt = NOW.minus(Duration.ofHours(5));
    w.subjectKind = kind;
    w.subjectRef = ref;
    w.targetStates = targets;
    w.subjectState = state;
    w.subjectStateAt = stateAt;
    return w;
  }

  private static Optional<Attention> evaluate(AgentWorktree agent, AgentWait... waits) {
    return AgentAttention.evaluate(agent, List.of(waits), NOW, CONFIG);
  }

  private static final String ENDS = "[\"FINALIZED\",\"REJECTED\",\"FAILED\",\"WITHDRAWN\",\"OBSOLETE\"]";

  // --- 1. WAIT_SUBJECT_PASSED ---------------------------------------------------------------------

  @ParameterizedTest(name = "{0} {1} in {2} -> {3}")
  @CsvSource(
      delimiter = '|',
      value = {
        // kind | state | target states | passed
        "RELEASE_REQUEST | FINALIZED | []                         | true",
        "RELEASE_REQUEST | FINALIZED | [\"FINALIZED\"]            | true",
        "RELEASE_REQUEST | WITHDRAWN | [\"FINALIZED\"]            | true",
        "RELEASE_REQUEST | PENDING   | [\"FINALIZED\"]            | false",
        "RELEASE_REQUEST | PENDING   | [\"PENDING\"]              | true",
        "CI_RUN          | SUCCESS   | []                         | true",
        "CI_RUN          | RUNNING   | [\"SUCCESS\",\"FAILED\"]   | false",
        "CI_RUN          | CANCELLED | [\"SUCCESS\",\"FAILED\"]   | true",
        "WORK_ITEM       | VERIFIED  | [\"VERIFIED\"]             | true",
        "WORK_ITEM       | VERIFYING | [\"VERIFIED\"]             | false",
        "WORK_ITEM       | DROPPED   | [\"VERIFIED\"]             | true",
        "COMMENTS        | x         | []                         | false",
        "OTHER           | DONE      | []                         | false",
      })
  void aWaitWhoseSubjectPassedNeedsAttention(String kind, String state, String targets, boolean passed) {
    AgentWait w = wait(kind, "ref-1", targets, state, Instant.parse("2026-10-28T10:42:00Z"));
    Optional<Attention> attention = evaluate(agent(), w);
    if (!passed) {
      assertTrue(attention.isEmpty(), attention.toString());
      return;
    }
    assertEquals(Code.WAIT_SUBJECT_PASSED, attention.orElseThrow().code());
    assertEquals(w.id, attention.get().waitId());
    assertEquals(w.subjectStateAt, attention.get().since());
  }

  @Test
  void aPassedSubjectIsNamedByItsQualifiedIdAndTheTimeItBegan() {
    AgentWait w =
        wait("RELEASE_REQUEST", "rr-uuid", ENDS, "FINALIZED", Instant.parse("2026-10-28T10:42:31Z"));
    w.subjectQualifiedId = "qits-projects-service-rr-368";
    assertEquals(
        "Waits on qits-projects-service-rr-368 to end; it is FINALIZED since 10:42",
        evaluate(agent(), w).orElseThrow().reason());

    AgentWait item = wait("WORK_ITEM", "item-uuid", "[\"VERIFIED\"]", "VERIFIED", w.subjectStateAt);
    assertEquals(
        "Waits on item-uuid to be VERIFIED; it is VERIFIED since 10:42",
        evaluate(agent(), item).orElseThrow().reason());

    AgentWait run =
        wait("CI_RUN", "9677c59a-0000-4000-8000-000000000001", "[]", "FAILED", w.subjectStateAt);
    assertEquals(
        "Waits on run 9677c59a to end; it is FAILED since 10:42",
        evaluate(agent(), run).orElseThrow().reason());
  }

  // --- 2. BLOCKED_NO_DESK -------------------------------------------------------------------------

  private static AgentWorktree blocked(String source, long minutes) {
    AgentWorktree a = agent();
    a.entityBlocked = true;
    a.blockSource = source;
    a.blockedAt = NOW.minus(Duration.ofMinutes(minutes));
    a.blockAuthor = "anna";
    return a;
  }

  @ParameterizedTest(name = "{0} for {1} min -> {2}")
  @CsvSource({
    "EXPLICIT, 50, true",
    "BOTH, 50, true",
    "explicit, 30, true",
    "EXPLICIT, 29, false",
    "AGENT_WAITING, 50, false",
  })
  void anExplicitBlockStandingLongEnoughNeedsTheDesk(String source, long minutes, boolean needs) {
    Optional<Attention> attention = evaluate(blocked(source, minutes));
    if (!needs) {
      assertTrue(attention.isEmpty(), attention.toString());
      return;
    }
    assertEquals(Code.BLOCKED_NO_DESK, attention.orElseThrow().code());
    assertEquals(NOW.minus(Duration.ofMinutes(minutes)), attention.get().since());
    assertNull(attention.get().waitId());
  }

  @Test
  void aDeskCommentAfterTheBlockBeganEndsItAndOneBeforeDoesNot() {
    AgentWorktree before = blocked("EXPLICIT", 50);
    before.deskAskedAt = before.blockedAt.minusSeconds(60);
    assertEquals(Code.BLOCKED_NO_DESK, evaluate(before).orElseThrow().code());

    AgentWorktree after = blocked("EXPLICIT", 50);
    after.deskAskedAt = after.blockedAt.plusSeconds(60);
    assertTrue(evaluate(after).isEmpty());

    AgentWorktree atOnce = blocked("EXPLICIT", 50);
    atOnce.deskAskedAt = atOnce.blockedAt;
    assertTrue(evaluate(atOnce).isEmpty());
  }

  @Test
  void aBlockReasonNamesWhoBlockedWhenKnown() {
    assertEquals(
        "Blocked 50 min by anna, no @desk comment since",
        evaluate(blocked("EXPLICIT", 50)).orElseThrow().reason());
    AgentWorktree nobody = blocked("BOTH", 125);
    nobody.blockAuthor = null;
    assertEquals(
        "Blocked 2 h 5 min, no @desk comment since", evaluate(nobody).orElseThrow().reason());
  }

  @Test
  void anExplicitBlockWithNoRecordedStartIsNotTimed() {
    AgentWorktree a = blocked("EXPLICIT", 50);
    a.blockedAt = null;
    assertTrue(evaluate(a).isEmpty());
  }

  // --- 3. IDLE_NO_WAIT ----------------------------------------------------------------------------

  @Test
  void anAgentIdleWithNothingDeclaredNeedsAttention() {
    Attention attention = evaluate(idle(42)).orElseThrow();
    assertEquals(Code.IDLE_NO_WAIT, attention.code());
    assertEquals("Idle 42 min with no wait and no block", attention.reason());
    assertEquals(NOW.minus(Duration.ofMinutes(42)), attention.since());
    assertNull(attention.waitId());
    assertEquals(Code.IDLE_NO_WAIT, evaluate(idle(30)).orElseThrow().code());
    assertTrue(evaluate(idle(29)).isEmpty());
  }

  @ParameterizedTest(name = "between phases: {0}")
  @CsvSource({"REFINED", "READY_FOR_DEV", "IMPLEMENTED", "implemented"})
  void anAgentBetweenPhasesIsNotIdle(String status) {
    AgentWorktree a = idle(90);
    a.entityStatus = status;
    assertTrue(evaluate(a).isEmpty());
  }

  @Test
  void anIdleAgentIsExcusedByAPendingTurnAWaitABlockOrNotRunning() {
    AgentWorktree pending = idle(90);
    pending.pendingText = "You ended your turn with no open wait…";
    assertTrue(evaluate(pending).isEmpty(), "a turn still to be said");

    AgentWait open = wait("OTHER", null, "[]", null, null);
    assertTrue(evaluate(idle(90), open).isEmpty(), "an OPEN wait");

    AgentWorktree yielded = idle(90);
    yielded.runState = AgentRunState.YIELDED;
    assertTrue(evaluate(yielded).isEmpty(), "not RUNNING");

    AgentWorktree working = agent();
    assertTrue(evaluate(working).isEmpty(), "no waitingSince");

    AgentWorktree noSource = idle(90);
    noSource.entityBlocked = true;
    assertTrue(evaluate(noSource).isEmpty(), "a block with no source");

    AgentWorktree derived = idle(90);
    derived.entityBlocked = true;
    derived.blockSource = "AGENT_WAITING";
    assertEquals(
        Code.IDLE_NO_WAIT,
        evaluate(derived).orElseThrow().code(),
        "the derived block alone is the stall itself");
  }

  @Test
  void onlyAnActiveAgentNeedsAttention() {
    for (AgentState state : List.of(AgentState.OBSOLETE, AgentState.REMOVED)) {
      AgentWorktree a = idle(90);
      a.state = state;
      assertTrue(evaluate(a).isEmpty(), state.name());
    }
  }

  // --- 4. WAIT_OVERDUE ----------------------------------------------------------------------------

  @Test
  void aRunPastItsExpectedFinishIsOverdue() {
    AgentWait run =
        wait("CI_RUN", "9677c59a-1111-4000-8000-000000000001", "[\"SUCCESS\"]", "RUNNING",
            NOW.minus(Duration.ofMinutes(100)));
    run.subjectExpectedBy = NOW.minus(Duration.ofMinutes(65));
    Attention attention = evaluate(agent(), run).orElseThrow();
    assertEquals(Code.WAIT_OVERDUE, attention.code());
    assertEquals(run.id, attention.waitId());
    assertEquals(run.subjectExpectedBy, attention.since());
    assertEquals("Run 9677c59a RUNNING for 1 h 40 min, expected about 35 min", attention.reason());

    AgentWait queued =
        wait("CI_RUN", "abcdef01-1111-4000-8000-000000000001", "[]", "QUEUED",
            NOW.minus(Duration.ofMinutes(130)));
    queued.subjectExpectedBy = Instant.parse("2026-10-28T11:50:00Z");
    assertEquals(
        "Run abcdef01 QUEUED for 2 h 10 min, past its expected finish at 11:50",
        evaluate(agent(), queued).orElseThrow().reason());

    AgentWait notYet = wait("CI_RUN", "r", "[]", "RUNNING", NOW.minus(Duration.ofMinutes(5)));
    notYet.subjectExpectedBy = NOW.plusSeconds(60);
    assertTrue(evaluate(agent(), notYet).isEmpty());

    AgentWait unpredicted = wait("CI_RUN", "r", "[]", "RUNNING", NOW.minus(Duration.ofHours(9)));
    assertTrue(evaluate(agent(), unpredicted).isEmpty(), "no expected finish: never overdue");
  }

  @Test
  void aReleaseRequestUnchangedTooLongIsOverdue() {
    AgentWait rr =
        wait("RELEASE_REQUEST", "rr-uuid", ENDS, "PENDING", NOW.minus(Duration.ofMinutes(190)));
    rr.subjectQualifiedId = "qits-qits-rr-12";
    Attention attention = evaluate(agent(), rr).orElseThrow();
    assertEquals(Code.WAIT_OVERDUE, attention.code());
    assertEquals("qits-qits-rr-12 PENDING, unchanged for 3 h 10 min", attention.reason());
    assertEquals(rr.subjectStateAt.plus(Duration.ofHours(3)), attention.since());
    assertEquals(rr.id, attention.waitId());

    AgentWait recent =
        wait("RELEASE_REQUEST", "rr-uuid", ENDS, "PENDING", NOW.minus(Duration.ofMinutes(179)));
    assertTrue(evaluate(agent(), recent).isEmpty());
  }

  // --- priority -----------------------------------------------------------------------------------

  @Test
  void theFirstRuleThatHoldsWins() {
    AgentWait passed =
        wait("RELEASE_REQUEST", "rr-1", ENDS, "FINALIZED", NOW.minus(Duration.ofMinutes(20)));
    AgentWait overdue =
        wait("RELEASE_REQUEST", "rr-2", ENDS, "PENDING", NOW.minus(Duration.ofHours(4)));

    // A passed subject beats an explicit block, and an overdue wait.
    AgentWorktree blockedAgent = blocked("EXPLICIT", 50);
    assertEquals(Code.WAIT_SUBJECT_PASSED, evaluate(blockedAgent, overdue, passed).orElseThrow().code());
    // A block beats an overdue wait.
    assertEquals(Code.BLOCKED_NO_DESK, evaluate(blockedAgent, overdue).orElseThrow().code());
    // A block beats idleness (and excuses it on its own once the desk was asked).
    AgentWorktree idleAndBlocked = blocked("EXPLICIT", 50);
    idleAndBlocked.waitingSince = NOW.minus(Duration.ofHours(2));
    assertEquals(Code.BLOCKED_NO_DESK, evaluate(idleAndBlocked).orElseThrow().code());
    idleAndBlocked.deskAskedAt = NOW;
    assertTrue(evaluate(idleAndBlocked).isEmpty());
    // With two waits of one rule, the older one is named.
    AgentWait older =
        wait("CI_RUN", "11111111-a", "[]", "SUCCESS", NOW.minus(Duration.ofMinutes(10)));
    older.createdAt = NOW.minus(Duration.ofHours(9));
    assertEquals(older.id, evaluate(agent(), passed, older).orElseThrow().waitId());
  }

  @Test
  void aClosedWaitIsNoneOfItsBusiness() {
    AgentWait matched =
        wait("RELEASE_REQUEST", "rr-1", ENDS, "FINALIZED", NOW.minus(Duration.ofMinutes(20)));
    matched.state = AgentWaitState.MATCHED;
    assertTrue(evaluate(agent(), matched).isEmpty());
  }

  // --- formats ------------------------------------------------------------------------------------

  @ParameterizedTest(name = "{0} s -> {1}")
  @CsvSource({"0, 0 min", "59, 0 min", "2520, 42 min", "3599, 59 min", "3600, 1 h 0 min", "6000, 1 h 40 min", "11400, 3 h 10 min"})
  void durationsAreMinutesThenHours(long seconds, String written) {
    assertEquals(written, AgentAttention.duration(Duration.ofSeconds(seconds)));
  }
}
