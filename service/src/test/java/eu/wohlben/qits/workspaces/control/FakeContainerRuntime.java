package eu.wohlben.qits.workspaces.control;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Test double for {@link ContainerRuntime} that emulates a per-workspace container as a local git
 * clone on the host, so the whole suite exercises the container-routed code paths without a real
 * docker (or the running {@code /git} server). A container is a clone at the old host workspace
 * path ({@code <data-dir>/<repoId>/workspaces/<workspaceId>}); {@code exec} runs the command there
 * via {@code env -C}, rewriting the {@code http://…/git/<repoId>} clone URL to the on-disk bare
 * origin and {@code /workspace} to the workspace dir. Because {@code exec} runs real host
 * processes, the {@code setsid}-based process-group termination the registry relies on works
 * end-to-end too.
 *
 * <p>Like real docker, operations against an <em>unknown</em> container (never {@code run}, or
 * already {@code rm}'d) fail instead of falling through to the host: {@code exec} returns a
 * non-zero {@code ExecResult}, {@code execArgv} yields an argv that fails when spawned, and {@code
 * startService} throws. This is what catches a use-site that forgot {@code ensureContainer} now
 * that workspace creation no longer provisions eagerly.
 *
 * <p>Stands in for the container-level env {@link WorkspaceContainerFactory} sets at {@code docker
 * run} by applying {@link GitIdentity#envMap()} under each call's own env (per-call entries win,
 * mirroring a per-exec {@code -e} overriding container-creation env) — so commits made "in the
 * container" carry the configured identity exactly like in a real container.
 *
 * <p>Replaces {@link eu.wohlben.qits.workspaces.control.ContainerRuntime}'s production adapter globally in this module's {@code @QuarkusTest}s via {@link
 * Mock}. Real-docker behavior is covered separately by integration tests behind {@code skipITs}.
 */
@Mock
@ApplicationScoped
public class FakeContainerRuntime implements ContainerRuntime {

  private static final Pattern CLONE_URL = Pattern.compile("^https?://[^/]+/git/([^/]+)$");

  /** The name-addressed scheme: {@code http://host/git/<projectId>/<name>[.git]}. */
  private static final Pattern CLONE_URL_NAMED =
      Pattern.compile("^https?://[^/]+/git/([^/]+)/([^/]+?)(?:\\.git)?$");

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @ConfigProperty(name = "qits.workspace.persist-workspace", defaultValue = "true")
  boolean persistWorkspace;

  @ConfigProperty(name = "qits.workspace.workspace-volume-prefix", defaultValue = "qits_workspace_")
  String workspaceVolumePrefix;

  @Inject GitIdentity gitIdentity;

  // The fake serves submodules over LOCAL paths (the name farm below), which git blocks by default
  // (CVE-2022-39253); the real container fetches over HTTP and needs no such relaxation. Enable the
  // file transport via git's env-config so it applies to every git invocation without touching
  // argv.
  private static final Map<String, String> FAKE_GIT_CONFIG =
      Map.of(
          "GIT_CONFIG_COUNT", "1",
          "GIT_CONFIG_KEY_0", "protocol.file.allow",
          "GIT_CONFIG_VALUE_0", "always");

  private record Info(String repoId, String workspaceId, String branch, String parent, Path dir) {}

  private final Map<String, Info> byName = new ConcurrentHashMap<>();
  // Containers present but not running — the fake's stand-in for a host/docker restart's `Exited`
  // state. `run`/`start` clear membership, `rm` drops it, and the `markExited` test hook adds to
  // it.
  private final Set<String> stopped = ConcurrentHashMap.newKeySet();

  // Per-workspace /workspace volumes, keyed by workspaceId. The "volume content" is the same host
  // clone dir the container uses as /workspace; registering a volume decouples that dir's lifetime
  // from container membership, so an incidental `rm` KEEPS it (a real named volume survives) while
  // `removeWorkspaceVolume` is the one path that deletes it. Emulates persist-workspace=true.
  private record Volume(
      String repoId, String workspaceId, String branch, String parent, Path dir) {}

  private final Map<String, Volume> volumes = new ConcurrentHashMap<>();

  private Path workspaceDir(String repoId, String workspaceId) {
    return Path.of(dataDir, repoId, "workspaces", workspaceId).toAbsolutePath();
  }

  @Override
  public String containerName(String workspaceId, String repoId) {
    String shortRepo = repoId.length() > 8 ? repoId.substring(0, 8) : repoId;
    return "qits-ws-" + workspaceId + "-" + shortRepo;
  }

  @Override
  public String run(String repoId, String workspaceId, Long rowId, String branch, String parent) {
    String name = containerName(workspaceId, repoId);
    Path dir = workspaceDir(repoId, workspaceId);
    try {
      Files.createDirectories(dir.getParent());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    // Mirror the adapter's run: create-if-absent the per-workspace volume before "mounting" it, so
    // a subsequent incidental `rm` preserves the checkout on the reattached volume.
    if (persistWorkspace) {
      ensureWorkspaceVolume(repoId, workspaceId, branch, parent);
    }
    byName.put(name, new Info(repoId, workspaceId, branch, parent, dir));
    stopped.remove(name);
    return name;
  }

  /**
   * Fake containers run as host processes binding real host ports, so the proxy target is simply
   * {@code 127.0.0.1} + the port the daemon bound — the host-clone analogue of reaching a container
   * by its DNS name on the shared network. Any known container resolves (no create-time port set).
   */
  @Override
  public ProxyOrigin resolveTarget(String container, int containerPort) {
    if (!byName.containsKey(container)) {
      return null;
    }
    return new ProxyOrigin("127.0.0.1", containerPort);
  }

  @Override
  public ExecResult exec(
      String container, String workdir, Map<String, String> env, String... argv) {
    Info info = byName.get(container);
    if (info == null) {
      // Mirror docker: exec against an unknown container fails, it doesn't run on the host. This
      // is what turns a use-site that forgot ensureContainer into a test failure instead of a
      // silent bad rewrite (literal /workspace on the host).
      return new ExecResult(1, "Error response from daemon: No such container: " + container);
    }
    // The agent auth check (AgentAuthStatus) must be deterministic here, not depend on the host's
    // real `claude` login: report signed in so chat launches take the chat path. Tests that
    // exercise the not-signed-in login redirect override AgentAuthStatus itself.
    if (argv.length >= 3
        && "claude".equals(argv[0])
        && "auth".equals(argv[1])
        && "status".equals(argv[2])) {
      return new ExecResult(0, "{\"loggedIn\": true, \"authMethod\": \"claudeai\"}");
    }
    Path dir = info.dir();
    List<String> cmd = new ArrayList<>();
    cmd.add("env");
    String wd = rewriteWorkdir(workdir, dir);
    if (wd != null) {
      cmd.add("-C");
      cmd.add(wd);
    }
    // Container-level identity env first, per-call env after — later `env K=V` assignments win,
    // mirroring docker where a per-exec -e overrides container-creation env.
    gitIdentity.envMap().forEach((k, v) -> cmd.add(k + "=" + v));
    FAKE_GIT_CONFIG.forEach((k, v) -> cmd.add(k + "=" + v));
    if (env != null) {
      env.forEach((k, v) -> cmd.add(k + "=" + (v == null ? "" : v)));
    }
    for (String a : argv) {
      cmd.add(rewriteArg(a, dir));
    }
    return runCapturing(cmd);
  }

  @Override
  public List<String> execArgv(
      String container, boolean tty, String workdir, Map<String, String> env) {
    Info info = byName.get(container);
    if (info == null) {
      // Mirror docker: the real argv is spawned later and fails then, so hand back an argv that
      // fails loudly at spawn time rather than one running on the host with a literal /workspace.
      return List.of("sh", "-c", "echo 'No such container: " + container + "' >&2; exit 1");
    }
    Path dir = info.dir();
    List<String> argv = new ArrayList<>();
    argv.add("env");
    String wd = rewriteWorkdir(workdir, dir);
    if (wd != null) {
      argv.add("-C");
      argv.add(wd);
    }
    // Container-level identity env first, per-call env after (later assignments win, like docker).
    gitIdentity.envMap().forEach((k, v) -> argv.add(k + "=" + v));
    FAKE_GIT_CONFIG.forEach((k, v) -> argv.add(k + "=" + v));
    if (env != null) {
      env.forEach((k, v) -> argv.add(k + "=" + (v == null ? "" : v)));
    }
    return argv;
  }

  @Override
  public boolean exists(String container) {
    return byName.containsKey(container);
  }

  @Override
  public boolean isRunning(String container) {
    return byName.containsKey(container) && !stopped.contains(container);
  }

  @Override
  public void start(String repoId, String workspaceId, Long rowId, String branch, String parent) {
    String container = containerName(workspaceId, repoId);
    if (!byName.containsKey(container)) {
      throw new IllegalStateException("No such container: " + container);
    }
    stopped.remove(container);
  }

  /**
   * Test hook: mark a present container {@code Exited} (a host/docker-restart stand-in) without
   * touching its {@code /workspace} clone, so a subsequent {@link #start} is verifiably lossless.
   */
  public void markExited(String container) {
    if (byName.containsKey(container)) {
      stopped.add(container);
    }
  }

  @Override
  public void stop(String container) {
    // Pause in place: keep the container present (in byName) and its /workspace clone on disk, just
    // mark it Exited — so a subsequent start() is verifiably lossless, mirroring `docker stop`.
    if (byName.containsKey(container)) {
      stopped.add(container);
    }
  }

  /**
   * Every keepalive this fake was asked for, newest last. The count is the assertion the debounce is
   * about — "one touch per interval, not one per request" is a statement about how many arrived.
   */
  private final java.util.List<String> touches =
      java.util.Collections.synchronizedList(new java.util.ArrayList<>());

  @Override
  public void touch(String container) {
    touches.add(container);
  }

  /** How many keepalives reached this container. */
  public int touchCount(String container) {
    synchronized (touches) {
      return (int) touches.stream().filter(container::equals).count();
    }
  }

  /** Forget the recorded keepalives — a test that counts them starts from zero. */
  public void clearTouches() {
    touches.clear();
  }

  @Override
  public void rm(String container) {
    stopped.remove(container);
    Info info = byName.remove(container);
    if (info == null) {
      return;
    }
    // A persistent /workspace volume survives container removal — the checkout is reattached on the
    // next run (recreation is lossless). Only when NO volume backs this workspace (persist off, or
    // a raw pre-volume container) does rm reclaim the dir, mirroring docker rm destroying the
    // writable layer. removeWorkspaceVolume is the sole path that deletes a persisted dir.
    if (volumes.containsKey(info.workspaceId())) {
      return;
    }
    deleteRecursively(info.dir());
  }

  private void deleteRecursively(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      paths
          .sorted((a, b) -> b.getNameCount() - a.getNameCount())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (Exception ignored) {
                  // best effort
                }
              });
    } catch (Exception ignored) {
      // best effort
    }
  }

  @Override
  public void restart(String container) {
    // no-op: nothing to restart for a host-clone stand-in
  }

  @Override
  public List<ContainerInfo> listWorkspaceContainers(String repoId) {
    List<ContainerInfo> infos = new ArrayList<>();
    for (Info info : byName.values()) {
      if (info.repoId().equals(repoId)) {
        String name = containerName(info.workspaceId(), repoId);
        infos.add(
            new ContainerInfo(
                name, info.workspaceId(), info.branch(), info.parent(), !stopped.contains(name)));
      }
    }
    return infos;
  }

  // --- Per-workspace /workspace volumes -------------------------------------------------------

  @Override
  public String workspaceVolumeName(String workspaceId) {
    return workspaceVolumePrefix + workspaceId;
  }

  @Override
  public void ensureWorkspaceVolume(
      String repoId, String workspaceId, String branch, String parent) {
    // Idempotent: register (or refresh) the volume and ensure its backing dir exists. The dir is
    // the
    // same host clone the container uses as /workspace, so a checkout written there persists across
    // container rm exactly like a real named volume.
    Path dir = workspaceDir(repoId, workspaceId);
    try {
      Files.createDirectories(dir);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    volumes.put(workspaceId, new Volume(repoId, workspaceId, branch, parent, dir));
  }

  @Override
  public void removeWorkspaceVolume(String workspaceId) {
    // The one destructive path: drop the volume AND its dir, even after the container is gone (the
    // GC/discard case). Best-effort, mirroring `docker volume rm`.
    Volume volume = volumes.remove(workspaceId);
    if (volume != null) {
      deleteRecursively(volume.dir());
    }
  }

  @Override
  public List<VolumeInfo> listWorkspaceVolumes() {
    List<VolumeInfo> infos = new ArrayList<>();
    for (Volume v : volumes.values()) {
      infos.add(
          new VolumeInfo(
              workspaceVolumeName(v.workspaceId()), "", v.repoId(), v.workspaceId(), v.branch()));
    }
    return infos;
  }

  // --- Service sessions: emulate the tmux model with a plain detached (setsid) host process
  // --------
  //
  // No tmux on the test host: a service session is a setsid'd shell (new session => group-killable
  // and
  // detached, so it survives like the real tmux session) that runs the script with output
  // redirected
  // to a host logfile and its exit code recorded. State lives on disk (pidfile/exitfile), so a
  // fresh
  // supervisor can reconcile a still-alive daemon exactly like it reads back tmux has-session.

  private Path serviceRunDir() {
    return Path.of(dataDir, ".qits-services").toAbsolutePath();
  }

  private String rewriteWorkdir(String workdir, Path dir) {
    if (workdir == null) {
      return null;
    }
    if (workdir.equals("/workspace") && dir != null) {
      return dir.toString();
    }
    return workdir;
  }

  /** Rewrite container-side references to their host equivalents. */
  private String rewriteArg(String arg, Path dir) {
    if ("/workspace".equals(arg) && dir != null) {
      return dir.toString();
    }
    // Name-addressed clone urls (/git/<projectId>/<name>) are not resolved here: the alias table
    // that backed the on-disk name farm belongs to the repositories context, and the test that
    // needed it (recursive submodule materialization) moved to the daemon with the behaviour it
    // covered. Id-addressed urls below are the daemon's own documented fallback.
    Matcher m = CLONE_URL.matcher(arg);
    if (m.matches()) {
      return Path.of(dataDir, m.group(1), "origin").toAbsolutePath().toString();
    }
    return arg;
  }


  private ExecResult runCapturing(List<String> command) {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    try {
      Process p = pb.start();
      String output;
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
        output = reader.lines().collect(Collectors.joining("\n"));
      }
      int exitCode = p.waitFor();
      return new ExecResult(exitCode, output);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ExecResult(-1, "interrupted");
    } catch (Exception e) {
      return new ExecResult(-1, e.getMessage());
    }
  }
}
