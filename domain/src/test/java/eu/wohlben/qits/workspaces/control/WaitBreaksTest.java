package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import eu.wohlben.qits.workspaces.control.WaitBreaks.Broken;
import eu.wohlben.qits.workspaces.control.WaitBreaks.Seen;
import eu.wohlben.qits.workspaces.control.WaitBreaks.Why;
import eu.wohlben.qits.workspaces.control.WaitSubjectResolver.Branch;
import eu.wohlben.qits.workspaces.control.WaitSubjectResolver.Delivery;
import eu.wohlben.qits.workspaces.control.WaitSubjects.Kind;
import eu.wohlben.qits.workspaces.control.WaitSubjects.Subject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link WaitBreaks} (qits-1961), pure: each kind's final states, the stuck states before and after
 * {@link WaitBreaks#STUCK_AFTER}, a 404, a branch the ledger does not follow, and a branch read for
 * one repository, everywhere and with no repository named.
 */
class WaitBreaksTest {

  private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

  private static Optional<Broken> seen(Kind kind, String state, Instant since, String... targets) {
    return WaitBreaks.seen(kind, List.of(targets), new Seen("it", state, since, null, null), NOW);
  }

  // --- final --------------------------------------------------------------------------------------

  @ParameterizedTest(name = "{0} {1} waiting for {2} -> {3}")
  @CsvSource(
      delimiter = '|',
      value = {
        // kind | state | the target | broken
        "RELEASE_REQUEST | FINALIZED | RELEASED  | true",
        "RELEASE_REQUEST | WITHDRAWN | FINALIZED | true",
        "RELEASE_REQUEST | OBSOLETE  | FINALIZED | true",
        "RELEASE_REQUEST | FINALIZED | FINALIZED | false",
        "RELEASE_REQUEST | PENDING   | FINALIZED | false",
        "RELEASE_REQUEST | RELEASED  | FINALIZED | false",
        "CI_RUN          | SUCCESS   | FAILED    | true",
        "CI_RUN          | CANCELLED | SUCCESS   | true",
        "CI_RUN          | RUNNING   | SUCCESS   | false",
        "CI_RUN          | QUEUED    | SUCCESS   | false",
        "WORK_ITEM       | DONE      | VERIFIED  | true",
        "WORK_ITEM       | DROPPED   | VERIFIED  | true",
        "WORK_ITEM       | VERIFYING | VERIFIED  | false",
        "BRANCH          | LIVE      | FAILED    | true",
        "BRANCH          | WITHDRAWN | LIVE      | true",
        "BRANCH          | RELEASED  | LIVE      | false",
        "COMMENTS        | DONE      | x         | false",
        "OTHER           | DONE      | x         | false",
      })
  void aFinalStateThatIsNoTargetBreaksTheWait(String kind, String state, String target, boolean broken) {
    // A minute old: only the final rule can hold.
    Optional<Broken> b = seen(Kind.valueOf(kind), state, NOW.minusSeconds(60), target);
    assertEquals(broken, b.isPresent(), b.toString());
    b.ifPresent(x -> assertEquals(Why.FINAL, x.why()));
  }

  @Test
  void aWaitWithNoTargetBreaksOnAnyFinalState() {
    assertEquals(Why.FINAL, seen(Kind.CI_RUN, "SUCCESS", NOW).orElseThrow().why());
    assertTrue(seen(Kind.CI_RUN, "RUNNING", NOW).isEmpty());
  }

  // --- stuck --------------------------------------------------------------------------------------

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"RELEASE_REQUEST, REJECTED", "RELEASE_REQUEST, CONFLICTED", "BRANCH, FAILED", "BRANCH, CONFLICTED"})
  void aStuckStateBreaksTheWaitOnlyAfterThirtyMinutes(String kind, String state) {
    Kind k = Kind.valueOf(kind);
    assertTrue(seen(k, state, NOW.minus(Duration.ofMinutes(29)), "LIVE").isEmpty(), "within the grace");
    assertEquals(Why.STUCK, seen(k, state, NOW.minus(WaitBreaks.STUCK_AFTER), "LIVE").orElseThrow().why());
    assertEquals(Why.STUCK, seen(k, state, NOW.minus(Duration.ofHours(3)), "LIVE").orElseThrow().why());
    // Waited for, it is a match, never a break.
    assertTrue(seen(k, state, NOW.minus(Duration.ofHours(3)), state).isEmpty());
    // No known start: never stuck.
    assertTrue(seen(k, state, null, "LIVE").isEmpty());
  }

  @Test
  void onlyRequestsAndBranchesGetStuck() {
    assertFalse(WaitBreaks.isStuck(Kind.CI_RUN, "FAILED"));
    assertFalse(WaitBreaks.isStuck(Kind.WORK_ITEM, "REJECTED"));
    assertFalse(WaitBreaks.isStuck(Kind.BRANCH, "REJECTED"));
    assertTrue(WaitBreaks.isStuck(Kind.RELEASE_REQUEST, "REJECTED"));
  }

  @Test
  void theReasonNamesTheStateSinceWhenTheRequestAndTheDetail() {
    Broken b =
        WaitBreaks.seen(
                Kind.RELEASE_REQUEST,
                List.of("FINALIZED"),
                new Seen(
                    "qits-x-rr-3",
                    "REJECTED",
                    Instant.parse("2026-11-08T10:42:31Z"),
                    null,
                    WaitBreaks.quotedDetail("Automation \"golden\" failed\nsee run")),
                NOW)
            .orElseThrow();
    assertEquals(
        "qits-x-rr-3 is REJECTED since 10:42 UTC: \"Automation \\\"golden\\\" failed\\nsee run\"",
        b.reason());
  }

  // --- 404 ----------------------------------------------------------------------------------------

  @Test
  void a404BreaksEveryKindThatIsLookedUp() {
    for (Kind kind : List.of(Kind.RELEASE_REQUEST, Kind.CI_RUN, Kind.WORK_ITEM, Kind.COMMENTS, Kind.BRANCH)) {
      Broken b = WaitBreaks.notFound(kind, "run 9677c59a", "qits-ci knows no run 9677c59a.").orElseThrow();
      assertEquals(Why.NOT_FOUND, b.why());
      assertEquals("run 9677c59a does not exist: qits-ci knows no run 9677c59a", b.reason());
    }
    assertTrue(WaitBreaks.notFound(Kind.OTHER, "x", "no").isEmpty());
  }

  // --- branches -----------------------------------------------------------------------------------

  private static final String BRANCH = "ticket/qits-1961";

  /** {@code qits wait branch} shapes: {@code until} on {@code path}, early groups, a repository. */
  private static Subject branchWait(String path, List<String> targets, List<String> early, String repository) {
    List<List<Condition>> groups = new ArrayList<>();
    for (String state : targets) {
      groups.add(group(path, state, repository));
    }
    for (String state : early) {
      groups.add(group("step", state, repository));
    }
    return WaitSubjects.parse(new EventSelection("BranchDeliveryChanged", groups));
  }

  private static List<Condition> group(String path, String state, String repository) {
    List<Condition> group = new ArrayList<>();
    group.add(Condition.exact("branch", BRANCH));
    if (repository != null) {
      group.add(Condition.exact("repository", repository));
    }
    group.add(Condition.exact(path, state));
    return group;
  }

  private static Delivery row(String repository, String step, Duration ago) {
    return new Delivery(repository, step, repository + "-rr-3", "FAILED".equals(step) ? "run-7" : null, NOW.minus(ago));
  }

  private static Optional<Broken> branch(Subject subject, Branch branch, Duration declaredAgo) {
    return WaitBreaks.branch(
        subject, "branch " + BRANCH, subject.targetStates(), branch, NOW.minus(declaredAgo), NOW);
  }

  @Test
  void aBranchWithNoDeliveryBreaksThirtyMinutesAfterTheDeclaration() {
    Subject live = branchWait("everywhere", List.of("LIVE"), List.of("FAILED", "CONFLICTED", "WITHDRAWN"), null);
    Branch none = new Branch(null, List.of());
    assertTrue(branch(live, none, Duration.ofMinutes(29)).isEmpty(), "the ledger may not have it yet");
    Broken b = branch(live, none, Duration.ofMinutes(31)).orElseThrow();
    assertEquals(Why.UNFOLLOWED, b.why());
    assertEquals(
        "branch ticket/qits-1961 has no delivery 31 min after the wait was declared: the delivery"
            + " ledger does not follow it, so no BranchDeliveryChanged is coming",
        b.reason());

    // A repository's wait wants that repository's row: another one's is not enough.
    Subject inCi = branchWait("step", List.of("LIVE"), List.of(), "qits-ci-service");
    Branch elsewhere = new Branch("JOINED", List.of(row("qits-ci-frontend", "JOINED", Duration.ofMinutes(40))));
    Broken missing = branch(inCi, elsewhere, Duration.ofHours(1)).orElseThrow();
    assertEquals(Why.UNFOLLOWED, missing.why());
    assertTrue(missing.reason().contains("has no delivery in qits-ci-service 1 h 0 min after"), missing.reason());
  }

  @Test
  void aRepositoryWaitReadsItsOwnRow() {
    Subject inCi = branchWait("step", List.of("LIVE"), List.of(), "qits-ci-service");
    Branch failedHere =
        new Branch(
            "JOINED",
            List.of(
                row("qits-ci-service", "FAILED", Duration.ofMinutes(45)),
                row("qits-ci-frontend", "LIVE", Duration.ofMinutes(5))));
    Broken b = branch(inCi, failedHere, Duration.ofHours(1)).orElseThrow();
    assertEquals(Why.STUCK, b.why());
    assertEquals(
        "branch ticket/qits-1961 in qits-ci-service is FAILED since 11:15 UTC in qits-ci-service-rr-3: run run-7",
        b.reason());

    // Another repository's failure is not this wait's.
    Subject inFrontend = branchWait("step", List.of("LIVE"), List.of(), "qits-ci-frontend");
    assertTrue(branch(inFrontend, failedHere, Duration.ofHours(1)).isEmpty(), "its own row is LIVE: a match");
    Branch stillJoined =
        new Branch("JOINED", List.of(row("qits-ci-frontend", "JOINED", Duration.ofHours(3))));
    assertTrue(branch(inFrontend, stillJoined, Duration.ofHours(4)).isEmpty(), "JOINED is neither");
  }

  @Test
  void anEverywhereWaitReadsEverywhereAndBreaksOnARowStuckForTheGrace() {
    // qits-1952's shape: --until live with no early groups, one repository FAILED in a REJECTED request.
    Subject live = branchWait("everywhere", List.of("LIVE"), List.of(), null);
    Branch failed =
        new Branch(
            "JOINED",
            List.of(
                row("qits-ci-service", "FAILED", Duration.ofMinutes(40)),
                row("qits-ci-frontend", "JOINED", Duration.ofMinutes(50))));
    Broken b = branch(live, failed, Duration.ofHours(1)).orElseThrow();
    assertEquals(Why.STUCK, b.why());
    assertTrue(b.reason().contains("in qits-ci-service is FAILED since 11:20 UTC in qits-ci-service-rr-3: run run-7"), b.reason());

    Branch justFailed =
        new Branch("JOINED", List.of(row("qits-ci-service", "FAILED", Duration.ofMinutes(10))));
    assertTrue(branch(live, justFailed, Duration.ofHours(1)).isEmpty(), "within the grace");

    // Everywhere LIVE is the target; everywhere RELEASED is on its way.
    assertTrue(branch(live, new Branch("RELEASED", List.of(row("a", "RELEASED", Duration.ofHours(2)))), Duration.ofHours(3)).isEmpty());

    // Released everywhere, waited on for JOINED only: everywhere LIVE is final and not a target.
    Subject joined = branchWait("everywhere", List.of("JOINED"), List.of(), null);
    Broken over = branch(joined, new Branch("LIVE", List.of(row("a", "LIVE", Duration.ofMinutes(2)))), Duration.ofHours(1)).orElseThrow();
    assertEquals(Why.FINAL, over.why());
    assertEquals("branch ticket/qits-1961 is LIVE since 11:58 UTC", over.reason());

    // Every row withdrawn: no everywhere, and every row has ended.
    Branch withdrawn = new Branch(null, List.of(row("a", "WITHDRAWN", Duration.ofMinutes(3))));
    assertEquals(Why.FINAL, branch(live, withdrawn, Duration.ofHours(1)).orElseThrow().why());
  }

  @Test
  void anUnscopedStepWaitBreaksOnlyWhenEveryRowEndedOrIsStuck() {
    Subject released = branchWait("step", List.of("RELEASED"), List.of(), null);
    Branch oneOpen =
        new Branch(
            "JOINED",
            List.of(row("a", "FAILED", Duration.ofHours(1)), row("b", "JOINED", Duration.ofHours(1))));
    assertTrue(branch(released, oneOpen, Duration.ofHours(2)).isEmpty(), "b may still get there");

    Branch allOver =
        new Branch(
            "JOINED",
            List.of(row("a", "FAILED", Duration.ofHours(1)), row("b", "LIVE", Duration.ofMinutes(30))));
    Broken b = branch(released, allOver, Duration.ofHours(2)).orElseThrow();
    assertEquals(
        "branch ticket/qits-1961 in b is LIVE since 11:30 UTC in b-rr-3 (and the delivery in each of"
            + " its other 1 repositories has ended or is stuck too)",
        b.reason());

    Branch oneTarget =
        new Branch(
            "JOINED",
            List.of(row("a", "FAILED", Duration.ofHours(1)), row("b", "RELEASED", Duration.ofHours(1))));
    assertTrue(branch(released, oneTarget, Duration.ofHours(2)).isEmpty(), "a target row is a match");
  }

  // --- the reading --------------------------------------------------------------------------------

  @Test
  void aBranchLookupIsReadAsTheWaitReadsItsEvents() {
    Branch branch =
        new Branch(
            "JOINED",
            List.of(
                row("a", "JOINED", Duration.ofMinutes(30)),
                row("b", "FAILED", Duration.ofMinutes(20)),
                row("c", "RELEASED", Duration.ofMinutes(10))));
    Subject everywhere = branchWait("everywhere", List.of("LIVE"), List.of("FAILED"), null);
    WaitSubjectResolver.Resolved e = WaitSubjects.branchReading(everywhere, everywhere.targetStates(), branch);
    assertEquals("JOINED", e.state());
    assertEquals(NOW.minus(Duration.ofMinutes(30)), e.stateAt(), "since the row at that step");
    assertNull(e.subjectId());

    Subject inB = branchWait("step", List.of("LIVE"), List.of(), "b");
    assertEquals("FAILED", WaitSubjects.branchReading(inB, inB.targetStates(), branch).state());

    Subject unscoped = branchWait("step", List.of("FAILED"), List.of(), null);
    assertEquals(
        "FAILED",
        WaitSubjects.branchReading(unscoped, unscoped.targetStates(), branch).state(),
        "a row in a target state, before the newest");
    Subject newest = branchWait("step", List.of("LIVE"), List.of(), null);
    assertEquals("RELEASED", WaitSubjects.branchReading(newest, newest.targetStates(), branch).state());

    assertNull(WaitSubjects.branchReading(inB, List.of(), new Branch(null, List.of())).state());
  }
}
