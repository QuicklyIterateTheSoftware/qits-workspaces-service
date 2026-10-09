package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit test of the registry's lifecycle: active-process discovery, the post-done retention
 * window (replay + immediate done, then eviction → unknown id), the PROCESS hints on begin/done,
 * and the idle backstop (reaps a quiet process, re-arms for an actively streaming one). The
 * publisher is a recording stub — the CDI wiring is covered by the service module's channel tests.
 */
class TechnicalProcessRegistryTest {

  private record Fired(String repoId, Long workspaceRowId, WorkspaceChangeHint.Topic topic) {}

  private final List<Fired> fired = new CopyOnWriteArrayList<>();
  private TechnicalProcessRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new TechnicalProcessRegistry();
    registry.doneTtlMillis = 150;
    registry.maxIdleMillis = 60_000;
    registry.changePublisher =
        new WorkspaceChangePublisher() {
          @Override
          public void fire(String repoId, Long workspaceRowId, WorkspaceChangeHint.Topic topic) {
            fired.add(new Fired(repoId, workspaceRowId, topic));
          }
        };
  }

  @Test
  void beginRegistersTheProcessAsTheWorkspacesActiveOneAndFiresAProcessHint() {
    TechnicalProcess process = registry.begin("repo-1", "ws-1", 1L);

    assertEquals(process.id(), registry.activeFor("repo-1", "ws-1").orElseThrow());
    assertEquals(process, registry.find(process.id()).orElseThrow());
    assertEquals(List.of(new Fired("repo-1", 1L, WorkspaceChangeHint.Topic.PROCESS)), fired);
  }

  @Test
  void doneClearsTheActiveMappingFiresAHintAndEvictsAfterTheRetentionWindow() throws Exception {
    TechnicalProcess process = registry.begin("repo-1", "ws-1", 1L);
    process.completeNoOp("container-start", "already running");

    assertTrue(registry.activeFor("repo-1", "ws-1").isEmpty(), "done means no longer active");
    assertEquals(2, fired.size(), "begin + done each announce a PROCESS hint");
    assertTrue(
        registry.find(process.id()).isPresent(),
        "within the retention window a late subscriber still resolves the id");

    long deadline = System.currentTimeMillis() + 5_000;
    while (registry.find(process.id()).isPresent() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertFalse(registry.find(process.id()).isPresent(), "evicted after the TTL");
  }

  @Test
  void aNewerProcessForTheSameWorkspaceBecomesTheActiveOne() {
    TechnicalProcess first = registry.begin("repo-1", "ws-1", 1L);
    TechnicalProcess second = registry.begin("repo-1", "ws-1", 1L);

    assertEquals(second.id(), registry.activeFor("repo-1", "ws-1").orElseThrow());
    // The older process finishing must not clear the newer active mapping.
    first.completeNoOp("container-start", "superseded");
    assertEquals(second.id(), registry.activeFor("repo-1", "ws-1").orElseThrow());
  }

  @Test
  void beginForRepositoryRegistersTheActiveProcessAndFiresARepoScopedHint() {
    TechnicalProcess process = fresh(registry.beginForRepository("repo-1", "pull"));

    assertEquals(process.id(), registry.activeForRepository("repo-1").orElseThrow());
    assertEquals(process, registry.find(process.id()).orElseThrow());
    // A repository process announces on the repository channel: workspaceId is null.
    assertEquals(List.of(new Fired("repo-1", null, WorkspaceChangeHint.Topic.PROCESS)), fired);
    assertTrue(
        registry.activeFor("repo-1", "ws-1").isEmpty(),
        "a repo process is not a workspace's active process");
  }

  @Test
  void aSecondSameKindBeginReusesTheLiveProcessWithoutAnotherHint() {
    TechnicalProcess process = fresh(registry.beginForRepository("repo-1", "pull"));

    RepoProcessLease second = registry.beginForRepository("repo-1", "pull");
    assertEquals(
        process.id(),
        assertInstanceOf(RepoProcessLease.Reused.class, second).processId(),
        "a same-kind begin reuses the live process (single-flight)");
    assertEquals(1, fired.size(), "reuse registers nothing new, so no second hint fires");
  }

  @Test
  void aDifferentKindBeginConflictsWithTheLiveProcess() {
    fresh(registry.beginForRepository("repo-1", "pull"));

    RepoProcessLease second = registry.beginForRepository("repo-1", "sync");
    assertEquals(
        "pull",
        assertInstanceOf(RepoProcessLease.Conflict.class, second).runningKind(),
        "a sync while a pull is live is a conflict naming the running kind");
    assertEquals(1, fired.size(), "a conflict registers nothing, so no second hint fires");
  }

  @Test
  void aFreshBeginAfterTheLiveOneFinishedGetsANewProcess() {
    TechnicalProcess first = fresh(registry.beginForRepository("repo-1", "pull"));
    first.completeNoOp("pull:repo-1", "up to date");

    // The mapping cleared on done, so even a different kind now starts fresh (not a conflict).
    TechnicalProcess second = fresh(registry.beginForRepository("repo-1", "sync"));
    assertEquals(second.id(), registry.activeForRepository("repo-1").orElseThrow());
  }

  @Test
  void doneClearsTheRepositoryActiveMappingFiresAHintAndEvicts() throws Exception {
    TechnicalProcess process = fresh(registry.beginForRepository("repo-1", "pull"));
    process.completeNoOp("pull:repo-1", "up to date");

    assertTrue(registry.activeForRepository("repo-1").isEmpty(), "done means no longer active");
    assertEquals(2, fired.size(), "begin + done each announce a repo PROCESS hint");
    assertEquals(new Fired("repo-1", null, WorkspaceChangeHint.Topic.PROCESS), fired.get(1));
    assertTrue(
        registry.find(process.id()).isPresent(),
        "within the retention window a late subscriber still resolves the id");

    long deadline = System.currentTimeMillis() + 5_000;
    while (registry.find(process.id()).isPresent() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertFalse(registry.find(process.id()).isPresent(), "evicted after the TTL");
  }

  @Test
  void activeForRepositoryIsEmptyForAnUnknownRepository() {
    assertTrue(registry.activeForRepository("nope").isEmpty());
  }

  private static TechnicalProcess fresh(RepoProcessLease lease) {
    return assertInstanceOf(RepoProcessLease.Fresh.class, lease).process();
  }

  @Test
  void theIdleBackstopForceFinishesAProcessThatGoesQuiet() throws Exception {
    registry.maxIdleMillis = 100;
    TechnicalProcess process = registry.begin("repo-1", "ws-1", 1L);
    process.openSegment("container"); // never settled — e.g. a chain that never reports

    long deadline = System.currentTimeMillis() + 5_000;
    while (!process.isTerminal() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(process.isTerminal(), "the backstop must end a stuck (idle) process");
    assertTrue(registry.activeFor("repo-1", "ws-1").isEmpty());
  }

  @Test
  void theIdleBackstopReArmsForAnActivelyStreamingProcess() throws Exception {
    registry.maxIdleMillis = 200;
    TechnicalProcess process = registry.begin("repo-1", "ws-1", 1L);
    process.openSegment("chain");

    // Keep emitting past a single idle window: each line resets the idle clock, so a legitimately
    // long-but-active chain (unbounded total) is never cut mid-run.
    for (int i = 0; i < 6; i++) {
      Thread.sleep(60);
      process.appendLine("chain", "line " + i);
    }
    assertFalse(process.isTerminal(), "an actively streaming process outlives the idle window");

    // Once it goes quiet the backstop still reaps it.
    long deadline = System.currentTimeMillis() + 5_000;
    while (!process.isTerminal() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(process.isTerminal(), "after going idle the backstop ends it");
  }

  @Test
  void findOnAnUnknownOrNullIdIsEmpty() {
    assertTrue(registry.find("nope").isEmpty());
    assertTrue(registry.find(null).isEmpty());
  }
}
