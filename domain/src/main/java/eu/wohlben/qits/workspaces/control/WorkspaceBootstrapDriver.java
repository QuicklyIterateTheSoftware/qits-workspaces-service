package eu.wohlben.qits.workspaces.control;

import java.time.Duration;
import java.util.Optional;

/**
 * Drives (and awaits) the in-container workspace-daemon's bootstrap chain — the
 * install/migrate/seed commands the daemon runs from its own {@code .qits-config.yml}, right after the
 * self-clone (docs/epics/qits-workspace-daemon/ Part 3). Framework-free so it
 * lives in {@code domain}; the real implementation is the backend {@code WorkspaceDaemonRegistry}
 * (service module), reached over the control socket. Apps without the backend impl (cli, tests with
 * no daemon) provide a test double or simply have no bean; the runner injects it as {@code
 * Instance<>}.
 *
 * <p>Two entry points share the same wait machinery. On a <b>fresh provision</b> the daemon runs
 * the chain autonomously (no request); the host only {@link #awaitBootstrap awaits} the terminal
 * outcome, feeding each step's progress to a {@link StepSink}. A <b>manual re-run</b> from the
 * workspace surface {@link #runBootstrap sends} the daemon a run request and then awaits the same
 * way.
 *
 * <p>Distinct from {@link WorkspaceDaemonProvisioner} (clone) and {@link WorkspaceConfigReader}
 * (read): this one runs the chain. The host records the streamed outcomes and settles the start's
 * {@code bootstrap:} segments from them; {@link Result#ok()} is the chain's overall verdict.
 */
public interface WorkspaceBootstrapDriver {

  /**
   * Await the daemon's autonomous boot-time bootstrap chain for {@code workspaceId}, feeding {@code
   * sink} as steps stream in. Returns {@link Optional#empty()} when no daemon becomes live within
   * {@code connectTimeout} (the chain never ran).
   *
   * @param chainTimeout how long, once a daemon is live, to wait for the terminal {@code
   *     Bootstrapped}; a timeout resolves to a failed {@link Result}
   */
  Optional<Result> awaitBootstrap(
      Long workspaceId, StepSink sink, Duration connectTimeout, Duration chainTimeout);

  /**
   * Ask the daemon to re-run the chain (blank {@code name}) or a single step ({@code name}), then
   * await it the same way as {@link #awaitBootstrap}. Returns empty when no daemon is live to run
   * it.
   */
  Optional<Result> runBootstrap(
      Long workspaceId, String name, StepSink sink, Duration chainTimeout);

  /** The chain-complete outcome: {@code ok} false means a step failed (or the await timed out). */
  record Result(boolean ok) {}

  /**
   * Register a sink that receives <b>every</b> step outcome the daemon reports, awaited or not, for
   * the life of the app — the persistent counterpart of the per-run {@link StepSink}. This is what
   * lets a chain the daemon ran on its own (its HTTP {@code POST /bootstrap-commands/run}, reached
   * through the container proxy) still land as host {@code workspace_bootstrap_run} rows: no host
   * awaiter exists for such a run, so a sink tied to an await never sees it (measured live as D1's
   * missing-rows leg). The host recorder subscribes once at startup.
   */
  void subscribe(OutcomeSink sink);

  /**
   * Receives step outcomes as the daemon streams them, with the workspace identity the backend
   * knows (repoId from the daemon's {@code Hello}, the label, and the row id the socket is keyed
   * by). Callbacks may arrive on a dispatch thread; implementations guard their own failures.
   */
  interface OutcomeSink {

    /** One step's terminal outcome: {@code SKIPPED}, {@code SUCCEEDED}, or {@code FAILED}. */
    void onOutcome(
        String repoId,
        String workspaceId,
        Long workspaceRowId,
        String stepName,
        String outcome,
        Integer exitCode);
  }

  /**
   * Receives a bootstrap chain's per-step progress as the daemon streams it, so the host can settle
   * process segments, record run outcomes, and hint the UI. Callbacks arrive on the socket thread.
   */
  interface StepSink {

    /** A step is entering a phase: {@code CHECK}, {@code EXECUTE}, or {@code SKIP}. */
    void onStep(String name, String phase);

    /** A line of the step's streamed output. */
    void onLine(String name, String line);

    /**
     * A step's terminal outcome: {@code SKIPPED}, {@code SUCCEEDED}, or {@code FAILED}, with the
     * process exit code ({@code null} unknown).
     */
    void onOutcome(String name, String outcome, Integer exitCode);
  }
}
