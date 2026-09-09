package eu.wohlben.qits.workspaces.daemonhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspaces.containershost.EditorKeepalive;
import eu.wohlben.qits.workspaces.control.AgentActivityState;
import eu.wohlben.qits.workspaces.control.AgentSessionReporter;
import eu.wohlben.qits.workspaces.control.EditorLifecycle;
import eu.wohlben.qits.workspaces.control.ProvisionResult;
import eu.wohlben.qits.workspaces.control.QitsConfig;
import eu.wohlben.qits.workspaces.control.WorkspaceAgentActivity;
import eu.wohlben.qits.workspaces.control.WorkspaceBootstrapDriver;
import eu.wohlben.qits.workspaces.control.WorkspaceConfigReader;
import eu.wohlben.qits.workspaces.control.WorkspaceConfigView;
import eu.wohlben.qits.workspaces.control.WorkspaceDaemonInfo;
import eu.wohlben.qits.workspaces.control.WorkspaceDaemonLiveness;
import eu.wohlben.qits.workspaces.control.WorkspaceDaemonProvisioner;
import eu.wohlben.qits.workspaces.control.WorkspaceEditorState;
import eu.wohlben.qits.workspaces.control.WorkspaceGitStatus;
import eu.wohlben.qits.workspaces.control.WorkspaceGitSync;
import eu.wohlben.qits.workspaces.control.WorkspaceServiceDriver;
import eu.wohlben.qits.workspaces.control.WorkspaceChangeHint;
import eu.wohlben.qits.workspaces.control.WorkspaceChangePublisher;
import eu.wohlben.qits.workspacedaemon.protocol.Ack;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.BootstrapOutcome;
import eu.wohlben.qits.workspacedaemon.protocol.BootstrapStep;
import eu.wohlben.qits.workspacedaemon.protocol.Bootstrapped;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.CommandExit;
import eu.wohlben.qits.workspacedaemon.protocol.ConfigView;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Describe;
import eu.wohlben.qits.workspacedaemon.protocol.DescribeConfig;
import eu.wohlben.qits.workspacedaemon.protocol.EditorState;
import eu.wohlben.qits.workspacedaemon.protocol.GitStatus;
import eu.wohlben.qits.workspacedaemon.protocol.Heartbeat;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.workspacedaemon.protocol.Provisioned;
import eu.wohlben.qits.workspacedaemon.protocol.OpenStream;
import eu.wohlben.qits.workspacedaemon.protocol.PullBranch;
import eu.wohlben.qits.workspacedaemon.protocol.RunBootstrap;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.ServiceTransition;
import eu.wohlben.qits.workspacedaemon.protocol.SignalService;
import eu.wohlben.qits.workspacedaemon.protocol.StartService;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import eu.wohlben.qits.workspacedaemon.protocol.StreamTarget;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceChanged;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceInfo;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The backend's live-{@code workspace-daemon} directory (docs/epics/qits-workspace-daemon/): tracks
 * which workspaces have an open control socket, keyed by {@code workspaceId}, and routes correlated
 * request/reply traffic over it. It is the in-JVM half of the control plane — {@link
 * DaemonControlSocket} owns the WebSocket lifecycle and forwards frames here.
 *
 * <p>It implements framework-free {@code domain} SPIs so {@code WorkspaceService} (and other read
 * paths) can reach across the module boundary without depending on websockets: {@link
 * WorkspaceDaemonLiveness} (observational), {@link WorkspaceDaemonProvisioner} (awaits the daemon's
 * <b>autonomous self-provision</b> — clone + submodules on boot — streaming its output to the
 * {@code clone} process segment), {@link WorkspaceConfigReader} (Part 2 — reads the workspace's
 * in-container {@code .qits-config.yml} on demand, so config becomes the branch's config), and
 * {@link WorkspaceBootstrapDriver} (Part 3 — awaits the daemon's autonomous boot-time bootstrap
 * chain and re-triggers it on demand, streaming each step's progress to the host).
 *
 * <p>{@link #runCommand}/{@link #describe} remain Part-1 demonstration seams (backend → {@code
 * workspace-daemon} → backend); no existing {@code docker exec} path routes through them yet.
 */
@ApplicationScoped
public class WorkspaceDaemonRegistry
    implements WorkspaceDaemonLiveness,
        WorkspaceDaemonProvisioner,
        WorkspaceConfigReader,
        WorkspaceBootstrapDriver,
        WorkspaceServiceDriver,
        WorkspaceGitStatus,
        WorkspaceAgentActivity,
        WorkspaceEditorState,
        WorkspaceDaemonInfo,
        WorkspaceGitSync {

  private static final Logger LOG = Logger.getLogger(WorkspaceDaemonRegistry.class);

  @Inject DaemonMessageCodec codec;

  @Inject ObjectMapper objectMapper;

  @Inject WorkspaceChangePublisher changePublisher;

  /**
   * The session-lineage sink for the {@code SessionStart} activity hook — the behaviour-preserving
   * half of retiring the old direct-to-host {@code /agent-session} hook. Optional, because the
   * lineage belongs to the commands context: absent simply drops the report, leaving the
   * agent-activity rollup below untouched. Guarded at the call site either way, so a bad or late
   * report can't tear down the control socket.
   */
  @Inject Instance<AgentSessionReporter> agentSessions;

  /**
   * The reverse tunnel's host end. An {@code Instance<>} to break the cycle — {@link
   * WorkspaceTunnels} asks this registry which daemons can serve a stream, and this registry tells
   * it when one goes away.
   */
  @Inject Instance<WorkspaceTunnels> tunnels;

  /**
   * What carries this container's harness capability report to qits-projects' catalogue, fired on
   * {@link Hello} below. An {@code Instance<>} for the same reason {@link #tunnels} is one — the
   * relay reads through the tunnel, which reads this registry — and because it must be possible for
   * it to be absent without this class caring: nothing on the control plane depends on it.
   */
  @Inject Instance<WorkspaceCapabilityRelay> capabilityRelay;

  /**
   * The editor's keepalive. A coding agent working in a workspace is that workspace being used, and
   * the editor's container is the one with an idle-stop lifetime — so an unattended agent session
   * must hold it open exactly as a person's open tab does. Reporting here rather than from a timer
   * is what makes that true of the agent's OWN activity instead of of the socket merely being open:
   * a daemon that has connected and is doing nothing is idle, which is what the sweep is for.
   *
   * <p>It is debounced on its own side and it does nothing at all while the idle-stop switch is
   * unset, so this call costs one map operation on the socket thread in the shipped configuration.
   */
  @Inject EditorKeepalive editorKeepalive;

  /**
   * Last working-tree cleanliness each live daemon reported ({@link GitStatus}). In-memory only —
   * known while the daemon is connected (container RUNNING), cleared on {@link #unregister}, and
   * re-reported by the daemon on reconnect. Surfaced through {@link WorkspaceGitStatus#isClean}.
   */
  private final ConcurrentHashMap<Long, Boolean> gitClean = new ConcurrentHashMap<>();

  /**
   * Last commit each live daemon reported on the same {@link GitStatus} frame as {@link #gitClean}.
   * Same lifetime and same eviction — in-memory only, dropped on {@link #unregister}, re-reported
   * on reconnect. Surfaced through {@link WorkspaceGitStatus#head}, which the host compares against
   * the origin's ref instead of running {@code git rev-parse} inside the container.
   */
  private final ConcurrentHashMap<Long, String> gitHead = new ConcurrentHashMap<>();

  /**
   * Last coding-agent activity each live daemon reported ({@link AgentActivity}), keyed by {@code
   * commandId} (multiple agents can run per workspace). In-memory only — same lifecycle as {@link
   * #gitClean}: populated while the daemon is connected, evicted on {@link #unregister},
   * re-reported on reconnect. Surfaced as a per-workspace rollup through {@link
   * WorkspaceAgentActivity#activityFor}.
   *
   * <p><b>{@code ENDED} is kept here, not evicted.</b> It used to be dropped the instant it
   * arrived, which made {@code ENDED} a state the host could never report — and that is precisely
   * the state the agent-activity bar is ordered around: a session that has just stopped belongs at
   * the front, because it is the workspace waiting on your next prompt. Evicting it made that
   * workspace vanish at exactly the moment it mattered most. It expires on {@link #endedTtlMs}
   * instead, so the bar survives a page reload without keeping yesterday's finished run on screen.
   */
  private final ConcurrentHashMap<String, ActivityEntry> agentActivity = new ConcurrentHashMap<>();

  /**
   * Last web-editor state each live daemon reported ({@link EditorState}), per workspace row id.
   * The same lifetime and the same eviction as {@link #gitClean}: in-memory only, populated while
   * the daemon is connected, dropped on {@link #unregister}, re-reported on reconnect. Surfaced
   * through {@link WorkspaceEditorState#editorStateFor}, which {@code EditorService} and {@code
   * EditorProxyRoute} both gate on.
   *
   * <p><b>An absent entry is "nothing reported", never "no editor".</b> The daemon sends the frame
   * only where it supervises an editor at all, so absence covers a plain workspace, a container
   * that is not up, and an editor whose first frame has not arrived — three cases a caller turns
   * into the same "not ready", which is the answer each of them deserves. That is also why {@code
   * ENDED} is <em>kept</em> rather than evicted, unlike the way agent activity used to treat its
   * own terminal: ENDED is what lets a splash stop waiting and say so.
   */
  private final ConcurrentHashMap<Long, EditorLifecycle> editorStates = new ConcurrentHashMap<>();

  /**
   * One tracked agent command's activity, plus the workspace it belongs to (for the rollup) and
   * when it was recorded (only {@code ENDED} expires, but every entry carries the stamp so the
   * check needs no second map).
   */
  private record ActivityEntry(Long workspaceId, AgentActivityState state, long recordedAtMillis) {}

  /**
   * How long an {@code ENDED} session stays in the rollup. Thirty minutes: long enough to survive a
   * page reload, a tab switch and a coffee break — which is the whole point of keeping the state at
   * all, since the alternative (deriving "recently ended" client-side from a transition to null)
   * loses it on exactly the reload someone does after stepping away. Short enough that a workspace
   * whose agent finished this morning is not still claiming a slot in the bar this afternoon.
   *
   * <p>A live report always wins: a resume writes {@code BUSY} over the {@code ENDED} entry under
   * the same {@code commandId}, and a fresh run writes a new key. So the TTL only ever governs how
   * long a session that stayed finished is still worth mentioning.
   */
  @ConfigProperty(name = "qits.workspace.agent-activity.ended-ttl-ms", defaultValue = "1800000")
  long endedTtlMs;

  /** How long a {@link #readConfig} waits for the live daemon's {@link ConfigView} reply. */
  @ConfigProperty(name = "qits.workspace.config.describe-timeout-ms", defaultValue = "10000")
  long configDescribeTimeoutMs;

  private final ConcurrentHashMap<Long, DaemonConnection> clients = new ConcurrentHashMap<>();

  /**
   * In-flight autonomous self-provisions, keyed by {@code workspaceId} — <b>on the registry, not on
   * a {@link DaemonConnection}</b>, because a provision (a real clone) outlives a socket bounce:
   * the daemon keeps cloning across a reconnect and reports {@code Provisioned} on whichever
   * connection is up when it finishes. A connection-scoped slot would be orphaned by {@link
   * #unregister}.
   */
  private final ConcurrentHashMap<Long, PendingProvision> provisions = new ConcurrentHashMap<>();

  /**
   * In-flight bootstrap chains, keyed by {@code workspaceId} — like {@link #provisions}, on the
   * registry (a chain, a long {@code mvn install}, outlives a socket bounce). Unlike provisioning,
   * the boot-time chain is <b>autonomous</b>: the daemon runs it right after the self-clone, so it
   * can begin — and a fast/empty chain can finish — before the host's async observer registers its
   * await. So a {@link PendingBootstrap} buffers step events until a sink registers, and {@link
   * #completeBootstrap} retains the terminal (creating the slot if absent) rather than dropping it.
   * {@link #awaitProvision} clears any stale slot at the start of each provision cycle so a boot
   * awaiter never picks up a previous cycle's retained terminal.
   */
  private final ConcurrentHashMap<Long, PendingBootstrap> bootstraps = new ConcurrentHashMap<>();

  /**
   * Host coordinators subscribed to service (dev-server) lifecycle events (Part 4). The daemon owns
   * the process lifecycle and streams every transition; the host {@code ServiceSupervisor} projects
   * them. Registered once at host startup, so it's a small, stable list — {@code
   * CopyOnWriteArrayList} makes the iteration lock-free.
   */
  private final CopyOnWriteArrayList<WorkspaceServiceDriver.ServiceEventSink> serviceSinks =
      new CopyOnWriteArrayList<>();

  /**
   * Persistent bootstrap-outcome recorders ({@link WorkspaceBootstrapDriver#subscribe}), fed every
   * {@link BootstrapOutcome} frame — awaited or not. This is how a chain the daemon ran on its own
   * (its HTTP run verb, through the container proxy) still writes host rows.
   */
  private final CopyOnWriteArrayList<WorkspaceBootstrapDriver.OutcomeSink> outcomeSinks =
      new CopyOnWriteArrayList<>();

  /**
   * One ordered thread for everything fanned out to subscribed sinks — service transitions, service
   * output, bootstrap-outcome recording. <b>Never the socket thread.</b> websockets-next processes
   * one inbound frame per connection at a time, so a sink that blocks (the supervisor monitor, a DB
   * write) parks the whole pipeline — including the {@code ConfigView} reply a config read on
   * another thread is awaiting, which starved those reads to timeout (measured, D1). Single-threaded
   * so one workspace's transitions stay in arrival order.
   */
  private final java.util.concurrent.ExecutorService sinkDispatch =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "daemon-sink-dispatch");
            thread.setDaemon(true);
            return thread;
          });

  @jakarta.annotation.PreDestroy
  void shutdownDispatch() {
    sinkDispatch.shutdownNow();
  }

  /** The label a connected daemon announced for itself, or null when it has not said Hello yet. */
  private static String labelOf(DaemonConnection client) {
    return client == null ? null : client.label;
  }

  /** The terminal outcome of a {@link #runCommand} round-trip. */
  public record CommandResult(int exitCode, String stdout, String stderr) {}

  /** Register a freshly-connected client, replacing any stale entry for the same workspace. */
  public void register(Long workspaceId, WebSocketConnection connection) {
    clients.put(workspaceId, new DaemonConnection(connection));
    LOG.debugf(
        "workspace-daemon connected for workspace %s (connection %s)",
        workspaceId, connection.id());
  }

  /**
   * Drop the client for {@code workspaceId}, but only if it is still the given connection — a
   * reconnect that registered a newer socket must not be evicted by the old one's late close.
   */
  public void unregister(Long workspaceId, WebSocketConnection connection) {
    clients.computeIfPresent(
        workspaceId,
        (id, existing) -> existing.connection.id().equals(connection.id()) ? null : existing);
    // The daemon is gone: its cached working-tree status is unknown until it reconnects and
    // re-reports (RUNNING-only semantics; the UI shows no badge in the meantime).
    gitClean.remove(workspaceId);
    gitHead.remove(workspaceId);
    // Likewise drop every tracked agent activity for this workspace (re-reported on reconnect).
    agentActivity.values().removeIf(entry -> entry.workspaceId().equals(workspaceId));
    // And the editor's state. A disconnect means NOTHING IS KNOWN — not that the editor ended: the
    // daemon re-reports on every connect, and a retained RUNNING would send the proxy at a listener
    // whose container may have gone away underneath it.
    editorStates.remove(workspaceId);
    // Pending tunnel nonces are waiting on a daemon that is no longer there; live tunnels are NOT
    // torn down, deliberately — each stream is its own TCP connection, so an open terminal survives
    // a control-socket reconnect, which is the whole reason these calls do not ride that socket.
    if (tunnels.isResolvable()) {
      tunnels.get().onDaemonGone(workspaceId);
    }
    LOG.debugf(
        "workspace-daemon disconnected for workspace %s (connection %s)",
        workspaceId, connection.id());
  }

  /**
   * Ask a daemon to dial back and serve one stream to one of its loopback listeners — the reverse
   * tunnel's only outbound message.
   *
   * <p>Sent <b>without awaiting</b>, unlike every other send here: this is called from a {@code
   * NetServer} connect handler, which runs on an event loop, and {@code sendTextAndAwait} would be
   * rejected by Mutiny's blocking guard there. A failure is logged and nothing else — the parked
   * socket's own TTL closes it, so a lost {@code OpenStream} degrades to a request that fails
   * rather than one that hangs.
   *
   * <p>The target is a <b>name</b> and never a port: the host does not learn — and must not state —
   * an address inside the container, so it says <em>what</em> it wants and the daemon resolves that
   * against its own allow-list. {@link WorkspaceTunnels} is what keys the caller on a capability
   * version high enough to understand the name it is about to be sent.
   */
  void requestStream(Long workspaceId, String nonce, String path, StreamTarget target) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null || !client.connection.isOpen()) {
      LOG.debugf("requestStream: no workspace-daemon live for %s", workspaceId);
      return;
    }
    client
        .connection
        .sendText(codec.encode(new OpenStream(nonce, path, target)))
        .subscribe()
        .with(
            ignored -> {},
            failure ->
                LOG.debugf("could not ask workspace %s for a stream: %s", workspaceId, failure));
  }

  @Override
  public boolean isDaemonLive(Long workspaceId) {
    DaemonConnection client = clients.get(workspaceId);
    return client != null && client.connection.isOpen();
  }

  /** Handle a decoded frame from {@code workspace-daemon} for {@code workspaceId}. */
  public void onMessage(Long workspaceId, WebSocketConnection connection, DaemonMessage message) {
    DaemonConnection client = clients.get(workspaceId);
    switch (message) {
      case Hello hello -> {
        LOG.infof(
            "workspace-daemon HELLO for workspace %s (repo %s, branch %s, capability %d, daemon"
                + " %s built %s)",
            hello.workspaceId(),
            hello.repoId(),
            hello.branch(),
            hello.capabilityVersion(),
            hello.daemonVersion(),
            hello.daemonBuildTime());
        // Remember the repository the daemon serves: service (dev-server) events carry only the
        // service NAME, and the host keys supervision state by (repoId, workspaceId, id) — so the
        // ServiceEventSink needs repoId to resolve the name to a repository service definition. The
        // daemon's announced build identity is retained for the workspace registry
        // (WorkspaceDaemonInfo).
        if (client != null) {
          client.repoId = hello.repoId();
          client.label = hello.workspaceId();
          client.daemonVersion = hello.daemonVersion();
          client.daemonBuildTime = parseInstant(hello.daemonBuildTime());
          client.capabilityVersion = hello.capabilityVersion();
        }
        connection.sendTextAndAwait(codec.encode(new Ack()));
        // …and, off this thread entirely, go and ask that container what its harnesses can be
        // configured with. Hello is the first moment anything in the container is reachable, and
        // this is the only trigger there is: nothing on the wire announces the daemon's loopback
        // bind or its capability probe, so the relay asks and keeps asking while the answer says
        // "not yet". It returns immediately, it cannot throw into this frame handler, and nothing
        // about a workspace depends on it — see WorkspaceCapabilityRelay.
        if (capabilityRelay.isResolvable()) {
          capabilityRelay.get().onDaemonHello(workspaceId);
        }
      }
      case Heartbeat ignored -> {
        /* liveness only — the open socket is the signal */
      }
      case DaemonLog log ->
          LOG.infof("[workspace-daemon %s] %s: %s", workspaceId, log.level(), log.message());
      case CommandChunk chunk -> {
        if (DaemonProtocol.PROVISION_CORRELATION_ID.equals(chunk.correlationId())) {
          streamProvisionOutput(workspaceId, chunk);
        } else if (chunk.correlationId() != null
            && chunk.correlationId().startsWith(DaemonProtocol.BOOTSTRAP_CORRELATION_PREFIX)) {
          streamBootstrapOutput(workspaceId, chunk);
        } else if (chunk.correlationId() != null
            && chunk.correlationId().startsWith(DaemonProtocol.SERVICE_CORRELATION_PREFIX)) {
          streamServiceOutput(workspaceId, client, chunk);
        } else if (client != null) {
          client.appendChunk(chunk);
        }
      }
      case CommandExit exit -> {
        if (client != null) {
          client.completeCommand(exit);
        }
      }
      case WorkspaceInfo info -> {
        if (client != null) {
          client.completeDescribe(info);
        }
      }
      case ConfigView view -> {
        if (client != null) {
          client.completeConfig(view);
        }
      }
      case Provisioned provisioned ->
          completeProvision(workspaceId, ProvisionResult.ok(provisioned.head()));
      case ProvisionFailed failed ->
          completeProvision(workspaceId, ProvisionResult.failed(failed.message()));
      case BootstrapStep step -> routeBootstrapStep(workspaceId, step);
      case BootstrapOutcome outcome -> routeBootstrapOutcome(workspaceId, outcome);
      case Bootstrapped done -> completeBootstrap(workspaceId, done.ok());
      case ServiceTransition event -> routeServiceState(workspaceId, client, event);
      case GitStatus status -> onGitStatus(workspaceId, client, status);
      case AgentActivity activity -> onAgentActivity(workspaceId, client, activity);
      case EditorState state -> onEditorState(workspaceId, state);
      case WorkspaceChanged changed -> onWorkspaceChanged(workspaceId, client, changed);
      // qits -> workspace-daemon requests are never received here; ignore defensively.
      case Ack ignored -> {}
      case RunCommand ignored -> {}
      case Describe ignored -> {}
      case DescribeConfig ignored -> {}
      case RunBootstrap ignored -> {}
      case StartService ignored -> {}
      case SignalService ignored -> {}
      case PullBranch ignored -> {}
      case OpenStream ignored -> {}
    }
  }

  /**
   * Relay a daemon-side change nudge onto the workspace's SSE stream.
   *
   * <p>The daemon owns state the host no longer holds — the commands it ran, the transcripts it
   * imported — so it is the only thing that knows when a view went stale. This is how it says so
   * (capability 3); before it existed the browser refetched on its own cadence.
   *
   * <p>An unrecognised topic is dropped rather than treated as an error: the frame carries a name
   * rather than an enum precisely so a newer daemon can nudge about something this backend has no
   * view for, and that should cost nothing.
   */
  private void onWorkspaceChanged(
      Long workspaceId, DaemonConnection client, WorkspaceChanged changed) {
    WorkspaceChangeHint.Topic topic;
    try {
      topic = WorkspaceChangeHint.Topic.valueOf(changed.topic());
    } catch (IllegalArgumentException | NullPointerException unknown) {
      LOG.debugf(
          "Ignoring a workspace-daemon change nudge for unknown topic '%s' (workspace %s)",
          changed.topic(), workspaceId);
      return;
    }
    changePublisher.fire(client != null ? client.repoId : null, workspaceId, topic);
  }

  /**
   * Cache a working-tree status report and fan out its two consequences. Every report means the
   * daemon's working-tree marker moved, so it re-homes the old host watcher's trigger: a {@link
   * WorkspaceChangeHint.Topic#FILES} hint on the workspace channel makes the detail view re-fetch
   * {@code /files} + {@code /detection}. The {@code clean} flag is cached for {@link #isClean}, and
   * a {@link WorkspaceChangeHint.Topic#GIT_STATUS} hint on the repository channel refreshes the
   * branch-tree dirty badge — but only when the flag actually flipped, so a dirty→dirty content
   * edit nudges {@code FILES} without re-invalidating the whole workspace list.
   */
  private void onGitStatus(Long workspaceId, DaemonConnection client, GitStatus status) {
    String repoId = client != null ? client.repoId : null;
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.FILES);
    if (status.head() != null) {
      gitHead.put(workspaceId, status.head());
    }
    Boolean previous = gitClean.put(workspaceId, status.clean());
    if (previous == null || previous != status.clean()) {
      changePublisher.fire(repoId, null, WorkspaceChangeHint.Topic.GIT_STATUS);
    }
  }

  /**
   * Cache one web-editor state report. The daemon sends the current state once per control-socket
   * connect and then one frame per transition, so this is the whole of the host's knowledge.
   *
   * <p><b>A state this host cannot name drops the entry rather than keeping the last one.</b>
   * {@link EditorLifecycle#parse} answers null for anything outside the three values, and "nothing
   * reported" is the honest reading of a frame this backend does not understand — the protocol
   * module carries the state as a plain String precisely so a newer daemon can say something new
   * without failing a frame. Keeping the previous value instead would let a stale {@code RUNNING}
   * outlive a transition that said otherwise, which is the one direction that costs a reader a
   * splash they should have seen.
   */
  private void onEditorState(Long workspaceId, EditorState reported) {
    EditorLifecycle state = EditorLifecycle.parse(reported.state());
    if (state == null) {
      editorStates.remove(workspaceId);
      return;
    }
    editorStates.put(workspaceId, state);
  }

  @Override
  public Optional<EditorLifecycle> editorStateFor(Long workspaceRowId) {
    return workspaceRowId == null
        ? Optional.empty()
        : Optional.ofNullable(editorStates.get(workspaceRowId));
  }

  @Override
  public Optional<Boolean> isClean(Long workspaceId) {
    return Optional.ofNullable(gitClean.get(workspaceId));
  }

  @Override
  public Optional<String> head(Long workspaceId) {
    return Optional.ofNullable(gitHead.get(workspaceId));
  }

  /**
   * Handle one agent-activity report into its two sinks. First (behaviour-preserving) sink: a
   * {@code SessionStart} still records the session-lineage row via {@link
   * AgentSessionReporter#reportAgentSession} — the same DB write the retired direct {@code
   * /agent-session} hook produced, now driven off this daemon frame. Wrapped in try/catch (it
   * throws {@code BadRequestException}/{@code NotFoundException} for an invalid session id or an
   * unknown/stopped command) so an escape can't close the control socket, mirroring {@link
   * PendingBootstrap#dispatch}.
   *
   * <p>Second sink: the in-memory rollup cache. The state is stored per {@code commandId} —
   * <b>every state, {@code ENDED} included</b>; see {@link #agentActivity} for why that one used to
   * be thrown away and why it must not be. A {@link WorkspaceChangeHint.Topic#AGENT_ACTIVITY} hint
   * is fired only when the workspace's rollup actually flips, so a same-state re-report (reconnect
   * replay) doesn't churn the UI. The flip fires on three channels at once, one per route that
   * renders the agent-activity bar: the workspace channel (the open workspace detail), the
   * repository channel {@code (repoId, null)} (the repository detail, mirroring {@code
   * GIT_STATUS}), and the global channel {@code (null, null)} (the project detail, which spans
   * repositories and holds ONE connection instead of one per repo).
   */
  private void onAgentActivity(
      Long workspaceId, DaemonConnection client, AgentActivity activity) {
    String repoId = client != null ? client.repoId : null;
    if ("SessionStart".equals(activity.hookEvent()) && activity.sessionId() != null) {
      try {
        if (agentSessions.isResolvable()) {
          agentSessions
              .get()
              .reportAgentSession(
                  activity.commandId(), activity.sessionId(), activity.transcriptPath());
        }
      } catch (RuntimeException e) {
        LOG.debugf(
            "agent-session lineage report failed for command %s: %s",
            activity.commandId(), e.getMessage());
      }
    }
    AgentActivityState state = parseState(activity.state());
    if (state == null) {
      return; // unknown state string — lineage above still ran; nothing to cache/flip
    }
    long now = System.currentTimeMillis();
    // An agent that is working is the workspace being used. Debounced on the keepalive's side, and
    // a no-op entirely while nothing is idle-stopped.
    editorKeepalive.touched(workspaceId);
    AgentActivityState before = rollup(workspaceId, now);
    agentActivity.put(activity.commandId(), new ActivityEntry(workspaceId, state, now));
    if (before != rollup(workspaceId, now)) {
      fireActivityFlip(repoId, workspaceId);
    }
  }

  /**
   * Announce an agent-activity rollup change on all three channels that render the bar. Split out
   * because the {@link #expireEndedSessions} sweep has to fire exactly the same set — a state that
   * changed because a timer fired is no less a change than one a daemon reported.
   */
  private void fireActivityFlip(String repoId, Long workspaceId) {
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.AGENT_ACTIVITY);
    changePublisher.fire(repoId, null, WorkspaceChangeHint.Topic.AGENT_ACTIVITY);
    changePublisher.fire(null, null, WorkspaceChangeHint.Topic.AGENT_ACTIVITY);
  }

  /**
   * The per-workspace rollup: BUSY &gt; WAITING &gt; IDLE &gt; ENDED across its tracked commands;
   * else null.
   *
   * <p>{@code ENDED} sits at the bottom on purpose. A workspace with one finished session and one
   * still idling has a live conversation in it, so it reads as idle; only when <em>every</em>
   * tracked command has ended does the workspace itself count as ended, which is the state the bar
   * sorts to the front.
   *
   * <p><b>Expiry is evaluated here, on every read</b>, rather than only in the sweep below. That
   * makes the answer correct the instant the TTL passes regardless of when the timer next runs — the
   * sweep exists to <em>announce</em> the change, not to be the thing that makes it true.
   */
  private AgentActivityState rollup(Long workspaceId) {
    return rollup(workspaceId, System.currentTimeMillis());
  }

  /**
   * {@link #rollup(Long)} as of a given instant. The instant is a parameter so the sweep below can
   * compare one before/after pair taken at the same moment — and so a test can travel past the TTL
   * without a fake clock, a sleep, or a second application boot for one config value.
   */
  private AgentActivityState rollup(Long workspaceId, long now) {
    boolean waiting = false;
    boolean idle = false;
    boolean ended = false;
    for (ActivityEntry entry : agentActivity.values()) {
      if (!entry.workspaceId().equals(workspaceId) || hasExpired(entry, now)) {
        continue;
      }
      switch (entry.state()) {
        case BUSY -> {
          return AgentActivityState.BUSY; // highest precedence — short-circuit
        }
        case WAITING -> waiting = true;
        case IDLE -> idle = true;
        case ENDED -> ended = true;
      }
    }
    if (waiting) {
      return AgentActivityState.WAITING;
    }
    if (idle) {
      return AgentActivityState.IDLE;
    }
    return ended ? AgentActivityState.ENDED : null;
  }

  /** Only {@code ENDED} ages out; a live state is current until the daemon says otherwise. */
  private boolean hasExpired(ActivityEntry entry, long now) {
    return entry.state() == AgentActivityState.ENDED
        && now - entry.recordedAtMillis() >= endedTtlMs;
  }

  /**
   * Drop timed-out {@code ENDED} entries and tell the open views about it.
   *
   * <p>Without this the bar would keep showing "Ended" until something unrelated nudged the client:
   * {@link #rollup} already answers correctly, but nothing on this page polls, so an SSE hint is the
   * only way a change reaches a browser. The cadence is a floor on how late the fade is, not the
   * TTL — a sweep that runs a minute after the deadline still announces a state that became true at
   * the deadline.
   *
   * <p>The work is in the {@code now}-taking overload so a test can drive it past the TTL directly
   * rather than sleeping for one or booting a second application to shorten it.
   */
  @Scheduled(
      every = "{qits.workspace.agent-activity.sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweepEndedSessions() {
    expireEndedSessions(System.currentTimeMillis());
  }

  /** {@link #sweepEndedSessions()} as of a given instant; returns how many entries it dropped. */
  int expireEndedSessions(long now) {
    // Snapshot the rollup of every workspace that holds an expiring entry BEFORE removing anything,
    // so the comparison afterwards is against what a client was last told rather than against a
    // half-swept map.
    // A HashMap and containsKey rather than computeIfAbsent: a rollup of null is a real answer
    // ("nothing tracked here") and computeIfAbsent would treat it as "not computed yet", so a
    // workspace whose only entry is the expiring one would be skipped and its entry never removed.
    Map<Long, AgentActivityState> before = new HashMap<>();
    for (ActivityEntry entry : agentActivity.values()) {
      if (hasExpired(entry, now) && !before.containsKey(entry.workspaceId())) {
        before.put(entry.workspaceId(), rollup(entry.workspaceId(), now));
      }
    }
    if (before.isEmpty()) {
      return 0;
    }
    int removed = 0;
    for (Map.Entry<String, ActivityEntry> tracked : agentActivity.entrySet()) {
      if (hasExpired(tracked.getValue(), now)
          && agentActivity.remove(tracked.getKey(), tracked.getValue())) {
        removed++;
      }
    }
    for (Map.Entry<Long, AgentActivityState> workspace : before.entrySet()) {
      if (workspace.getValue() != rollup(workspace.getKey(), now)) {
        DaemonConnection client = clients.get(workspace.getKey());
        fireActivityFlip(client != null ? client.repoId : null, workspace.getKey());
      }
    }
    return removed;
  }

  private static AgentActivityState parseState(String state) {
    if (state == null) {
      return null;
    }
    try {
      return AgentActivityState.valueOf(state);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @Override
  public Optional<AgentActivityState> activityFor(Long workspaceId) {
    return Optional.ofNullable(rollup(workspaceId));
  }

  @Override
  public Optional<WorkspaceDaemonInfo.Info> lookup(Long workspaceId) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null || !client.connection.isOpen()) {
      return Optional.empty();
    }
    return Optional.of(
        new WorkspaceDaemonInfo.Info(
            client.connectedAt,
            client.daemonVersion,
            client.daemonBuildTime,
            client.capabilityVersion));
  }

  @Override
  public Collection<WorkspaceDaemonInfo.Info> all() {
    // Only open sockets — mirrors lookup's guard so a lingering-but-closed entry never counts.
    return clients.values().stream()
        .filter(client -> client.connection.isOpen())
        .map(
            client ->
                new WorkspaceDaemonInfo.Info(
                    client.connectedAt,
                    client.daemonVersion,
                    client.daemonBuildTime,
                    client.capabilityVersion))
        .toList();
  }

  /**
   * Parse the daemon's ISO-8601 build-time string to an {@code Instant}, tolerating {@code null}
   * (older image / unfiltered dev jar) and a malformed value (never fail a registration over a
   * cosmetic field) — either yields {@code null}, surfaced as "unknown build time".
   */
  private static Instant parseInstant(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(iso);
    } catch (java.time.format.DateTimeParseException e) {
      LOG.debugf("workspace-daemon reported unparseable build time '%s': %s", iso, e.getMessage());
      return null;
    }
  }

  @Override
  public void pullFromOrigin(Long workspaceId, String branch) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null || !client.connection.isOpen()) {
      // No live daemon to pull — the checkout syncs on its next host git op (fast-forward /
      // merge-parent-in), so a missed notification never loses data. Fire-and-forget.
      LOG.debugf("pullFromOrigin('%s'): no workspace-daemon live for %s", branch, workspaceId);
      return;
    }
    String correlationId = UUID.randomUUID().toString();
    client.connection.sendTextAndAwait(codec.encode(new PullBranch(correlationId, branch)));
  }

  /**
   * Fan a service's lifecycle transition out to every subscribed host coordinator — on the {@link
   * #sinkDispatch} thread, never the socket thread (see the field's javadoc for the deadlock this
   * prevents). repoId/label are captured before the hop so a disconnect can't blank them mid-fan.
   */
  private void routeServiceState(
      Long workspaceId, DaemonConnection client, ServiceTransition event) {
    if (serviceSinks.isEmpty()) {
      return;
    }
    String repoId = client != null ? client.repoId : null;
    String label = labelOf(client);
    sinkDispatch.execute(
        () -> {
          for (WorkspaceServiceDriver.ServiceEventSink sink : serviceSinks) {
            try {
              sink.onState(
                  repoId, label, workspaceId, event.id(), event.state(), event.exitCode());
            } catch (RuntimeException e) {
              LOG.debugf("service transition sink failed (dropped): %s", e.getMessage());
            }
          }
        });
  }

  /**
   * Fan a running service's streamed output (correlation {@code service:<name>}) out to the sinks —
   * off the socket thread, like {@link #routeServiceState}.
   */
  private void streamServiceOutput(
      Long workspaceId, DaemonConnection client, CommandChunk chunk) {
    if (serviceSinks.isEmpty()) {
      return;
    }
    String repoId = client != null ? client.repoId : null;
    String label = labelOf(client);
    String name =
        chunk.correlationId().substring(DaemonProtocol.SERVICE_CORRELATION_PREFIX.length());
    sinkDispatch.execute(
        () -> {
          for (String line : chunk.text().split("\n", -1)) {
            if (line.isEmpty()) {
              continue;
            }
            for (WorkspaceServiceDriver.ServiceEventSink sink : serviceSinks) {
              try {
                sink.onLine(repoId, label, workspaceId, name, chunk.stream().name(), line);
              } catch (RuntimeException e) {
                LOG.debugf("service output sink failed (dropped): %s", e.getMessage());
              }
            }
          }
        });
  }

  /**
   * Feed a provision's streamed clone/submodule output to the awaiting host's {@code clone}
   * segment.
   */
  private void streamProvisionOutput(Long workspaceId, CommandChunk chunk) {
    PendingProvision pending = provisions.get(workspaceId);
    if (pending == null || pending.onLine == null) {
      return; // no awaiter (yet) — provision output is best-effort UI, the exit is what matters
    }
    for (String line : chunk.text().split("\n", -1)) {
      if (!line.isEmpty()) {
        pending.onLine.accept(line);
      }
    }
  }

  /**
   * Complete the workspace's provision with {@code result}, if an awaiter is registered. The
   * awaiter always registers first — {@code provisionContainer} calls {@link #awaitProvision}
   * synchronously right after {@code docker run}, long before the daemon can finish cloning — so a
   * terminal with no pending slot is a late straggler (a connect that beat the timeout, or a
   * duplicate on restart) and is dropped rather than retained: {@code computeIfAbsent} here would
   * create an entry no awaiter ever removes, leaking the map. A duplicate on an already-completed
   * future is a harmless no-op.
   */
  private void completeProvision(Long workspaceId, ProvisionResult result) {
    PendingProvision pending = provisions.get(workspaceId);
    if (pending != null) {
      pending.future.complete(result);
    }
  }

  /** Route one bootstrap step's phase change to the awaiting sink (buffered until it registers). */
  private void routeBootstrapStep(Long workspaceId, BootstrapStep step) {
    bootstraps
        .computeIfAbsent(workspaceId, id -> new PendingBootstrap())
        .deliver(sink -> sink.onStep(step.name(), step.phase()));
  }

  /**
   * Route one bootstrap step's terminal outcome to the awaiting sink (buffered until it registers)
   * — and, independently, to every persistent {@link WorkspaceBootstrapDriver.OutcomeSink}. The
   * persistent fan-out is what records rows for a chain the host never awaited (the daemon's HTTP
   * run verb); it hops to {@link #sinkDispatch} because the recorder writes the database, and a DB
   * write on the socket thread parks the connection's serialized inbound pipeline.
   */
  private void routeBootstrapOutcome(Long workspaceId, BootstrapOutcome outcome) {
    bootstraps
        .computeIfAbsent(workspaceId, id -> new PendingBootstrap())
        .deliver(sink -> sink.onOutcome(outcome.name(), outcome.outcome(), outcome.exitCode()));
    if (outcomeSinks.isEmpty()) {
      return;
    }
    DaemonConnection client = clients.get(workspaceId);
    String repoId = client != null ? client.repoId : null;
    String label = labelOf(client);
    sinkDispatch.execute(
        () -> {
          for (WorkspaceBootstrapDriver.OutcomeSink sink : outcomeSinks) {
            try {
              sink.onOutcome(
                  repoId, label, workspaceId, outcome.name(), outcome.outcome(), outcome.exitCode());
            } catch (RuntimeException e) {
              LOG.debugf("bootstrap outcome sink failed (dropped): %s", e.getMessage());
            }
          }
        });
  }

  @Override
  public void subscribe(WorkspaceBootstrapDriver.OutcomeSink sink) {
    outcomeSinks.add(sink);
  }

  /** Feed a bootstrap step's streamed output (correlation {@code bootstrap:<name>}) to the sink. */
  private void streamBootstrapOutput(Long workspaceId, CommandChunk chunk) {
    String name =
        chunk.correlationId().substring(DaemonProtocol.BOOTSTRAP_CORRELATION_PREFIX.length());
    PendingBootstrap pending =
        bootstraps.computeIfAbsent(workspaceId, id -> new PendingBootstrap());
    for (String line : chunk.text().split("\n", -1)) {
      if (!line.isEmpty()) {
        pending.deliver(sink -> sink.onLine(name, line));
      }
    }
  }

  /**
   * Complete the workspace's bootstrap chain — <b>complete-or-retain</b>: unlike {@link
   * #completeProvision}, the autonomous boot chain can finish before the host's async observer
   * registers its await, so a terminal with no slot creates one (retaining the result) rather than
   * dropping it. The awaiter (or {@link #awaitProvision}'s next-cycle clear) removes it.
   */
  private void completeBootstrap(Long workspaceId, boolean ok) {
    bootstraps
        .computeIfAbsent(workspaceId, id -> new PendingBootstrap())
        .future
        .complete(new Result(ok));
  }

  /**
   * Send a {@link RunCommand} to the workspace's client and complete when its {@link CommandExit}
   * arrives, with the accumulated output. Fails fast if no client is connected. Part-1
   * demonstration seam only.
   */
  public CompletableFuture<CommandResult> runCommand(
      Long workspaceId,
      java.util.List<String> argv,
      String cwd,
      java.util.Map<String, String> env) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("No workspace-daemon connected for workspace " + workspaceId));
    }
    String correlationId = UUID.randomUUID().toString();
    CompletableFuture<CommandResult> future = client.expectCommand(correlationId);
    client.connection.sendTextAndAwait(codec.encode(new RunCommand(correlationId, argv, cwd, env)));
    return future;
  }

  /**
   * Send a {@link Describe} to the workspace's client and complete with its {@link WorkspaceInfo}.
   * Part-1 demonstration seam only.
   */
  public CompletableFuture<WorkspaceInfo> describe(Long workspaceId) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("No workspace-daemon connected for workspace " + workspaceId));
    }
    String correlationId = UUID.randomUUID().toString();
    CompletableFuture<WorkspaceInfo> future = client.expectDescribe();
    client.connection.sendTextAndAwait(codec.encode(new Describe(correlationId)));
    return future;
  }

  /**
   * Send a {@link DescribeConfig} to the workspace's client and complete with its {@link
   * ConfigView} — the workspace's in-container {@code .qits-config.yml}, correlated by id (unlike
   * the FIFO {@link #describe}). Fails fast if no client is connected.
   */
  public CompletableFuture<ConfigView> describeConfig(Long workspaceId) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("No workspace-daemon connected for workspace " + workspaceId));
    }
    String correlationId = UUID.randomUUID().toString();
    CompletableFuture<ConfigView> future = client.expectConfig(correlationId);
    client.connection.sendTextAndAwait(codec.encode(new DescribeConfig(correlationId)));
    return future;
  }

  @Override
  public Optional<WorkspaceConfigView> readConfig(Long workspaceId) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null || !client.connection.isOpen()) {
      return Optional.empty(); // no daemon live to read the checkout — caller uses its default view
    }
    try {
      ConfigView view =
          describeConfig(workspaceId).get(configDescribeTimeoutMs, TimeUnit.MILLISECONDS);
      String warning = view.warning();
      QitsConfig config;
      try {
        config = objectMapper.readValue(view.configJson(), QitsConfig.class);
      } catch (Exception e) {
        // The daemon reported the file valid but its JSON doesn't map to a QitsConfig (e.g. an
        // unknown enum, which the daemon normalizes but does not validate): surface it as the
        // degrade-loudly warning and fall back to the empty config, same end state as a structural
        // parse error in-container.
        config = QitsConfig.EMPTY;
        warning = warning != null ? warning : "invalid qits config: " + e.getMessage();
      }
      return Optional.of(new WorkspaceConfigView(config, warning));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (TimeoutException | ExecutionException e) {
      // A live daemon that didn't answer in time (or the socket dropped mid-read): treat as "no
      // config available right now" rather than a hard failure — the read path shows its default.
      LOG.debugf("workspace-daemon config read failed for %s: %s", workspaceId, e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public Optional<ProvisionResult> awaitProvision(
      Long workspaceId,
      Duration connectTimeout,
      Duration provisionTimeout,
      Consumer<String> onLine) {
    // A new provision cycle: drop any stale bootstrap slot from a previous cycle (e.g. a
    // kill-switch
    // run whose retained terminal was never awaited), so this cycle's boot awaiter can't pick it
    // up.
    bootstraps.remove(workspaceId);
    // Register the pending slot BEFORE waiting, so streamed chunks and the terminal event that
    // arrive during the wait land on it (and survive a socket reconnect — the slot lives on the
    // registry, not the connection).
    PendingProvision pending =
        provisions.computeIfAbsent(workspaceId, id -> new PendingProvision());
    pending.onLine = onLine;
    try {
      // If the terminal event somehow already arrived, take it without waiting on liveness. Else
      // wait
      // for a daemon to dial home; if none does within the window, this is a stale image /
      // no-backend
      // case — return empty so the caller falls back to the host-driven clone (degradation
      // contract).
      if (!pending.future.isDone() && !awaitLive(workspaceId, connectTimeout)) {
        return Optional.empty();
      }
      return Optional.of(pending.future.get(provisionTimeout.toMillis(), TimeUnit.MILLISECONDS));
    } catch (TimeoutException e) {
      // Live but silent past the deadline: fail (the caller removes the container + marks FAILED —
      // no fallback, the daemon owns a possibly half-populated /workspace).
      return Optional.of(
          ProvisionResult.failed(
              "workspace-daemon did not finish provisioning within "
                  + provisionTimeout.toSeconds()
                  + "s"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.of(ProvisionResult.failed("interrupted while awaiting provisioning"));
    } catch (ExecutionException e) {
      return Optional.of(ProvisionResult.failed(String.valueOf(e.getCause())));
    } finally {
      provisions.remove(workspaceId, pending);
    }
  }

  /**
   * Test hook: whether an {@link #awaitProvision} awaiter (with its line sink) is registered for
   * {@code workspaceId}. Lets a test send provision output only once routing is in place, since
   * streamed chunks are otherwise best-effort (dropped before an awaiter registers).
   */
  boolean isAwaitingProvision(Long workspaceId) {
    PendingProvision pending = provisions.get(workspaceId);
    return pending != null && pending.onLine != null;
  }

  /**
   * Test hook: whether a {@link PendingBootstrap} slot exists for {@code workspaceId} — lets a test
   * confirm the daemon's autonomous chain events have landed on the registry before it registers
   * its await (the retain/buffer race the autonomous model must survive).
   */
  boolean isBootstrapPending(Long workspaceId) {
    return bootstraps.containsKey(workspaceId);
  }

  @Override
  public Optional<Result> awaitBootstrap(
      Long workspaceId, StepSink sink, Duration connectTimeout, Duration chainTimeout) {
    // Register the sink so buffered events (steps that beat this await, since the daemon runs the
    // chain autonomously) replay onto it, and a terminal that already arrived is picked up.
    PendingBootstrap pending =
        bootstraps.computeIfAbsent(workspaceId, id -> new PendingBootstrap());
    pending.setSink(sink);
    return awaitBootstrapFuture(workspaceId, pending, connectTimeout, chainTimeout);
  }

  @Override
  public Optional<Result> runBootstrap(
      Long workspaceId, String name, StepSink sink, Duration chainTimeout) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null || !client.connection.isOpen()) {
      return Optional.empty(); // no daemon live to run it
    }
    // A manual re-run only starts when the daemon receives RunBootstrap, so the awaiter always
    // registers first: replace any stale slot with a fresh one, set the sink, then send.
    PendingBootstrap pending = new PendingBootstrap();
    pending.setSink(sink);
    bootstraps.put(workspaceId, pending);
    String correlationId = UUID.randomUUID().toString();
    client.connection.sendTextAndAwait(codec.encode(new RunBootstrap(correlationId, name)));
    // The daemon is already live (we just sent to it), so no connect wait.
    return awaitBootstrapFuture(workspaceId, pending, Duration.ZERO, chainTimeout);
  }

  private Optional<Result> awaitBootstrapFuture(
      Long workspaceId,
      PendingBootstrap pending,
      Duration connectTimeout,
      Duration chainTimeout) {
    try {
      if (!pending.future.isDone()
          && !connectTimeout.isZero()
          && !awaitLive(workspaceId, connectTimeout)) {
        return Optional.empty(); // no daemon became live — the chain never ran
      }
      return Optional.of(pending.future.get(chainTimeout.toMillis(), TimeUnit.MILLISECONDS));
    } catch (TimeoutException e) {
      return Optional.of(new Result(false)); // live but silent past the deadline ⇒ treat as failed
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.of(new Result(false));
    } catch (ExecutionException e) {
      return Optional.of(new Result(false));
    } finally {
      bootstraps.remove(workspaceId, pending);
    }
  }

  @Override
  public void subscribe(WorkspaceServiceDriver.ServiceEventSink sink) {
    serviceSinks.add(sink);
  }

  @Override
  public void startService(
      Long workspaceId, String serviceName, String script, Map<String, String> env) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null || !client.connection.isOpen()) {
      LOG.debugf("startService('%s'): no workspace-daemon live for %s", serviceName, workspaceId);
      return; // daemon-backed mode requires a live daemon; the host falls back to tmux otherwise
    }
    String correlationId = UUID.randomUUID().toString();
    client.connection.sendTextAndAwait(
        codec.encode(new StartService(correlationId, serviceName, script, env)));
  }

  @Override
  public void signalService(Long workspaceId, String serviceName, String signal) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null || !client.connection.isOpen()) {
      LOG.debugf("signalService('%s'): no workspace-daemon live for %s", serviceName, workspaceId);
      return;
    }
    String correlationId = UUID.randomUUID().toString();
    client.connection.sendTextAndAwait(
        codec.encode(new SignalService(correlationId, serviceName, signal)));
  }

  /** Poll for a live daemon up to {@code timeout}; true once one is connected. */
  private boolean awaitLive(Long workspaceId, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (isDaemonLive(workspaceId)) {
        return true;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return isDaemonLive(workspaceId);
  }

  /** One live client: its connection plus the in-flight correlated round-trips. */
  private static final class DaemonConnection {
    private final WebSocketConnection connection;

    /** When this control socket registered — the workspace's "connected since" (registry). */
    private final Instant connectedAt = Instant.now();

    /** The repository the daemon serves, learned from its {@link Hello} — see {@code onMessage}. */
    private volatile String repoId;

    /**
     * The workspace label the daemon announces for itself. Carried only so events and log lines can
     * still name the workspace readably — the registry keys on the id from the socket path.
     */
    private volatile String label;

    /**
     * The daemon binary's build identity announced in its {@link Hello} (registry); may be null.
     */
    private volatile String daemonVersion;

    private volatile Instant daemonBuildTime;

    /**
     * The wire-contract version announced in {@link Hello}; 0 until one arrives. The registry used
     * to only log this — it is recorded now because the reverse tunnel has to branch on it, and
     * "not yet said" has to read as "not capable".
     */
    private volatile int capabilityVersion;

    private final ConcurrentHashMap<String, PendingCommand> pendingCommands =
        new ConcurrentHashMap<>();
    // WorkspaceInfo carries no correlation id (Part-1 stub), so describes are matched FIFO: a queue
    // rather than a single slot, otherwise a second concurrent describe() would overwrite the first
    // and orphan its future. Best-effort ordering is enough until a real consumer needs
    // correlation.
    private final Queue<CompletableFuture<WorkspaceInfo>> pendingDescribes =
        new ConcurrentLinkedQueue<>();
    // ConfigView DOES echo the request's correlation id, so config reads are matched by id (an
    // improvement over the FIFO pendingDescribes stub) — concurrent readConfig calls never cross.
    private final ConcurrentHashMap<String, CompletableFuture<ConfigView>> pendingConfigs =
        new ConcurrentHashMap<>();

    DaemonConnection(WebSocketConnection connection) {
      this.connection = connection;
    }

    CompletableFuture<CommandResult> expectCommand(String correlationId) {
      PendingCommand pending = new PendingCommand();
      pendingCommands.put(correlationId, pending);
      return pending.future;
    }

    void appendChunk(CommandChunk chunk) {
      PendingCommand pending = pendingCommands.get(chunk.correlationId());
      if (pending != null) {
        (chunk.stream() == Stream.STDERR ? pending.stderr : pending.stdout).append(chunk.text());
      }
    }

    void completeCommand(CommandExit exit) {
      PendingCommand pending = pendingCommands.remove(exit.correlationId());
      if (pending != null) {
        pending.future.complete(
            new CommandResult(
                exit.exitCode(), pending.stdout.toString(), pending.stderr.toString()));
      }
    }

    CompletableFuture<WorkspaceInfo> expectDescribe() {
      CompletableFuture<WorkspaceInfo> future = new CompletableFuture<>();
      pendingDescribes.add(future);
      return future;
    }

    void completeDescribe(WorkspaceInfo info) {
      CompletableFuture<WorkspaceInfo> future = pendingDescribes.poll();
      if (future != null) {
        future.complete(info);
      }
    }

    CompletableFuture<ConfigView> expectConfig(String correlationId) {
      CompletableFuture<ConfigView> future = new CompletableFuture<>();
      pendingConfigs.put(correlationId, future);
      return future;
    }

    void completeConfig(ConfigView view) {
      CompletableFuture<ConfigView> future = pendingConfigs.remove(view.correlationId());
      if (future != null) {
        future.complete(view);
      }
    }
  }

  /** Accumulates a command's streamed output until its exit resolves the future. */
  private static final class PendingCommand {
    private final CompletableFuture<CommandResult> future = new CompletableFuture<>();
    private final StringBuilder stdout = new StringBuilder();
    private final StringBuilder stderr = new StringBuilder();
  }

  /**
   * One in-flight autonomous self-provision: the future the awaiting host blocks on, plus the
   * {@code clone}-segment line sink its streamed output is routed to. Keyed by {@code workspaceId}
   * so it survives a socket reconnect mid-clone.
   */
  private static final class PendingProvision {
    private final CompletableFuture<ProvisionResult> future = new CompletableFuture<>();
    private volatile Consumer<String> onLine;
  }

  /**
   * One in-flight bootstrap chain: the future the awaiting host blocks on, plus a buffer of step
   * events delivered before a sink registered (the autonomous boot chain can start streaming before
   * the host's async observer awaits). {@link #setSink} replays the buffer and thereafter delivers
   * live; {@link #deliver} buffers until then. Keyed by {@code workspaceId} so it survives a socket
   * reconnect mid-chain.
   */
  private static final class PendingBootstrap {
    private final CompletableFuture<WorkspaceBootstrapDriver.Result> future =
        new CompletableFuture<>();
    private final List<Consumer<WorkspaceBootstrapDriver.StepSink>> buffered = new ArrayList<>();
    private WorkspaceBootstrapDriver.StepSink sink;

    synchronized void setSink(WorkspaceBootstrapDriver.StepSink sink) {
      this.sink = sink;
      for (Consumer<WorkspaceBootstrapDriver.StepSink> event : buffered) {
        dispatch(event);
      }
      buffered.clear();
    }

    synchronized void deliver(Consumer<WorkspaceBootstrapDriver.StepSink> event) {
      if (sink != null) {
        dispatch(event);
      } else {
        buffered.add(event);
      }
    }

    /**
     * Run a sink callback, swallowing any exception. The callbacks run on the websockets-next
     * onMessage thread (which does not guard the dispatch), and the host sink writes to the DB
     * (e.g. {@code recordOutcome} throws {@code NotFoundException} for a workspace deleted
     * mid-chain) — an escape would close the control socket, so the daemon's terminal {@code
     * Bootstrapped} never arrives and the host await hangs to timeout. A dropped per-step callback
     * is harmless; {@link #completeBootstrap} resolves the future directly, not through the sink.
     */
    private void dispatch(Consumer<WorkspaceBootstrapDriver.StepSink> event) {
      try {
        event.accept(sink);
      } catch (RuntimeException e) {
        LOG.debugf("workspace-daemon bootstrap sink callback failed (dropped): %s", e.getMessage());
      }
    }
  }
}
