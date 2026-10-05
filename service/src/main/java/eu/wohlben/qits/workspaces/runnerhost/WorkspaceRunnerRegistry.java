package eu.wohlben.qits.workspaces.runnerhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.runner.protocol.Retire;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import eu.wohlben.qits.runner.protocol.Upgrade;
import eu.wohlben.qits.workspaces.control.RunnerClaims;
import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunnerCapabilities;
import eu.wohlben.qits.workspaces.error.DomainException;
import eu.wohlben.qits.workspacesrunner.protocol.Deleted;
import eu.wohlben.qits.workspacesrunner.protocol.Estate;
import eu.wohlben.qits.workspacesrunner.protocol.Exited;
import eu.wohlben.qits.workspacesrunner.protocol.HealthCheck;
import eu.wohlben.qits.workspacesrunner.protocol.HealthChecked;
import eu.wohlben.qits.workspacesrunner.protocol.HeldContainer;
import eu.wohlben.qits.workspacesrunner.protocol.Inventory;
import eu.wohlben.qits.workspacesrunner.protocol.LaunchFailed;
import eu.wohlben.qits.workspacesrunner.protocol.Launched;
import eu.wohlben.qits.workspacesrunner.protocol.LoginState;
import eu.wohlben.qits.workspacesrunner.protocol.Stopped;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.jboss.logging.Logger;

/**
 * Every workspace runner holding a socket to this process: one {@link Session} per connection, what
 * each was told, and the replies a routed verb waits for. {@link WorkspaceRunnerSocket} owns the
 * WebSocket lifecycle and forwards frames here — qits-ci's {@code CiRunnerSocket}/{@code
 * CiRunnerRegistry} split, kept for the same reason, and that registry is the exemplar throughout.
 *
 * <p><b>A session is one connection, and a runner has at most one per version.</b> A second
 * connection that says {@code hello} in the <em>same</em> version replaces the first, which is
 * closed 1008 {@link #ALREADY_CONNECTED}: the likeliest second dial is the same runner coming back
 * before this host noticed its old socket was dead, and refusing it would lock the runner out
 * behind a half-open socket.
 *
 * <p><b>Two versions side by side are a self-update.</b> A runner whose {@code hello} is not the
 * pinned version ({@link WorkspaceRunnerPins}) is sent {@link Upgrade} and {@code ack{slots: 0}}:
 * that connection is <b>draining</b>, takes nothing, and keeps serving the {@code stop}s and {@code
 * delete}s sent to it. Its successor dials as a second connection; once one in the pinned version
 * has said {@code hello}, every other connection of the runner is sent {@link Retire}. Workspace
 * containers outlive the runner process, so nothing waits for a drain: the successor reports the
 * same containers in its own {@code inventory}.
 *
 * <p><b>What a greeted connection is told, in order</b> (the dossier's sequence): {@code ack} with
 * the row's slots — 0 while the runner is quarantined, followed by {@code quarantined} — then
 * {@code estate}, then {@code backlog}; and {@code healthCheck} when the runner is still awaiting
 * its first one (it is quarantined from its registration until a health check passes). {@code
 * estate} is sent again whenever the set of rows a runner owns changes, and only ever from a
 * successful read: a list that could not be read is not sent, because the runner removes whatever
 * is not on it. {@code backlog} is recounted and pushed whenever a row enters or leaves the queue,
 * debounced per runner.
 *
 * <p><b>A dropped runner is still present for the reconnect grace</b>, {@link #RECONNECT_GRACE} —
 * qits-ci's value ({@code qits.ci.runner.reconnect-grace-seconds=60}), named here as a constant. A
 * socket that blinked (the edge restarting) leaves its workspaces as they are; after the grace they
 * read UNAVAILABLE ({@link #presence}), and every client watching them is told so.
 *
 * <p><b>Nothing here waits without a deadline</b>: a frame is sent bounded at {@link
 * #SEND_TIMEOUT}, a close at {@link #CLOSE_TIMEOUT}, a routed verb's reply at the deadline its
 * caller names. The pushes ({@code backlog}, {@code estate}, a released row's {@code delete}) run on
 * this registry's own thread, never on the thread that changed a row — which may still be inside
 * the transaction that changed it.
 */
@ApplicationScoped
public class WorkspaceRunnerRegistry {

  private static final Logger LOG = Logger.getLogger(WorkspaceRunnerRegistry.class);

  /** The close code every refusal and every replaced session gets: 1008, "policy violation". */
  public static final int CLOSE_POLICY = RunnerWire.CLOSE_POLICY_VIOLATION;

  /** The close reason a session replaced by a newer dial of the same runner is given. */
  public static final String ALREADY_CONNECTED = "ALREADY_CONNECTED";

  /** How long a dropped runner still counts as present: qits-ci's runner reconnect grace. */
  public static final Duration RECONNECT_GRACE = Duration.ofSeconds(60);

  /** How long one frame may take to leave — qits-ci's number. */
  static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

  /** How long a close may take before the host stops caring. */
  static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

  /** How often a heartbeat may reach the row: {@code last_seen_at} is read at a minute's grain. */
  static final Duration SEEN_INTERVAL = Duration.ofMinutes(1);

  /** How long a backlog change waits for the ones right behind it before the count is pushed. */
  static final Duration BACKLOG_DEBOUNCE = Duration.ofMillis(250);

  /** The source a greenlight by a passing health check is reported with in {@code reinstated}. */
  static final String HEALTH_CHECK = "health check";

  @Inject WorkspaceRunnerMessageCodec codec;

  @Inject WorkspaceRunners runners;

  @Inject RunnerClaims claims;

  @Inject WorkspaceRunnerPins pins;

  @Inject WorkspaceRunnerAddresses addresses;

  @Inject ObjectMapper objectMapper;

  /** Every open session of each runner, oldest first; mutated only inside {@code compute}. */
  private final ConcurrentHashMap<UUID, List<Session>> sessions = new ConcurrentHashMap<>();

  /** Admission order, so "the newest session" does not depend on a clock's resolution. */
  private final AtomicLong admitted = new AtomicLong();

  /** Since when each connected runner has held at least one socket without a break. */
  private final ConcurrentHashMap<UUID, Instant> connectedSince = new ConcurrentHashMap<>();

  /** When each runner lost its last socket; the grace runs from here. */
  private final ConcurrentHashMap<UUID, Instant> droppedAt = new ConcurrentHashMap<>();

  /** The routed verbs waiting for their reply, by {@link #replyKey}. True answered, false lost. */
  private final ConcurrentHashMap<String, CompletableFuture<Boolean>> replies =
      new ConcurrentHashMap<>();

  /** The runners with a backlog push already scheduled: the debounce. */
  private final Set<UUID> backlogScheduled = ConcurrentHashMap.newKeySet();

  /** The pushes, the grace expiries and the debounced recounts. Daemon threads. */
  private final ScheduledExecutorService pushes =
      Executors.newScheduledThreadPool(
          2,
          r -> {
            Thread t = new Thread(r, "workspaces-runner-push");
            t.setDaemon(true);
            return t;
          });

  private volatile Duration seenInterval = SEEN_INTERVAL;

  private volatile Duration reconnectGrace = RECONNECT_GRACE;

  /** A suite proving a heartbeat reaches the row cannot wait a minute. */
  void seenInterval(Duration interval) {
    this.seenInterval = interval;
  }

  /** A suite proving the grace runs out cannot wait a minute either. */
  void reconnectGrace(Duration grace) {
    this.reconnectGrace = grace;
  }

  /** One runner's connection. */
  public static final class Session {

    private final WorkspaceRunner runner;
    private final UUID runnerId;
    private final String runnerName;
    private final WebSocketConnection connection;
    private final long order;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private volatile boolean greeted;
    private volatile boolean draining;
    private volatile String runnerVersion;
    private volatile Instant seenWrittenAt;

    Session(WorkspaceRunner runner, WebSocketConnection connection, long order) {
      this.runner = runner;
      this.runnerId = runner.id;
      this.runnerName = runner.name;
      this.connection = connection;
      this.order = order;
    }

    /** The row this session was admitted as — detached, read once at the dial. */
    public WorkspaceRunner runner() {
      return runner;
    }

    public UUID runnerId() {
      return runnerId;
    }

    public String runnerName() {
      return runnerName;
    }

    /** Whether its {@code hello} was answered with slots: the pinned version, not draining. */
    public boolean greeted() {
      return greeted;
    }

    /** Whether it was told to {@link Upgrade}: it takes nothing more. */
    public boolean draining() {
      return draining;
    }

    /** What its {@code hello} said it is; null before one. */
    public String runnerVersion() {
      return runnerVersion;
    }

    public boolean isOpen() {
      return !closed.isDone();
    }
  }

  /** How a {@code hello} was received. */
  public enum Greeting {
    GREETED,
    /** The pinned version speaking another capability: nothing to update it to; closed 1008. */
    VERSION_MISMATCH,
    /** The row went away between the dial and the {@code hello}. */
    RUNNER_GONE
  }

  /** What became of a routed verb. */
  public enum Reply {
    /** The runner answered. */
    ANSWERED,
    /** No connection of the runner could be asked, or the last one dropped before answering. */
    UNAVAILABLE,
    /** Asked, and no answer inside the deadline. */
    TIMEOUT
  }

  // --- the socket side --------------------------------------------------------------------------

  /**
   * Bind a connection to its runner, beside any session it already has — which one stays is decided
   * at its {@code hello}, where its version is known. The row was resolved from the bearer by the
   * caller.
   */
  public Session admit(WorkspaceRunner runner, WebSocketConnection connection) {
    Session fresh = new Session(runner, connection, admitted.incrementAndGet());
    boolean[] first = {false};
    sessions.compute(
        runner.id,
        (id, present) -> {
          List<Session> next = new ArrayList<>();
          if (present != null) {
            present.stream().filter(Session::isOpen).forEach(next::add);
          }
          first[0] = next.isEmpty();
          next.add(fresh);
          return List.copyOf(next);
        });
    if (first[0]) {
      connectedSince.put(runner.id, Instant.now());
      droppedAt.remove(runner.id);
      // Its rows may have read UNAVAILABLE; whoever watches them reads them again.
      async(() -> claims.announceOwned(runner.id), "announce runner " + runner.name + " back");
    }
    LOG.infof("Runner %s (%s) connected (connection %s)", runner.name, runner.id, connection.id());
    return fresh;
  }

  /**
   * The runner said who it is: the version against the pin, then the capability, then what it said
   * is recorded, the same-version rule settled, and it is answered — {@link Upgrade} and {@code
   * ack{0}} for a runner not at the pin, else {@code ack}, {@code quarantined} if it is, {@code
   * estate}, {@code backlog} and, while it awaits its first one, {@code healthCheck}. Every other
   * version's connection of the runner is retired after.
   *
   * <p>The slots are the row's, never the runner's ({@link Hello#slots()} is what its operator put
   * on the machine, and advisory), and 0 while it is quarantined.
   */
  public Greeting onHello(Session session, Hello hello) {
    String pin = pins.version();
    boolean current = pin.equals(hello.runnerVersion());
    boolean speaks = hello.capabilityVersion() == WorkspacesRunnerProtocol.CAPABILITY_VERSION;
    if (current && !speaks) {
      LOG.warnf(
          "Runner %s announced capability version %d and this host speaks %d — refusing it",
          session.runnerName, hello.capabilityVersion(), WorkspacesRunnerProtocol.CAPABILITY_VERSION);
      return Greeting.VERSION_MISMATCH;
    }
    WorkspaceRunner row = runners.recordCapabilities(session.runnerId, said(hello, speaks));
    if (row == null) {
      return Greeting.RUNNER_GONE;
    }
    session.seenWrittenAt = Instant.now();
    session.runnerVersion = hello.runnerVersion();
    session.draining = !current;
    List<String> adopted = adopted(session, hello.held());
    List<Session> retiring = settle(session);
    if (!current) {
      String image;
      try {
        image = addresses.runnerImage(pin);
      } catch (DomainException unconfigured) {
        LOG.errorf(
            "Runner %s said hello as %s and the pin is %s, and it cannot be upgraded: %s",
            row.name, hello.runnerVersion(), pin, unconfigured.getMessage());
        if (speaks) {
          send(session, ack(0, adopted));
        }
        return Greeting.GREETED;
      }
      LOG.infof(
          "Runner %s said hello as %s and the pin is %s — upgrading it to %s; this connection"
              + " takes nothing more",
          row.name, hello.runnerVersion(), pin, image);
      send(session, new Upgrade(pin, image, null));
      if (speaks) {
        send(session, ack(0, adopted));
      }
      return Greeting.GREETED;
    }
    int slots = slots(row);
    LOG.infof(
        "Runner %s said hello: %s, %d slot(s)%s",
        row.name,
        hello.runnerVersion(),
        slots,
        row.quarantined() ? " — quarantined: " + row.quarantineReason : "");
    send(session, ack(slots, adopted));
    if (row.quarantined()) {
      send(session, quarantinedFrame(row.quarantineReason, row.quarantinedAt));
    }
    session.greeted = true;
    sendEstate(session);
    sendBacklog(session);
    if (row.quarantined()
        && WorkspaceRunners.AWAITING_FIRST_HEALTH_CHECK.equals(row.quarantineReason)) {
      send(session, new HealthCheck());
    }
    catchUp(session, row, slots);
    for (Session old : retiring) {
      LOG.infof(
          "Runner %s's %s connection %s is superseded by %s; retiring it",
          row.name, old.runnerVersion, old.connection.id(), hello.runnerVersion());
      send(old, new Retire("superseded by " + hello.runnerVersion()));
    }
    return Greeting.GREETED;
  }

  /** What a {@code hello} says about the machine, as the capabilities column keeps it. */
  private ObjectNode said(Hello hello, boolean speaks) {
    ObjectNode said = objectMapper.createObjectNode();
    said.put(WorkspaceRunnerCapabilities.VERSION, hello.runnerVersion());
    RunnerCapabilities machine = speaks ? hello.capabilities() : null;
    if (machine != null) {
      said.put(RunnerWire.Field.DOCKER, machine.docker());
      said.put(RunnerWire.Field.ARCH, machine.arch());
      said.put(RunnerWire.Field.OS, machine.os());
    }
    return said;
  }

  /**
   * The containers a {@code hello} claims that this runner owns rows for, answered in the {@code
   * ack}; null — "nothing adopted" — when the rows could not be read. A carried container the host
   * does not adopt is only a slot the runner stops counting; its next {@code estate} decides it.
   */
  private List<String> adopted(Session session, List<String> held) {
    if (held == null || held.isEmpty()) {
      return List.of();
    }
    try {
      Set<String> owned =
          Set.copyOf(claims.ownedRowIds(session.runnerId).stream().map(String::valueOf).toList());
      return held.stream().filter(owned::contains).toList();
    } catch (RuntimeException e) {
      LOG.debugf("Could not read runner %s's rows to adopt: %s", session.runnerName, e.getMessage());
      return null;
    }
  }

  /**
   * The same-version rule, once a session has said which version it is: every other open session
   * of the runner in the same version is replaced, closed {@link #ALREADY_CONNECTED}. Returned are
   * the sessions of other versions when this one is at the pin — the ones to {@link Retire}. A
   * session that has not said {@code hello} yet is left alone.
   */
  private List<Session> settle(Session session) {
    List<Session> replaced = new ArrayList<>();
    List<Session> retiring = new ArrayList<>();
    sessions.computeIfPresent(
        session.runnerId,
        (id, present) -> {
          List<Session> kept = new ArrayList<>();
          for (Session other : present) {
            if (other == session || other.runnerVersion == null) {
              kept.add(other);
            } else if (other.runnerVersion.equals(session.runnerVersion)) {
              replaced.add(other);
            } else {
              kept.add(other);
              if (!session.draining) {
                retiring.add(other);
              }
            }
          }
          return kept.isEmpty() ? null : List.copyOf(kept);
        });
    for (Session previous : replaced) {
      LOG.warnf(
          "Runner %s said hello again as %s on connection %s; closing its previous connection %s"
              + " %s",
          session.runnerName,
          session.runnerVersion,
          session.connection.id(),
          previous.connection.id(),
          ALREADY_CONNECTED);
      previous.closed.complete(null);
      closeBounded(
          previous.connection,
          new CloseReason(CLOSE_POLICY, ALREADY_CONNECTED),
          "the replaced connection of runner " + session.runnerName);
    }
    return retiring;
  }

  /** Liveness: the socket is the signal, and the row hears about it at most once a minute. */
  public void onHeartbeat(Session session) {
    Instant now = Instant.now();
    Instant written = session.seenWrittenAt;
    if (written != null && written.plus(seenInterval).isAfter(now)) {
      return;
    }
    session.seenWrittenAt = now;
    try {
      runners.touchSeen(session.runnerId);
    } catch (RuntimeException e) {
      LOG.debugf("Could not stamp runner %s as seen: %s", session.runnerName, e.getMessage());
    }
  }

  /**
   * What the runner holds: its agent home volume is recorded, and the rows it owns are reconciled
   * with the containers it holds ({@link RunnerClaims#reconcile} — which recounts the backlog
   * itself when it requeues one).
   */
  public void onInventory(Session session, Inventory inventory) {
    if (inventory.dotClaudeVolume() != null && !inventory.dotClaudeVolume().isBlank()) {
      ObjectNode said = objectMapper.createObjectNode();
      said.put(WorkspaceRunnerCapabilities.DOT_CLAUDE_VOLUME, inventory.dotClaudeVolume());
      record(session, said);
    }
    List<RunnerClaims.HeldContainer> held = new ArrayList<>();
    for (HeldContainer container : inventory.containers()) {
      held.add(new RunnerClaims.HeldContainer(container.rowId(), container.running()));
    }
    try {
      List<Long> changed = claims.reconcile(session.runnerId, held);
      if (!changed.isEmpty()) {
        LOG.infof("Runner %s's inventory moved workspace(s) %s", session.runnerName, changed);
      }
    } catch (RuntimeException e) {
      LOG.warnf("Runner %s's inventory was not reconciled: %s", session.runnerName, e.getMessage());
    }
  }

  /** The node's agent login, as last probed: kept on the row while the runner is offline. */
  public void onLoginState(Session session, LoginState login) {
    ObjectNode said = objectMapper.createObjectNode();
    ObjectNode state = said.putObject(WorkspaceRunnerCapabilities.LOGIN);
    state.put("claude", login.claude().name());
    state.put("kimi", login.kimi().name());
    state.put("checkedAt", login.checkedAt());
    record(session, said);
  }

  /**
   * A health check settled. It is recorded first, whatever follows; a pass lifts a quarantine
   * ({@code reinstated}, then {@code ack} with the row's slots and a fresh {@code backlog}), a
   * failure begins one ({@code quarantined}, then {@code ack{0}}).
   */
  public void onHealthChecked(Session session, HealthChecked checked) {
    WorkspaceRunner row = runners.recordHealthCheck(session.runnerId, checked.ok(), Instant.now());
    if (row == null) {
      return;
    }
    if (checked.ok()) {
      if (row.quarantined()) {
        runners.greenlight(session.runnerId);
        LOG.infof("Runner %s passed its health check; it takes workspaces now", row.name);
        reinstated(session.runnerId, HEALTH_CHECK);
      }
      return;
    }
    String detail = checked.detail() == null || checked.detail().isBlank() ? "" : checked.detail();
    String reason = "health check failed" + (detail.isEmpty() ? "" : ": " + detail);
    WorkspaceRunner quarantined = runners.quarantine(session.runnerId, truncate(reason));
    LOG.warnf("Runner %s failed its health check: %s", row.name, detail);
    if (quarantined != null) {
      quarantined(session.runnerId, quarantined.quarantineReason, quarantined.quarantinedAt);
    }
  }

  public void onLaunched(Session session, Launched launched) {
    claims.launched(session.runnerId, launched.rowId());
  }

  public void onLaunchFailed(Session session, LaunchFailed failed) {
    claims.launchFailed(session.runnerId, failed.rowId(), failed.detail());
  }

  public void onExited(Session session, Exited exited) {
    claims.exited(session.runnerId, exited.rowId());
  }

  /** The answer to a routed {@code stop}: the row is STOPPED, and whoever waits is released. */
  public void onStopped(Session session, Stopped stopped) {
    claims.stopped(session.runnerId, stopped.rowId());
    answer(session.runnerId, "stop", stopped.rowId());
  }

  /** The answer to a {@code delete}: the row is STOPPED on no runner. */
  public void onDeleted(Session session, Deleted deleted) {
    claims.deleted(session.runnerId, deleted.rowId());
    answer(session.runnerId, "delete", deleted.rowId());
    estateChanged(session.runnerId);
  }

  /**
   * A connection went away. Only its own session is dropped — a replaced connection closing late
   * must not take the one that replaced it. When it was the runner's last, the grace starts: its
   * waiting verbs are answered as lost, and once the grace is out its rows read UNAVAILABLE.
   */
  public void onClose(Session session) {
    session.closed.complete(null);
    boolean[] last = {false};
    sessions.computeIfPresent(
        session.runnerId,
        (id, present) -> {
          List<Session> kept = new ArrayList<>(present);
          kept.remove(session);
          kept.removeIf(other -> !other.isOpen());
          last[0] = kept.isEmpty();
          return kept.isEmpty() ? null : List.copyOf(kept);
        });
    if (!last[0] || sessions.containsKey(session.runnerId)) {
      return;
    }
    LOG.infof(
        "Runner %s (%s) disconnected (connection %s); it is waited for %ss",
        session.runnerName,
        session.runnerId,
        session.connection.id(),
        reconnectGrace.toSeconds());
    connectedSince.remove(session.runnerId);
    Instant dropped = Instant.now();
    droppedAt.put(session.runnerId, dropped);
    String prefix = session.runnerId + "/";
    replies.forEach(
        (key, pending) -> {
          if (key.startsWith(prefix)) {
            pending.complete(false);
          }
        });
    Duration grace = reconnectGrace;
    pushes.schedule(
        () -> expire(session.runnerId, session.runnerName, dropped),
        Math.max(0, grace.toMillis()),
        TimeUnit.MILLISECONDS);
  }

  /** The grace ran out; unless the runner came back, its rows read UNAVAILABLE from now on. */
  private void expire(UUID runnerId, String name, Instant dropped) {
    if (connected(runnerId) || !dropped.equals(droppedAt.get(runnerId))) {
      return;
    }
    LOG.warnf("Runner %s did not come back within %ss; its workspaces are unavailable", name,
        reconnectGrace.toSeconds());
    try {
      claims.announceOwned(runnerId);
    } catch (RuntimeException e) {
      LOG.debugf("Could not announce runner %s's workspaces: %s", name, e.getMessage());
    }
  }

  // --- presence -----------------------------------------------------------------------------------

  /** Whether the runner holds at least one open socket right now. */
  public boolean connected(UUID runnerId) {
    return runnerId != null && !open(runnerId).isEmpty();
  }

  /** Since when it has held one without a break; null while it holds none. */
  public Instant connectedSince(UUID runnerId) {
    return runnerId == null || !connected(runnerId) ? null : connectedSince.get(runnerId);
  }

  /** Connected, or dropped less than the reconnect grace ago. */
  public boolean presence(UUID runnerId) {
    if (connected(runnerId)) {
      return true;
    }
    Instant dropped = runnerId == null ? null : droppedAt.get(runnerId);
    return dropped != null && dropped.plus(reconnectGrace).isAfter(Instant.now());
  }

  /** Every runner with at least one open connection, in memory and with no row read. */
  public Set<UUID> connectedRunnerIds() {
    Set<UUID> connected = new java.util.HashSet<>();
    for (UUID runnerId : List.copyOf(sessions.keySet())) {
      if (connected(runnerId)) {
        connected.add(runnerId);
      }
    }
    return Set.copyOf(connected);
  }

  /**
   * The runner's current session: greeted in the pinned version when there is one, else its newest
   * open session, else null. Where a frame for the runner rather than for one connection goes.
   */
  public Session current(UUID runnerId) {
    List<Session> open = open(runnerId);
    return open.stream()
        .filter(s -> s.greeted && !s.draining)
        .findFirst()
        .orElseGet(() -> open.isEmpty() ? null : open.get(open.size() - 1));
  }

  List<Session> open(UUID runnerId) {
    List<Session> present = runnerId == null ? null : sessions.get(runnerId);
    if (present == null) {
      return List.of();
    }
    return present.stream()
        .filter(Session::isOpen)
        .sorted(Comparator.comparingLong(s -> s.order))
        .toList();
  }

  // --- the signals: what an operator, a health check or a row change tells a runner --------------

  /**
   * Ask the runner something and wait for its answer: {@code frame} goes to its {@link #current}
   * session, and the reply is whichever {@code verb} answer for {@code rowId} arrives on any of its
   * connections first. Two callers asking the same of the same row share one wait.
   */
  public Reply request(UUID runnerId, String verb, long rowId, RunnerMessage frame, Duration deadline) {
    Session session = current(runnerId);
    if (session == null) {
      return Reply.UNAVAILABLE;
    }
    String key = replyKey(runnerId, verb, rowId);
    CompletableFuture<Boolean> pending = replies.computeIfAbsent(key, k -> new CompletableFuture<>());
    try {
      if (!send(session, frame)) {
        pending.complete(false);
      }
      Boolean answered = await(pending, deadline);
      if (answered == null) {
        return Reply.TIMEOUT;
      }
      return answered ? Reply.ANSWERED : Reply.UNAVAILABLE;
    } finally {
      replies.remove(key, pending);
    }
  }

  private void answer(UUID runnerId, String verb, long rowId) {
    CompletableFuture<Boolean> pending = replies.get(replyKey(runnerId, verb, rowId));
    if (pending != null) {
      pending.complete(true);
    }
  }

  private static String replyKey(UUID runnerId, String verb, long rowId) {
    return runnerId + "/" + verb + "/" + rowId;
  }

  /** Send {@code frame} to the runner's current session, if it has one. False when none took it. */
  public boolean send(UUID runnerId, RunnerMessage frame) {
    Session session = current(runnerId);
    return session != null && send(session, frame);
  }

  /** {@link #send(UUID, RunnerMessage)} on this registry's thread: for a push nobody waits on. */
  public void sendLater(UUID runnerId, RunnerMessage frame) {
    if (runnerId != null) {
      async(() -> send(runnerId, frame), frame.getClass().getSimpleName() + " to " + runnerId);
    }
  }

  /** The quarantine was lifted: {@code reinstated}, then {@code ack} with the row's slots again. */
  public void reinstated(UUID runnerId, String by) {
    Session session = serving(runnerId);
    if (session != null) {
      send(session, new Reinstated(by));
      reAck(session);
    }
  }

  /** The runner was quarantined: {@code quarantined}, then {@code ack{0}}. */
  public void quarantined(UUID runnerId, String reason, Instant since) {
    Session session = serving(runnerId);
    if (session != null) {
      send(session, quarantinedFrame(reason, since));
      reAck(session);
    }
  }

  /** What it may hold moved (an operator's slots), so its {@code ack} is sent again. */
  public void slotsChanged(UUID runnerId) {
    Session session = serving(runnerId);
    if (session != null) {
      reAck(session);
    }
  }

  /**
   * The runner's row was deleted: every open connection of it is sent {@code retire{DELETED}}, on
   * which the runner removes its own namespace from its node, and is closed 1008 {@code
   * RUNNER_DELETED} — the frame first, so it is read first.
   */
  public void deleted(UUID runnerId) {
    for (Session session : open(runnerId)) {
      LOG.infof(
          "Runner %s (%s) was deleted; retiring its connection %s for good",
          session.runnerName, session.runnerId, session.connection.id());
      send(session, Retire.deleted("the runner was deleted"));
      closeBounded(
          session.connection,
          new CloseReason(CLOSE_POLICY, RunnerWire.CloseReason.RUNNER_DELETED),
          "the connection of deleted runner " + session.runnerName);
    }
    droppedAt.remove(runnerId);
  }

  /**
   * The rows {@code runnerId} owns changed: its greeted connections are sent a fresh {@code
   * estate}, on this registry's thread.
   */
  public void estateChanged(UUID runnerId) {
    if (runnerId == null) {
      return;
    }
    async(
        () -> open(runnerId).stream().filter(s -> s.greeted).forEach(this::sendEstate),
        "the estate of runner " + runnerId);
  }

  /**
   * The queue changed around a row of {@code runnerId}'s, or around a row on no runner when it is
   * null (every connected runner may take that one). Each runner's backlog is recounted and pushed
   * once the debounce is out, so a burst of changes is one frame.
   */
  public void backlogChanged(UUID runnerId) {
    List<UUID> targets = runnerId == null ? List.copyOf(sessions.keySet()) : List.of(runnerId);
    for (UUID target : targets) {
      if (backlogScheduled.add(target)) {
        pushes.schedule(
            () -> {
              backlogScheduled.remove(target);
              open(target).stream().filter(s -> s.greeted && !s.draining).forEach(this::sendBacklog);
            },
            BACKLOG_DEBOUNCE.toMillis(),
            TimeUnit.MILLISECONDS);
      }
    }
  }

  // --- frames -------------------------------------------------------------------------------------

  /**
   * {@code estate{owned, workspaceImage}}, from a successful read only: a read that failed sends
   * nothing, because the runner removes what is not listed. An image this deployment cannot name is
   * sent as null; the runner then pulls nothing ahead.
   */
  void sendEstate(Session session) {
    List<Long> owned;
    try {
      owned = claims.ownedRowIds(session.runnerId);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not read runner %s's rows; no estate sent: %s",
          session.runnerName, e.getMessage());
      return;
    }
    String image;
    try {
      image = addresses.workspaceImage();
    } catch (DomainException unconfigured) {
      image = null;
    }
    send(session, new Estate(owned, image));
  }

  private void sendBacklog(Session session) {
    long queued;
    try {
      queued = claims.backlogFor(session.runnerId);
    } catch (RuntimeException e) {
      LOG.debugf("Could not count runner %s's backlog: %s", session.runnerName, e.getMessage());
      return;
    }
    send(session, new Backlog((int) Math.min(Integer.MAX_VALUE, queued)));
  }

  /**
   * A runner learns its slots only from an {@code ack}, so every change of what a greeted runner
   * may hold re-sends one with the value it has now, then {@code backlog}, which un-parks a runner
   * that was answered {@code nothing} earlier.
   */
  private void reAck(Session session) {
    WorkspaceRunner row;
    try {
      row = runners.get(session.runnerId);
    } catch (RuntimeException gone) {
      return;
    }
    int slots = slots(row);
    LOG.infof("Runner %s may hold %d workspace(s) now; re-sending its ack", row.name, slots);
    send(session, ack(slots, null));
    sendBacklog(session);
  }

  /** A quarantine or slot change that landed between the hello's row read and its greeting. */
  private void catchUp(Session session, WorkspaceRunner answered, int ackedSlots) {
    WorkspaceRunner now;
    try {
      now = runners.get(session.runnerId);
    } catch (RuntimeException gone) {
      return;
    }
    boolean moved = false;
    if (now.quarantined() && !answered.quarantined()) {
      send(session, quarantinedFrame(now.quarantineReason, now.quarantinedAt));
      moved = true;
    }
    if (moved || slots(now) != ackedSlots) {
      reAck(session);
    }
  }

  /** The session a runner's slots are granted on — greeted, pinned, open — or null. */
  private Session serving(UUID runnerId) {
    Session session = current(runnerId);
    return session != null && session.greeted && !session.draining && session.isOpen()
        ? session
        : null;
  }

  private static int slots(WorkspaceRunner row) {
    return row.quarantined() ? 0 : Math.max(0, row.slots);
  }

  private static Ack ack(int slots, List<String> adopted) {
    return new Ack(WorkspacesRunnerProtocol.CAPABILITY_VERSION, slots, adopted, Map.of());
  }

  private static Quarantined quarantinedFrame(String reason, Instant since) {
    return new Quarantined(
        reason == null ? "" : reason, since == null ? Instant.now().toString() : since.toString());
  }

  private void record(Session session, ObjectNode said) {
    try {
      runners.recordCapabilities(session.runnerId, said);
    } catch (RuntimeException e) {
      LOG.warnf("Runner %s's report was not recorded: %s", session.runnerName, e.getMessage());
    }
  }

  /** Send one frame on a session, bounded. False when it could not leave. */
  public boolean send(Session session, RunnerMessage message) {
    WebSocketConnection connection = session.connection;
    if (!session.isOpen() || !connection.isOpen()) {
      LOG.debugf(
          "No live socket for runner %s — dropped %s",
          session.runnerName, message.getClass().getSimpleName());
      return false;
    }
    try {
      connection.sendText(codec.encode(message)).await().atMost(SEND_TIMEOUT);
      return true;
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not send %s to runner %s: %s",
          message.getClass().getSimpleName(), session.runnerName, e.getMessage());
      return false;
    }
  }

  private void async(Runnable work, String what) {
    try {
      pushes.execute(
          () -> {
            try {
              work.run();
            } catch (RuntimeException e) {
              LOG.debugf("%s failed: %s", what, e.getMessage());
            }
          });
    } catch (RuntimeException rejected) {
      LOG.debugf("%s was not scheduled: %s", what, rejected.getMessage());
    }
  }

  @PreDestroy
  void stop() {
    pushes.shutdownNow();
  }

  /** The one place a future is waited on here; null when the deadline passed first. */
  static <T> T await(CompletableFuture<T> future, Duration timeout) {
    try {
      return future.get(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException e) {
      return null;
    }
  }

  /** A close with a deadline on it. */
  static void closeBounded(WebSocketConnection connection, CloseReason reason, String what) {
    try {
      connection.close(reason).await().atMost(CLOSE_TIMEOUT);
    } catch (RuntimeException e) {
      LOG.debugf("Closing the socket of %s did not complete: %s", what, e.getMessage());
    }
  }

  private static String truncate(String s) {
    return s.length() <= 1000 ? s : s.substring(0, 1000);
  }
}
