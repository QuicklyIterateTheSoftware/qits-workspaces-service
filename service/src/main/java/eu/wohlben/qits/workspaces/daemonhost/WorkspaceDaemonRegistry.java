package eu.wohlben.qits.workspaces.daemonhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspaces.control.AgentActivityState;
import eu.wohlben.qits.workspaces.control.AgentKills;
import eu.wohlben.qits.workspaces.control.AgentActivityView;
import eu.wohlben.qits.workspaces.control.AgentService;
import eu.wohlben.qits.workspaces.control.AgentSessionReporter;
import eu.wohlben.qits.workspaces.control.ProvisionResult;
import eu.wohlben.qits.workspaces.control.QitsConfig;
import eu.wohlben.qits.workspaces.control.WorkspaceAgentActivity;
import eu.wohlben.qits.workspaces.control.WorkspaceConfigReader;
import eu.wohlben.qits.workspaces.control.WorkspaceConfigView;
import eu.wohlben.qits.workspaces.control.WorkspaceDaemonInfo;
import eu.wohlben.qits.workspaces.control.WorkspaceDaemonLiveness;
import eu.wohlben.qits.workspaces.control.WorkspaceDaemonProvisioner;
import eu.wohlben.qits.workspaces.control.WorkspaceGitSync;
import eu.wohlben.qits.workspaces.control.WorkspaceChangeHint;
import eu.wohlben.qits.workspaces.control.WorkspaceChangePublisher;
import eu.wohlben.qits.workspacedaemon.protocol.Ack;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.AgentBranchPushed;
import eu.wohlben.qits.workspacedaemon.protocol.AgentHarnessExit;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.CommandExit;
import eu.wohlben.qits.workspacedaemon.protocol.ConfigView;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Describe;
import eu.wohlben.qits.workspacedaemon.protocol.DescribeConfig;
import eu.wohlben.qits.workspacedaemon.protocol.Heartbeat;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.workspacedaemon.protocol.Provisioned;
import eu.wohlben.qits.workspacedaemon.protocol.OpenStream;
import eu.wohlben.qits.workspacedaemon.protocol.PullBranch;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import eu.wohlben.qits.workspacedaemon.protocol.StreamTarget;
import eu.wohlben.qits.workspacedaemon.protocol.TranscriptChunk;
import eu.wohlben.qits.workspacedaemon.protocol.TranscriptResync;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceChanged;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceInfo;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
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
        WorkspaceAgentActivity,
        WorkspaceDaemonInfo,
        WorkspaceGitSync,
        AgentActivityView {

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
   * The agents (qits-1152): an agent's activity, waiting state and session, its pushes, and the
   * daemon's coming and going (after which every agent is started again — its credential lives in
   * the daemon's memory only). Every call runs on {@link #sinkDispatch}, never on a socket thread.
   */
  @Inject AgentService agentService;

  /**
   * The last activity frame per agent id, while its daemon's socket is up ({@link AgentActivityView}):
   * its state, and since qits-1601 whether background work it started was still in flight.
   */
  private final ConcurrentHashMap<String, AgentEntry> agentActivityById = new ConcurrentHashMap<>();

  /**
   * The reverse tunnel's host end. An {@code Instance<>} to break the cycle — {@link
   * WorkspaceTunnels} asks this registry which daemons can serve a stream, and this registry tells
   * it when one goes away.
   */
  @Inject Instance<WorkspaceTunnels> tunnels;

  /**
   * Where a killed agent is written onto the workspace row, and taken back off it (qits-951). See
   * {@link #onAgentKill}.
   */
  @Inject AgentKills agentKills;

  /**
   * The {@code hookEvent} of the {@code ENDED} frame a daemon at capability 7 sends itself for an
   * agent that died by SIGKILL — {@code DaemonProtocol.AgentEvent}'s two values, spelled here
   * because the protocol this service is pinned to predates the constants. Mirrored by name, as
   * {@link AgentActivityState} mirrors {@code DaemonProtocol.AgentState}; switch to the constants
   * when the pin carries them.
   */
  static final String OOM_KILLED_EVENT = "OomKilled";

  static final String KILLED_EVENT = "Killed";

  /**
   * Last coding-agent activity each live daemon reported ({@link AgentActivity}), keyed by {@code
   * commandId} (multiple agents can run per workspace). In-memory only: populated while the
   * daemon is connected, evicted on {@link #unregister},
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
   * One tracked agent command's activity, plus the workspace it belongs to (for the rollup) and
   * when it was recorded (only {@code ENDED} expires, but every entry carries the stamp so the
   * check needs no second map).
   */
  private record ActivityEntry(Long workspaceId, AgentActivityState state, long recordedAtMillis) {}

  /**
   * One agent's last frame, for {@link #activityOf} and {@link #reportedActivityOf} (qits-1601): the
   * {@link ActivityEntry} the rollup also keeps, plus the two flags that decide whether an idle
   * harness is still working. {@code backgroundWork} is the frame's own (capability 12; an older
   * daemon's null is taken as false, so its agents read exactly as before); {@code awaitingInput}
   * is kept as sent, because only an explicit {@code true} — the daemon's background-work grace has
   * run out — overrides it.
   */
  private record AgentEntry(ActivityEntry activity, boolean backgroundWork, Boolean awaitingInput) {}

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
   * #unregister}. A slot is registered by {@link #awaitProvision} (a DIRECT start, right after the
   * container runs) or, earlier, by {@link #expectProvision} (a TARGET start, when it asks for its
   * lease — qits-1600); either way the await removes it when it returns, and a TARGET start that ends
   * without awaiting forgets the slot it registered.
   */
  private final ConcurrentHashMap<Long, PendingProvision> provisions = new ConcurrentHashMap<>();

  /**
   * One ordered thread for everything fanned out to subscribed sinks — today the bootstrap-outcome
   * recording. <b>Never the socket thread.</b> websockets-next processes
   * one inbound frame per connection at a time, so a sink that blocks (a DB write) parks the whole pipeline — including the {@code ConfigView} reply a config read on
   * another thread is awaiting, which starved those reads to timeout (measured, D1). Single-threaded
   * so one workspace's outcomes stay in arrival order.
   */
  private final java.util.concurrent.ExecutorService sinkDispatch =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "daemon-sink-dispatch");
            thread.setDaemon(true);
            return thread;
          });

  /**
   * The transcript store's writer (qits-1752): every {@link TranscriptChunk} is resolved to its
   * agent and appended on {@link #transcriptDispatch}.
   */
  @Inject TranscriptIngest transcriptIngest;

  /**
   * One ordered thread for transcript chunks, apart from {@link #sinkDispatch}: a daemon that
   * reconnects re-ships whole files, and that backlog must not hold up the agent frames queued
   * behind it. Single-threaded because order is the append rule — chunks of one file processed out
   * of arrival order would each start somewhere other than where the store ends, and be answered
   * with resyncs instead of appended.
   */
  private final java.util.concurrent.ExecutorService transcriptDispatch =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "daemon-transcript-ingest");
            thread.setDaemon(true);
            return thread;
          });

  @jakarta.annotation.PreDestroy
  void shutdownDispatch() {
    sinkDispatch.shutdownNow();
    transcriptDispatch.shutdownNow();
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
    // The daemon is gone: drop every tracked agent activity for this workspace (re-reported on
    // reconnect).
    agentActivity.values().removeIf(entry -> entry.workspaceId().equals(workspaceId));
    agentActivityById
        .values()
        .removeIf(entry -> entry.activity().workspaceId().equals(workspaceId));
    sinkDispatch.execute(() -> agentService.onDaemonConnection(workspaceId));
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
        // Remember the repository the daemon serves: the change hints fanned out from its later
        // frames (files, git status, agent activity, bootstrap outcomes) are keyed by it. The
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
        // A daemon that (re)connected knows no agent until each is started again.
        sinkDispatch.execute(() -> agentService.onDaemonConnection(workspaceId));
      }
      case Heartbeat ignored -> {
        /* liveness only — the open socket is the signal */
      }
      case DaemonLog log ->
          LOG.infof("[workspace-daemon %s] %s: %s", workspaceId, log.level(), log.message());
      case CommandChunk chunk -> {
        if (DaemonProtocol.PROVISION_CORRELATION_ID.equals(chunk.correlationId())) {
          streamProvisionOutput(workspaceId, chunk);
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
      case AgentActivity activity -> onAgentActivity(workspaceId, client, activity);
      case AgentBranchPushed pushed -> onBranchPushed(workspaceId, pushed);
      // qits-1236: a harness exit frame (capability 11) — relayed to the agent row's own event.
      case AgentHarnessExit exit -> onAgentHarnessExit(workspaceId, exit);
      case WorkspaceChanged changed -> onWorkspaceChanged(workspaceId, client, changed);
      // qits-1752: a transcript chunk (capability 13) into the store, answered when it is not
      // where the store ends.
      case TranscriptChunk chunk -> onTranscriptChunk(workspaceId, connection, chunk);
      // Everything else is ignored: qits -> workspace-daemon requests are never received here; a
      // GitStatus frame is sent by no daemon since protocol 10 (qits-1152), and the per-agent
      // cleanup check replaced what it carried (qits-1263); and a pre-qits-947 daemon's service
      // frames (ServiceTransition, and its `service:` output chunks, which fall through to the
      // command buffer above and match no command) have no host left to read them. A default arm
      // rather than named cases, so this compiles against the protocol both before and after
      // those records leave the sealed interface.
      default -> {}
    }
  }

  /**
   * A transcript chunk, appended on {@link #transcriptDispatch} by {@link TranscriptIngest} — which
   * also decides whether this workspace may write it at all — and answered with a {@link
   * TranscriptResync} when it did not start where the store ends. The answer goes out on the
   * connection the chunk came in on, without awaiting, as {@link #requestStream} sends: a lost one
   * is only a resync the next chunk asks for again. A failure is logged and dropped, as every sink
   * here is: it must not close the control socket.
   */
  private void onTranscriptChunk(
      Long workspaceId, WebSocketConnection connection, TranscriptChunk chunk) {
    transcriptDispatch.execute(
        () -> {
          try {
            transcriptIngest
                .accept(workspaceId, chunk)
                .ifPresent(resync -> sendResync(workspaceId, connection, resync));
          } catch (RuntimeException e) {
            LOG.warnf(e, "could not store a transcript chunk (workspace %s)", workspaceId);
          }
        });
  }

  private void sendResync(
      Long workspaceId, WebSocketConnection connection, TranscriptResync resync) {
    WebSocketConnection target = connection;
    if (target == null) {
      DaemonConnection client = clients.get(workspaceId);
      target = client == null ? null : client.connection;
    }
    if (target == null || !target.isOpen()) {
      LOG.debugf("transcript resync for workspace %s dropped: no daemon connected", workspaceId);
      return;
    }
    target
        .sendText(codec.encode(resync))
        .subscribe()
        .with(
            ignored -> {},
            failure ->
                LOG.debugf(
                    "could not send a transcript resync to workspace %s: %s",
                    workspaceId,
                    failure));
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
    onAgentFrame(workspaceId, activity);
    AgentActivityState state = parseState(activity.state());
    if (state == null) {
      return; // unknown state string — lineage above still ran; nothing to cache/flip
    }
    onAgentKill(workspaceId, activity, state);
    long now = System.currentTimeMillis();
    // An agent that is working is the workspace being used. Debounced on the keepalive's side, and
    // a no-op entirely while nothing is idle-stopped.
    AgentActivityState before = rollup(workspaceId, now);
    ActivityEntry entry = new ActivityEntry(workspaceId, state, now);
    agentActivity.put(activity.commandId(), entry);
    if (activity.agentId() != null) {
      agentActivityById.put(
          activity.agentId(),
          new AgentEntry(
              entry, Boolean.TRUE.equals(activity.backgroundWork()), activity.awaitingInput()));
    }
    if (before != rollup(workspaceId, now)) {
      fireActivityFlip(repoId, workspaceId);
    }
  }

  /**
   * The row's half of a killed agent (qits-951). The rollup above takes the frame's {@code ENDED}
   * like any other — and that is all a kill could ever say through it, because {@code ENDED} is also
   * what a clean {@code SessionEnd} says. Before the daemon sent the frame at all, the rollup simply
   * kept the last state it heard, usually {@code IDLE}: a turn that died mid-work read as a turn
   * that finished, to the SPA and to a dispatcher alike. So the kill is written onto the row as a
   * {@code runtimeError} ({@link AgentKills} says why that field), where {@code /workspaces/{id}}
   * carries it, a disconnect does not drop it and no TTL expires it.
   *
   * <p>The other direction: a {@code BUSY} — an agent in this workspace has started a turn again —
   * takes the kill back off, since the row then has a working agent and an error still standing would
   * read as this turn failing too. A clean finish needs no such step; the error is already gone by
   * then.
   *
   * <p>Both are DB writes, so both go to {@link #sinkDispatch}, never the socket thread, and in
   * arrival order — a kill and the relaunch's first {@code BUSY} cannot swap. A failure is logged and
   * dropped, as every sink here is: it must not close the control socket.
   */
  /**
   * An agent's frame (qits-1152): its activity time, waiting flip and session go to its row, on
   * {@link #sinkDispatch}. A frame naming no agent is a person's own session and relays nothing.
   */
  private void onAgentFrame(Long workspaceId, AgentActivity activity) {
    if (activity.agentId() == null || activity.agentId().isBlank()) {
      return;
    }
    sinkDispatch.execute(
        () -> {
          try {
            agentService.onActivity(
                workspaceId,
                activity.agentId(),
                activity.awaitingInput(),
                activity.sessionId(),
                activity.hookEvent(),
                activity.at());
          } catch (RuntimeException e) {
            LOG.warnf(e, "could not record agent %s's activity (workspace %s)", activity.agentId(), workspaceId);
          }
        });
  }

  /** A branch an agent pushed (D22), recorded on {@link #sinkDispatch}. */
  private void onBranchPushed(Long workspaceId, AgentBranchPushed pushed) {
    sinkDispatch.execute(
        () -> {
          try {
            agentService.onBranchPushed(
                pushed.agentId(), pushed.repository(), pushed.branch(), pushed.sha());
          } catch (RuntimeException e) {
            LOG.warnf(e, "could not record agent %s's push of %s (workspace %s)", pushed.agentId(), pushed.branch(), workspaceId);
          }
        });
  }

  /**
   * A harness's exit (qits-1236, capability 11), recorded on {@link #sinkDispatch} like every other
   * agent frame — never the socket thread. Skipped for a null {@code agentId}: that is the sign-in
   * terminal, which no agent of this service owns.
   */
  private void onAgentHarnessExit(Long workspaceId, AgentHarnessExit exit) {
    if (exit.agentId() == null) {
      return;
    }
    sinkDispatch.execute(
        () -> {
          try {
            agentService.onHarnessExited(
                workspaceId,
                exit.agentId(),
                exit.commandId(),
                exit.status(),
                exit.exitCode(),
                exit.stoppedBy(),
                exit.at() == null ? 0 : exit.at().toEpochMilli());
          } catch (RuntimeException e) {
            LOG.warnf(
                e,
                "could not record agent %s's harness exit (workspace %s)",
                exit.agentId(),
                workspaceId);
          }
        });
  }

  @Override
  public Optional<AgentActivityState> activityOf(String agentId) {
    return liveEntry(agentId).map(entry -> entry.activity().state());
  }

  /**
   * The state to show for an agent (qits-1601): the raw one, except that an {@code IDLE} or {@code
   * WAITING} harness whose last frame said background work it started is still in flight reads
   * {@code BUSY}. While an agent waits on its own subagent, the daemon reports {@code Stop} (IDLE)
   * and a minute later an {@code idle_prompt} (WAITING), and a reader that took either at face
   * value — the delivery desk — prompted a busy agent as stalled. An explicit {@code awaitingInput
   * = true} wins over the flag: the daemon sends it once its background-work grace runs out, so an
   * agent that really did abandon its work still reads as waiting.
   */
  @Override
  public Optional<AgentActivityState> reportedActivityOf(String agentId) {
    return liveEntry(agentId)
        .map(
            entry -> {
              AgentActivityState state = entry.activity().state();
              boolean idle =
                  state == AgentActivityState.IDLE || state == AgentActivityState.WAITING;
              return idle && entry.backgroundWork() && !Boolean.TRUE.equals(entry.awaitingInput())
                  ? AgentActivityState.BUSY
                  : state;
            });
  }

  /** The agent's last frame, unless there is none or it has aged out ({@link #hasExpired}). */
  private Optional<AgentEntry> liveEntry(String agentId) {
    AgentEntry entry = agentId == null ? null : agentActivityById.get(agentId);
    if (entry == null || hasExpired(entry.activity(), System.currentTimeMillis())) {
      return Optional.empty();
    }
    return Optional.of(entry);
  }

  private void onAgentKill(Long workspaceId, AgentActivity activity, AgentActivityState state) {
    boolean oom = OOM_KILLED_EVENT.equals(activity.hookEvent());
    boolean killed =
        state == AgentActivityState.ENDED
            && (oom || KILLED_EVENT.equals(activity.hookEvent()));
    if (!killed && state != AgentActivityState.BUSY) {
      return;
    }
    // The pinned protocol's frame has no exit code or sentence (capability 7 adds both, as
    // AgentActivity.exitCode()/message()); until the pin moves, the host's own sentence stands in.
    String error = killed ? AgentKills.describe(oom, activity.commandId(), null) : null;
    sinkDispatch.execute(
        () -> {
          try {
            if (killed) {
              agentKills.record(workspaceId, error);
            } else {
              agentKills.clear(workspaceId);
            }
          } catch (RuntimeException e) {
            LOG.warnf(
                e, "could not update workspace %s's row for its agent's activity", workspaceId);
          }
        });
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
   * Complete the workspace's provision with {@code result}, if a slot is registered. Two starts
   * register one before the daemon can report: a DIRECT start's {@code provisionContainer} calls
   * {@link #awaitProvision} synchronously right after {@code docker run}, and a TARGET start calls
   * {@link #expectProvision} when it asks for its lease — not at its await, which waits for this
   * service to observe the lease RUNNING, and which a daemon restarted over an existing {@code
   * /workspace} routinely beats (qits-1600: its {@code Provisioned} was dropped here and the row
   * FAILED at the provision timeout). An expected slot keeps the outcome until its await takes it.
   * So a terminal with no slot is a late straggler (a connect that beat the timeout, a duplicate on
   * restart, a start that already ended) and is dropped rather than retained: {@code
   * computeIfAbsent} here would create an entry no awaiter ever removes, leaking the map, and hand
   * the next start a stale outcome. A duplicate on an already-completed future is a harmless no-op.
   *
   * <p>Dropped, but not silently (qits-1750): a successful provision with no slot is logged at WARN,
   * because it is the one a person looks for — a clone that hung past the start's timeout and then
   * finished, on a row already FAILED that this does not adopt or change. A failure with no slot is
   * only DEBUG; the row says that much already.
   */
  private void completeProvision(Long workspaceId, ProvisionResult result) {
    PendingProvision pending = provisions.get(workspaceId);
    if (pending != null) {
      pending.future.complete(result);
    } else if (result.ok()) {
      LOG.warnf(
          "workspace %s: provisioning finished (head %s) after its start was given up; not adopted,"
              + " the workspace keeps the state its start left it in",
          workspaceId,
          result.head());
    } else {
      LOG.debugf(
          "workspace %s: provisioning failed after its start was given up: %s",
          workspaceId,
          result.message());
    }
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
      Consumer<String> onLine,
      BooleanSupplier abandoned) {
    // Register the pending slot BEFORE waiting, so streamed chunks and the terminal event that
    // arrive during the wait land on it (and survive a socket reconnect — the slot lives on the
    // registry, not the connection). A TARGET start registered it already (expectProvision), and
    // its terminal may be on it by now: reused here, and taken below without a liveness wait.
    PendingProvision pending =
        provisions.computeIfAbsent(workspaceId, id -> new PendingProvision());
    pending.onLine = onLine;
    try {
      // A stop that landed before this slot existed released nothing (abandonProvision never
      // creates a slot), so ask now that it does exist (qits-1076). The caller set its stop marker
      // before sending the release, so between this question and the release one of them sees it.
      if (abandoned.getAsBoolean()) {
        return Optional.of(ProvisionResult.failed(STOPPED_WHILE_PROVISIONING));
      }
      // If the terminal event somehow already arrived, take it without waiting on liveness. Else
      // wait for a daemon to dial home; if none does within the window, this is a stale image /
      // no-backend case — return empty and the caller fails the provision. A release
      // (abandonProvision) or the abandoned question ends the connect wait too, rather than leaving
      // a stopped start polling for a daemon in a container that is no longer running.
      if (!pending.future.isDone()
          && !awaitLive(workspaceId, connectTimeout, pending.future, abandoned)) {
        return Optional.empty();
      }
      if (!pending.future.isDone() && abandoned.getAsBoolean()) {
        return Optional.of(ProvisionResult.failed(STOPPED_WHILE_PROVISIONING));
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

  /** What an await a stop cut short answers, when the stop's own reason did not reach it. */
  private static final String STOPPED_WHILE_PROVISIONING =
      "the workspace was stopped while it was provisioning";

  /**
   * Complete the pending await, if there is one, with the stop's failed outcome. Unlike {@link
   * #completeProvision} this is the host speaking, not the daemon, and like it, it never creates a
   * slot: a retained outcome would be taken by the next provision cycle as its own.
   */
  @Override
  public boolean abandonProvision(Long workspaceId, String reason) {
    PendingProvision pending = provisions.get(workspaceId);
    return pending != null
        && pending.future.complete(
            ProvisionResult.failed(reason == null ? STOPPED_WHILE_PROVISIONING : reason));
  }

  /**
   * Registers the TARGET start's slot ahead of its await (qits-1600) — see {@link
   * WorkspaceDaemonProvisioner#expectProvision}. Always a fresh slot: one a previous start left (an
   * outcome it never took, or an await it abandoned) is replaced, and an await still parked on it is
   * woken with a failed outcome rather than left to its timeout. That await's own write is guarded
   * by its lease and its row's PROVISIONING, so it lands on nothing; and its {@code finally} removes
   * only its own slot, never this one.
   */
  @Override
  public ExpectedProvision expectProvision(Long workspaceId) {
    PendingProvision pending = new PendingProvision();
    PendingProvision previous = provisions.put(workspaceId, pending);
    if (previous != null) {
      previous.future.complete(ProvisionResult.failed(SUPERSEDED_BY_A_NEWER_START));
    }
    return () -> provisions.remove(workspaceId, pending);
  }

  /** What an await whose slot a newer start replaced answers. */
  private static final String SUPERSEDED_BY_A_NEWER_START =
      "a newer start of the workspace replaced this provision";

  /**
   * Test hook: whether any provision slot — awaited or only {@link #expectProvision expected} — is
   * registered for {@code workspaceId}.
   */
  boolean hasProvisionSlot(Long workspaceId) {
    return provisions.containsKey(workspaceId);
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

  /** {@link #awaitLive(Long, Duration, CompletableFuture, BooleanSupplier)} with no early exit. */
  private boolean awaitLive(Long workspaceId, Duration timeout) {
    return awaitLive(workspaceId, timeout, null, () -> false);
  }

  /**
   * Poll for a live daemon up to {@code timeout}; true once one is connected — or once {@code
   * settled} is done or {@code abandoned} answers true, so the caller goes on to read the outcome
   * that is already there instead of polling out the window (qits-1076: a stop releases the
   * provision await, and the await may still be in this phase).
   */
  private boolean awaitLive(
      Long workspaceId,
      Duration timeout,
      CompletableFuture<?> settled,
      BooleanSupplier abandoned) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (isDaemonLive(workspaceId)
          || (settled != null && settled.isDone())
          || abandoned.getAsBoolean()) {
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

}
