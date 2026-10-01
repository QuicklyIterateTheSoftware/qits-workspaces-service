package eu.wohlben.qits.workspaces.control;

import java.util.List;
import java.util.Map;

/**
 * The per-workspace container runtime. A workspace is a branch (host-side, in the mirror and on the
 * git host) plus a container that owns a clone of it under {@code /workspace}; every action script,
 * dependency install, dev server, service and coding-agent command runs inside that container, so
 * nothing untrusted ever touches the host home dir or its credentials.
 *
 * <p><b>This host holds no docker socket and spawns no process for any of it.</b> The sole
 * implementation is {@code containershost/WorkspaceContainers} in the {@code service} module, and
 * every method below is one HTTP call to qits-containers, which owns the daemon. The interface is
 * the seam that survived that cutover unchanged in shape — {@link WorkspaceService} and {@link
 * DaemonProxyTargets} read exactly as they did — and it stays an interface for the reason it always
 * was one: the tests supply a fake and need no container engine.
 *
 * <p><b>One method's signature did change, and the reason is worth having here.</b> {@link #start}
 * used to take a container name alone, because "start what is at this name" is something a docker
 * socket can do. The orchestrator has no such route: a place is started by <em>asking for it
 * again</em>, and asking means presenting the spec, which is derived from the workspace's identity.
 * So {@code start} now takes what {@link #run} takes.
 */
public interface ContainerRuntime {

  /** The exit code and combined stdout/stderr of a finished container command. */
  record ExecResult(int exitCode, String output) {}

  /**
   * A discovered workspace container. {@code running} distinguishes a live container from a
   * present-but-stopped one (a deliberate {@link #stop} or an out-of-band death): the listing
   * includes both (it drives reconcile/adoption, which must see stopped containers), so callers that
   * mean "actually running" — e.g. the workspace runtime status — must filter on this flag rather
   * than mere presence.
   *
   * <p><b>{@code branch} and {@code parent} are null off the orchestrator, and that is honest rather
   * than lossy.</b> They used to be read back from the container's own {@code qits.*} labels, which
   * a {@code docker ps --format} could print; the orchestrator's listing answers names and states
   * and carries no labels. Nothing consumed them — {@link WorkspaceService}'s only reader takes
   * {@code workspaceId} and {@code running} — and the host's own rows are where a branch actually
   * lives, so reading one back out of a container was always the weaker of two answers.
   */
  record ContainerInfo(
      String name, String workspaceId, String branch, String parent, boolean running) {}

  /** The deterministic container name for a workspace — no round-trip needed. */
  String containerName(String workspaceId, String repoId);

  /**
   * Creates and starts the workspace's container, whose process is the image's {@code
   * qits-workspace-daemon} ENTRYPOINT (no {@code sleep infinity} keep-alive), on the shared network
   * with the {@code qits.repository}/{@code qits.workspace}/{@code qits.branch}/{@code qits.parent}
   * labels. Returns the container name. Throws on failure.
   *
   * <p>The container publishes <em>no</em> host ports: qits and every workspace container share one
   * network, so the service web-view proxy reaches a container port by its container name over that
   * network (see {@link #resolveTarget}). That removes the create-time port-publishing constraint
   * entirely — a service can gain a web-view port after its container exists and still be reachable
   * without a recreation.
   */
  String run(String repoId, String workspaceId, Long rowId, String branch, String parent);

  /**
   * Where the qits process connects to reach {@code containerPort} inside {@code container} — the
   * service web-view proxy's origin. On the shared network this is the container's DNS name and the
   * real container port; the test fake maps it to {@code 127.0.0.1}. Null only when the target
   * cannot be resolved at all (e.g. the container is gone), in which case the proxy 502s.
   */
  ProxyOrigin resolveTarget(String container, int containerPort);

  /**
   * Runs a one-shot command inside the container and captures its output.
   *
   * <p><b>No production caller, and the implementation refuses it.</b> {@code exec} is not on the
   * orchestrator's wire and cannot be: running a command inside a container someone else owns is
   * precisely the privilege the cutover gave up. It stays on the interface because the two {@code
   * FakeContainerRuntime}s are its real implementors — they emulate a container as a host git clone
   * and run the whole suite's container-routed paths through here — and deleting it would take the
   * @QuarkusTest strategy with it.
   */
  ExecResult exec(String container, String workdir, Map<String, String> env, String... argv);

  /**
   * {@link #exec} with a per-line tap: {@code onLine} receives each output line as it arrives. The
   * default delivers the lines only after completion; the test fakes keep the default (ordering is
   * preserved either way). Same standing as {@link #exec} — no production caller.
   */
  default ExecResult exec(
      String container,
      String workdir,
      Map<String, String> env,
      java.util.function.Consumer<String> onLine,
      String... argv) {
    ExecResult result = exec(container, workdir, env, argv);
    if (onLine != null && !result.output().isEmpty()) {
      result.output().lines().forEach(onLine);
    }
    return result;
  }

  /**
   * {@link #run} with a per-line tap on the launch. The default ignores the tap (the capturing run
   * embeds its output in the failure exception).
   *
   * <p>There is much less to tap than there was: the tap used to carry a {@code docker pull}'s
   * progress and a {@code docker run}'s output line by line. The pull is the orchestrator's now
   * (pull policy MISSING) and the launch is one HTTP call, so what reaches the segment is a sentence
   * about what was asked for and what came back.
   */
  default String run(
      String repoId,
      String workspaceId,
      Long rowId,
      String branch,
      String parent,
      java.util.function.Consumer<String> onLine) {
    return run(repoId, workspaceId, rowId, branch, parent);
  }

  /**
   * {@link #run} with the workspace's subject entity id (qits-projects' qualified id, e.g. {@code
   * qits-614}) carried onto the daemon's env alongside {@code branch}/{@code parent} — see {@code
   * Workspace.entityId}. The default ignores it and delegates to the tapped {@link #run} above, so
   * nothing besides the real adapter has to know this overload exists.
   */
  default String run(
      String repoId,
      String workspaceId,
      Long rowId,
      String branch,
      String parent,
      String entityId,
      java.util.function.Consumer<String> onLine) {
    return run(repoId, workspaceId, rowId, branch, parent, onLine);
  }

  /**
   * The exec argv <em>prefix</em> up to and including the container name — the caller appends the
   * command to run. Same standing as {@link #exec}: no production caller, refused by the
   * implementation, kept because the fakes are its real implementors.
   */
  List<String> execArgv(String container, boolean tty, String workdir, Map<String, String> env);

  /** Whether a container with this name exists (running or stopped). */
  boolean exists(String container);

  /**
   * Whether a container with this name exists <em>and</em> is currently running — the live-container
   * guard {@link #exists} can't give, since a place that exited still has a row. A host restart
   * leaves qits containers present but stopped, so the "already provisioned?" check must key off run
   * state, not mere presence.
   */
  boolean isRunning(String container);

  /**
   * Brings a present-but-stopped workspace back up — the recovery for a container that died
   * out-of-band, or that a deliberate {@link #stop} paused. Lossless: the container comes back with
   * its writable layer and its {@code /workspace} checkout, unpushed commits included.
   *
   * <p><b>It takes the workspace's identity rather than a container name.</b> The orchestrator
   * exposes no start route — a stopped place is started by asking for it again with its spec, and
   * the spec is derived from the workspace — so the arguments are {@link #run}'s. Throws on failure.
   */
  void start(String repoId, String workspaceId, Long rowId, String branch, String parent);

  /**
   * {@link #start} with the subject entity id carried the same way {@link #run}'s tapped overload
   * carries it — see that overload's javadoc. The default ignores it and delegates to {@link
   * #start} above.
   */
  default void start(
      String repoId, String workspaceId, Long rowId, String branch, String parent, String entityId) {
    start(repoId, workspaceId, rowId, branch, parent);
  }

  /**
   * Gracefully stops a running container (SIGTERM + grace) <em>without</em> removing it, so the
   * container and its {@code /workspace} clone survive for a later {@link #start}. This is the pause
   * half of a deliberate workspace stop: unlike {@link #rm} it preserves the working tree
   * (uncommitted/untracked files and unpushed commits alike). Best-effort, never throws.
   */
  void stop(String container);

  /**
   * Force-removes the container; best-effort, never throws. Destroys the container's writable layer
   * — use only when the work is being discarded, not to pause a workspace (that is {@link #stop}).
   * It deliberately does <em>not</em> take the per-workspace volume with it: that is {@link
   * #removeWorkspaceVolume}'s, so a recreate reattaches the same checkout.
   */
  void rm(String container);

  /**
   * Says the container is still being used — the keepalive an idle-stop policy is measured against.
   *
   * <p><b>It changes nothing about the container</b> and means nothing at all to a container whose
   * lifetime is EXPLICIT, which is every workspace but the editor's. So it is safe to call for any
   * workspace and the caller does not have to know which kind it has; what it costs when it is
   * pointless is one request.
   *
   * <p>Best-effort and never throws, for the reason {@link #stop} and {@link #rm} are: a keepalive
   * that failed is a container that may be swept, which is recoverable — the next ensure starts it
   * back up in place with its volume intact — while a keepalive that threw would fail whatever the
   * caller was actually doing. A dropped one is the designed behaviour.
   */
  void touch(String container);

  /**
   * Restarts the container. <b>No production caller</b>, and refused by the implementation — the
   * sledgehammer for a stuck process group lost its meaning when the process group stopped being
   * this host's to signal.
   */
  void restart(String container);

  /** All workspace containers for a repository. */
  List<ContainerInfo> listWorkspaceContainers(String repoId);

  // --- Per-workspace /workspace volumes -------------------------------------------------------
  //
  // A workspace's checkout lives on a per-workspace named volume mounted at /workspace (not the
  // container's ephemeral writable layer), so it SURVIVES container recreation (image update,
  // crash, prune, host restart) and is reattached on the next provision — the workspace-daemon then
  // skips its self-clone on the already-populated volume. Gated by qits.workspace.persist-workspace;
  // when off, /workspace reverts to the ephemeral layer and none of these are exercised. See
  // docs/epics/qits-workspaces/features/2026-07-25_persistent-workspace-volume.md.
  //
  // The volume is CLAIMED BY THE CONTAINER'S ROW on the orchestrator (a VolumeMount), which is what
  // makes it created with the container and removable with it. It is still removed through the
  // standalone door below, deliberately: the volume outlives the container on every path that
  // reclaims a workspace, and a delete that took it along would take it at the wrong moment.

  /** A discovered per-workspace volume. */
  record VolumeInfo(
      String name, String projectId, String repoId, String workspaceId, String branch) {}

  /** The deterministic per-workspace volume name (prefix + {@code workspaceId}); no round-trip. */
  String workspaceVolumeName(String workspaceId);

  /**
   * Create-if-absent the per-workspace {@code /workspace} volume; idempotent. {@link #run} no longer
   * has to call it — the volume rides the container's spec as a claimed mount and the orchestrator
   * creates it as part of the same ensure — so this is the door for the paths that need the volume
   * to exist without a container. Best-effort: a failure just logs.
   */
  void ensureWorkspaceVolume(String repoId, String workspaceId, String branch, String parent);

  /**
   * Removes the per-workspace volume; best-effort, never throws. The container referencing it must
   * be {@link #rm}'d first — the orchestrator's own delete succeeds either way, but docker refuses
   * to remove a volume an existing container mounts, and that refusal is reported rather than
   * retried. This is the one destructive step that drops a persisted checkout — used only where the
   * work is being discarded (delete-container reclaim, branch discard/abandon, repo delete, GC).
   */
  void removeWorkspaceVolume(String workspaceId);

  /**
   * All qits-managed per-workspace volumes. <b>No production caller</b>, and refused by the
   * implementation: the orchestrator answers about volumes one name at a time, and the
   * dangling-volume reconcile this fed was a host-wide sweep of the kind the cutover removed.
   */
  List<VolumeInfo> listWorkspaceVolumes();

  // --- Service sessions ------------------------------------------------------------------------
  //
  // Deliberately absent. Services (dev servers) are spawned, supervised, restarted and stopped by
  // the in-container workspace-daemon, which owns the process and pushes every lifecycle transition
  // home over the control socket; ServiceSupervisor is only the host-side projection of that, and
  // WorkspaceServiceDriver the outbound half. There is no host-execution fallback: a workspace with
  // no live daemon cannot run a service, and that is the honest state.

}
