package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.BootstrapRunDto;
import eu.wohlben.qits.workspaces.dto.TechnicalProcessFrame;
import eu.wohlben.qits.workspaces.entity.BootstrapOutcome;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The host bootstrap <b>wiring</b> against the {@code FakeWorkspaceBootstrapDriver} (which plays
 * the daemon: it parses the fake checkout's committed {@code .qits-config.yml} and runs each step
 * through {@code FakeContainerRuntime} — real host processes, no docker): a fresh provision awaits
 * the daemon's chain and records outcomes strictly in order; the check script skips; a failure
 * aborts the rest; a restart-shaped start passes straight through without re-running; and the
 * manual chain re-run is the recovery path. The chain <b>semantics</b> (order, check-skip,
 * fail-fast, timeout-terminate) are the daemon module's {@code BootstrapRunnerTest}; bootstrap steps
 * run in the container now, so they no longer leave host {@code Command} audit rows (their live
 * output is the {@code bootstrap:<name>} process segment). Kill-switch coverage is {@link
 * WorkspaceBootstrapKillSwitchTest}.
 *
 * <p><b>Every streamed start must reach {@code done}.</b> The bootstrap phase is the last phase of a
 * start's technical process, and the runner is what ends it ({@link TechnicalProcess#finishBootstrap})
 * — on success, on failure and on the restart pass-through alike. A path that forgets leaves the
 * start open until the registry's idle reaper, a quarter of an hour later, with nothing failing
 * meanwhile; so each provisioning test here starts through {@code beginEnsureContainer} and awaits
 * the process's terminal frame, and asserts its verdict.
 *
 * <p>Staging: the chain is committed as {@code .qits-config.yml} on {@code master} before the
 * workspace is forked (so the provision-triggered — asynchronous — chain sees it deterministically;
 * a file written into the checkout afterwards would race the async observer). {@code BootstrapRun}
 * rows are keyed by the config-declared step {@code id:} (which defaults to the name) — most tests
 * here declare no {@code id:}, so {@code bootstrapCommandId} equals the step name for them; the
 * declared-id test stages a config where the two differ.
 */
@QuarkusTest
// The chain-await bound this class needs lives in the shared profile alongside another class's
// scenery (see SharedTestOverridesProfile).
@TestProfile(SharedTestOverridesProfile.class)
public class WorkspaceBootstrapRunnerTest {

  private static final long AWAIT_MILLIS = 20_000;

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject BootstrapRunService bootstrapRunService;
  @Inject WorkspaceBootstrapRunner runner;
  @Inject FakeWorkspaceConfigReader configReader;
  @Inject FakeWorkspaceBootstrapDriver bootstrapDriver;
  @Inject TechnicalProcessRegistry processes;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private Path scratch;

  @BeforeEach
  void setUp() throws Exception {
    configReader.clear(); // the fake is a shared singleton across this class's test methods
    scratch = Files.createTempDirectory("qits-bootstrap-runner-scratch");
  }

  /**
   * Clones the fixture, commits {@code configYaml} as {@code .qits-config.yml} on master (when
   * given), and adds a lazy {@code work} workspace forked off that master (no container yet).
   */
  private String repoWithWorkspace(String name, String configYaml) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    if (configYaml != null) {
      commitConfig(repoId, configYaml);
    }
    workspaceService.createWorkspace(repoId, "work", "master", "work", null, false, false, true);
    return repoId;
  }

  /** Commit {@code .qits-config.yml} onto the origin's master, so workspace forks carry it. */
  private void commitConfig(String repoId, String yaml) throws Exception {
    Path origin = Path.of(dataDir, repoId, "origin");
    Path worktree = Files.createTempDirectory("qits-config-commit");
    TestGit.exec(null, "git", "clone", origin.toString(), worktree.toString());
    TestGit.exec(worktree.toFile(), "git", "config", "user.email", "t@example.com");
    TestGit.exec(worktree.toFile(), "git", "config", "user.name", "Test");
    Files.writeString(worktree.resolve(".qits-config.yml"), yaml);
    TestGit.exec(worktree.toFile(), "git", "add", ".qits-config.yml");
    TestGit.exec(worktree.toFile(), "git", "commit", "-m", "stage qits config");
    TestGit.exec(worktree.toFile(), "git", "push", "origin", "HEAD:master");
  }

  /** A bootstrap chain YAML (version 1) from {@code name=execute[=check]} tuples. */
  private static String chainYaml(String... steps) {
    StringBuilder yaml = new StringBuilder("version: 1\nbootstrap:\n");
    for (String step : steps) {
      String[] parts = step.split("=", 3);
      yaml.append("  - name: '").append(parts[0]).append("'\n");
      yaml.append("    execute: '").append(parts[1]).append("'\n");
      if (parts.length > 2) {
        yaml.append("    check: '").append(parts[2]).append("'\n");
      }
    }
    return yaml.toString();
  }

  /** Records a process's full replay; attaching to a terminal process replays it and its done. */
  private static final class Replay implements TechnicalProcess.Listener {
    final List<TechnicalProcessFrame> frames = new ArrayList<>();

    @Override
    public void onFrame(TechnicalProcessFrame frame) {
      frames.add(frame);
    }

    @Override
    public void onDone() {}

    @Override
    public boolean isOpen() {
      return true;
    }
  }

  /** Start the workspace's container as the UI does — streamed — and return the process id. */
  private String start(String repoId) {
    String processId = workspaceService.beginEnsureContainer(workspaceIds.of(repoId, "work"));
    assertNotNull(processId, "a streamed start registers a technical process");
    return processId;
  }

  /**
   * Await the start's terminal {@code done} frame and return its verdict ({@code ok}/{@code
   * failed}). A start the runner forgot to end would sit here until the timeout.
   */
  private String awaitDone(String processId) throws InterruptedException {
    TechnicalProcess process = processes.find(processId).orElseThrow();
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (!process.isTerminal() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(process.isTerminal(), "the start's process never reached done");
    Replay replay = new Replay();
    process.attach(replay);
    return replay.frames.stream()
        .filter(f -> "done".equals(f.kind()))
        .map(TechnicalProcessFrame::status)
        .findFirst()
        .orElseThrow(() -> new AssertionError("a terminal process replayed no done frame"));
  }

  /** Wait until no manual run holds the workspace, so nothing async leaks into the next test. */
  private void awaitNoChainRunning(String repoId) throws InterruptedException {
    await(
        () -> runner.isChainRunning(workspaceIds.of(repoId, "work")) ? null : Boolean.TRUE,
        "the manual run to release the workspace");
  }

  private BootstrapRunDto lastRun(String repoId, String stepName) {
    return bootstrapRunService.listForWorkspace(workspaceIds.of(repoId, "work")).stream()
        .filter(r -> r.bootstrapCommandId().equals(stepName))
        .findFirst()
        .orElse(null);
  }

  private <T> T await(Supplier<T> probe, String what) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    T last = null;
    while (System.currentTimeMillis() < deadline) {
      last = probe.get();
      if (last != null) {
        return last;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for " + what + "; last: " + last);
  }

  private BootstrapRunDto awaitOutcome(String repoId, String stepName, BootstrapOutcome expected)
      throws InterruptedException {
    return await(
        () -> {
          BootstrapRunDto run = lastRun(repoId, stepName);
          return run != null && run.outcome() == expected ? run : null;
        },
        expected + " for " + stepName);
  }

  @Test
  public void freshProvisionRunsChainInOrderAndEndsTheStartOk() throws Exception {
    Path orderLog = scratch.resolve("order.log");
    String repoId =
        repoWithWorkspace(
            "Bootstrap Fresh",
            chainYaml("first=echo first >> " + orderLog, "second=echo second >> " + orderLog));

    // First access provisions the container (fresh) and triggers the chain.
    String processId = start(repoId);

    BootstrapRunDto first = awaitOutcome(repoId, "first", BootstrapOutcome.SUCCEEDED);
    BootstrapRunDto second = awaitOutcome(repoId, "second", BootstrapOutcome.SUCCEEDED);
    assertEquals("ok", awaitDone(processId), "a successful chain ends the start ok");

    assertEquals(
        List.of("first", "second"),
        Files.readAllLines(orderLog),
        "commands ran strictly in declaration order");
    assertEquals(0, first.exitCode());
    assertNull(first.commandId(), "bootstrap steps run in-container — no host Command audit row");
    assertEquals(0, second.exitCode());
  }

  @Test
  public void failingCheckSkipsWithoutCommandRowAndChainContinues() throws Exception {
    Path marker = scratch.resolve("ran.log");
    // check exits non-zero → "not needed" → SKIPPED, execute never runs.
    String repoId =
        repoWithWorkspace(
            "Bootstrap Skip",
            chainYaml(
                "skipped=echo skipped >> " + marker + "=exit 1",
                "ran=echo ran >> " + marker + "=exit 0"));

    String processId = start(repoId);

    BootstrapRunDto skipped = awaitOutcome(repoId, "skipped", BootstrapOutcome.SKIPPED);
    awaitOutcome(repoId, "ran", BootstrapOutcome.SUCCEEDED);
    assertNull(skipped.commandId(), "a skip leaves no Command row");
    assertNull(skipped.exitCode());
    assertEquals(List.of("ran"), Files.readAllLines(marker), "only the checked-in command ran");
    assertEquals("ok", awaitDone(processId), "skips count as chain success");
  }

  @Test
  public void failureAbortsChainAndEndsTheStartFailed() throws Exception {
    Path marker = scratch.resolve("never.log");
    String repoId =
        repoWithWorkspace(
            "Bootstrap Fail", chainYaml("failing=exit 7", "never=echo never >> " + marker));

    String processId = start(repoId);

    BootstrapRunDto failed = awaitOutcome(repoId, "failing", BootstrapOutcome.FAILED);
    assertEquals(7, failed.exitCode());
    // A failed chain still ENDS the start — failed, through its failed bootstrap segment — rather
    // than leaving it open for the idle reaper.
    assertEquals("failed", awaitDone(processId), "a failed chain ends the start failed");
    assertNull(lastRun(repoId, "never"), "commands after the failure never ran");
    assertFalse(Files.exists(marker));
  }

  @Test
  public void restartShapedStartPassesStraightThroughWithoutRunning() throws Exception {
    Path marker = scratch.resolve("installs.log");
    String repoId =
        repoWithWorkspace("Bootstrap Restart", chainYaml("install=echo installed >> " + marker));

    // A fresh provision runs the chain once.
    String fresh = start(repoId);
    awaitOutcome(repoId, "install", BootstrapOutcome.SUCCEEDED);
    assertEquals("ok", awaitDone(fresh));

    // A restart of the Exited container (freshProvision=false): no chain re-run, and the runner's
    // pass-through is what ends the restart's process.
    workspaceService.stopContainer(workspaceIds.of(repoId, "work"));
    assertEquals("ok", awaitDone(start(repoId)), "the pass-through ends the restart ok");

    assertEquals(
        List.of("installed"),
        Files.readAllLines(marker),
        "a plain restart does not re-run the chain");
  }

  @Test
  public void manualChainRerunAfterFailureIsTheRecoveryPath() throws Exception {
    Path flag = scratch.resolve("fixed.flag");
    // Fails until the flag file exists — the "broken then fixed" bootstrap step.
    String repoId = repoWithWorkspace("Bootstrap Recover", chainYaml("flaky=test -f " + flag));

    String processId = start(repoId);
    awaitOutcome(repoId, "flaky", BootstrapOutcome.FAILED);
    assertEquals("failed", awaitDone(processId));

    // Fix the world, then re-run the whole chain from the workspace surface.
    Files.writeString(flag, "fixed");
    runner.runChainAsync(workspaceIds.of(repoId, "work"));

    awaitOutcome(repoId, "flaky", BootstrapOutcome.SUCCEEDED);
    awaitNoChainRunning(repoId);
  }

  @Test
  public void singleCommandRerunRecordsItsOutcomeOnly() throws Exception {
    Path marker = scratch.resolve("single.log");
    String repoId =
        repoWithWorkspace(
            "Bootstrap Single",
            chainYaml("target=echo target >> " + marker, "other=echo other >> " + marker));

    // A single-step re-run touches only its own step — deliberately, even when its
    // ensureContainer fresh-provisions the container here: the run holds the in-flight guard, so
    // the provision's container-started event yields to it and the rest of the chain does not run.
    // (The full chain is the fresh-provision/"Run all" job, not this trigger's.) The step id passes
    // through as the name (no config is readable for the workspace yet — ids default to names).
    runner.runSingleAsync(workspaceIds.of(repoId, "work"), "target");

    awaitOutcome(repoId, "target", BootstrapOutcome.SUCCEEDED);
    Thread.sleep(500);
    assertNull(lastRun(repoId, "other"), "a single re-run touches only its command");
    assertEquals(List.of("target"), Files.readAllLines(marker));
  }

  @Test
  public void concurrentManualRunsAreRejected() throws Exception {
    String repoId = repoWithWorkspace("Bootstrap Conflict", chainYaml("slow=sleep 3"));

    runner.runChainAsync(workspaceIds.of(repoId, "work"));
    assertThrows(
        BadRequestException.class,
        () -> runner.runChainAsync(workspaceIds.of(repoId, "work")),
        "a second run while one is in flight is rejected");

    // Drain the first run so the async pipeline is quiescent before the next test.
    awaitOutcome(repoId, "slow", BootstrapOutcome.SUCCEEDED);
    awaitNoChainRunning(repoId);
  }

  @Test
  public void anUnawaitedDaemonRunStillRecordsItsOutcomeRow() throws Exception {
    // The daemon can run the chain on its own (its HTTP `POST /bootstrap-commands/run`, reached
    // through the container proxy) — no host awaiter exists for such a run, so a sink tied to an
    // await never sees it. Measured live (D1): the POST answered 202 and wrote no row. The
    // persistent recorder the runner subscribes at startup is what closes it; the fake fans the
    // outcome to it exactly as the registry fans an unawaited BootstrapOutcome frame.
    String repoId = repoWithWorkspace("Bootstrap Unawaited", null);

    bootstrapDriver.reportUnawaitedOutcome(
        repoId, "work", workspaceIds.of(repoId, "work"), "own-run", "SUCCEEDED", 0);

    BootstrapRunDto run = awaitOutcome(repoId, "own-run", BootstrapOutcome.SUCCEEDED);
    assertEquals(0, run.exitCode());
    assertNull(run.commandId(), "an in-container step leaves no host Command audit row");
  }

  @Test
  public void recorderPersistsTheDeclaredIdNotTheStepName() throws Exception {
    // The daemon's BootstrapOutcome frame carries the step NAME only ("say-hello"); the declared
    // chain — and the panel's join — is keyed by the config-declared id ("greet"). The recorder
    // resolves the name back to the id through the workspace config. Recording the raw name here
    // (measured live, N2) made the join miss and rendered a green step as never-run.
    String repoId = repoWithWorkspace("Bootstrap Declared Id", null);
    Long rowId = workspaceIds.of(repoId, "work");
    configReader.setConfig(
        rowId,
        new QitsConfig(
            null,
            null,
            null,
            List.of(
                new QitsConfig.BootstrapDecl("greet", "say-hello", null, "echo hi", null, null))));

    bootstrapDriver.reportUnawaitedOutcome(repoId, "work", rowId, "say-hello", "SUCCEEDED", 0);

    BootstrapRunDto run = awaitOutcome(repoId, "greet", BootstrapOutcome.SUCCEEDED);
    assertEquals("say-hello", run.commandName(), "the name stays readable beside the id");
    assertNull(lastRun(repoId, "say-hello"), "no second row keyed by the raw step name");
  }

  // DROPPED IN EXTRACTION: repositoryWithRecordedBootstrapRunDeletesCleanly.
  // It asserted that deleting a *repository* cascade-deletes its workspace rows and, through
  // workspace_bootstrap_run's `on delete cascade`, the runs recorded against them (the V32
  // command_agent_session bug class). Repositories live in another context and another database
  // here, so there is no repositoryService.delete to call and no cross-database cascade to fire.
  // The FK and its `on delete cascade` are still in V2; what is now unasserted is the *repository*
  // half of the chain, and it belongs in qits-projects beside the delete that starts it.
}
