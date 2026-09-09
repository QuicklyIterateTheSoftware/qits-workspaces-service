package eu.wohlben.qits.workspaces.daemonhost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.workspaces.control.AgentCapabilitySink;
import eu.wohlben.qits.workspaces.control.ContainerProxyPath;
import eu.wohlben.qits.workspaces.control.WorkspaceContainerFactory;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>What a workspace container's harnesses can be configured with, carried to the catalogue that
 * fills the editor's dropdowns.</b> One read of the daemon's {@code GET /agents/available} when its
 * container starts, and one {@code PUT /projects/api/agent-capabilities} with the answer.
 *
 * <p>The catalogue is keyed by <b>harness and image version</b>, and until this class existed it
 * only ever held rows a project's agent container had reported — which defeats the key: a workspace
 * container may run an entirely different image build from the project agent's, and the editor was
 * showing what some other image could do. qits-projects' own {@code agenthost/AgentCapabilityRelay}
 * is this class's sibling and the ingest door's other caller; the two differ in three places, all of
 * them below.
 *
 * <h2>Where it runs, and where it deliberately does not</h2>
 *
 * <p><b>On the daemon's {@code Hello}</b>, from {@link WorkspaceDaemonRegistry}, on a virtual
 * thread. That is the first moment the container is reachable at all — a workspace daemon binds
 * loopback and is addressable only through {@link WorkspaceTunnels}, so an open control socket is
 * the earliest evidence there is anything to ask. It is also, structurally, off every request path:
 * nothing a person presses reaches this class, so no editor read and no container ensure can be
 * slowed by it, and the process spawns the report costs happen inside the container at its own boot.
 *
 * <p><b>Not from the provision, and not from the ensure.</b> An ensure returns while the container
 * is still pulling an image; the provision returns when the checkout is populated, which is before
 * the daemon has probed anything. Nothing here may fail, slow or block a container coming up: every
 * arm ends in a log line, the whole window runs on a thread nobody holds, and the worst outcome is
 * that the editor's dropdowns keep the values they had.
 *
 * <h2>{@code Hello} is not "the daemon can answer this" — and it says "not yet" differently here</h2>
 *
 * <p>Read from {@code ControlSocket} in qits-workspace-daemon (2026-09-09), the boot sequence is:
 * {@code start()} materializes the agent configuration document, wires the services and bootstrap
 * surfaces, kicks the <b>boot self-clone onto a worker</b>, starts the reverse tunnel and dials home
 * — so {@code Hello} is sent while the clone is still running. It is that worker which, when the
 * clone lands, calls {@code startGitStatusMonitor} → <b>{@code workspaceApi.start()}</b>, the
 * loopback bind ("the read API goes up last in this sequence"), then {@code wireCommands} →
 * {@code wireAgents} → {@code reportHarnessCapabilities}, which probes the harnesses on <b>another
 * worker</b> and assigns a volatile field.
 *
 * <p>So a container start walks three states, and this class must tell them apart:
 *
 * <ol>
 *   <li>Between {@code Hello} and the bind, <b>nothing is listening</b> on the daemon's loopback
 *       port: the tunnel opens, the stream is requested, the daemon's own dial-back finds nothing,
 *       and the read fails outright. Not ready.
 *   <li>Between the bind and {@code wireAgents}, {@code WorkspaceApi} answers <b>503</b> ("Coding
 *       agents are not available yet") on every agent route while {@code agentLaunch} is null. Not
 *       ready.
 *   <li>From {@code wireAgents} until the probe worker lands — which is the state a container spends
 *       most of its first seconds in — the route answers <b>200 with an empty {@code capabilities}
 *       array</b>: the surface is served from {@code () -> harnessCapabilities}, and that field is
 *       {@code List.of()} until the probe assigns it. <b>Not ready either</b>, and this is where
 *       this class must differ from its sibling.
 * </ol>
 *
 * <p><b>That third state is the trap.</b> In qits-projects the daemon probes <em>before</em> it
 * binds, so an empty capability list there means "a daemon older than the feature" — terminal, and
 * quiet. Here it means "the probe has not landed yet" and is the <em>ordinary</em> answer on the
 * first attempt, so a relay that copied the sibling's rule would record nothing on very nearly every
 * container and never say a word about it. It is retried.
 *
 * <p>The cost of that choice is paid by a genuinely older daemon, which answers 200 with no
 * capabilities for ever: it is asked {@link #attempts} times and the window ends in a WARN. That is
 * the right way round — a window that gives up must be visible, and a daemon too old to report is a
 * fact worth one line per container start, where the alternative is silence on every container.
 *
 * <h2>Absent is quiet, malformed is loud</h2>
 *
 * <p>A <b>404</b> is a daemon that does not serve this route at all: absence, terminal, DEBUG. A
 * body that will not parse, or one the ingest door refuses (an unknown harness, a credential it will
 * not take), is <b>broken</b>: a WARN naming what it was, because that is a daemon and a host
 * disagreeing about a contract rather than a version lagging. qits-projects being unreachable is
 * neither — the report is still true and the container is still there, so it is asked again.
 *
 * <h2>The window is bounded and the give-up is loud</h2>
 *
 * <p>{@link #attempts} reads on an exponential backoff capped at {@link #retryMaxMs} — about four
 * minutes shipped — sleeping on the virtual thread this already runs on, which parks rather than
 * holding a carrier. A window that ends unanswered is a <b>WARN naming the workspace, the attempt
 * count and what the last attempt saw</b>. That line is the whole point of bounding it: a relay that
 * silently finds no tunnel for ever is the green-while-dead shape this epic exists to remove, and
 * silence is exactly how the missing half of this feature went unnoticed for a release. The
 * {@link #inFlight} guard is held for the <b>whole window</b>, so a flapping daemon reconnecting six
 * times a minute still has one read in flight.
 *
 * <h2>One blank is filled, and only one</h2>
 *
 * <p>The body is passed through <b>unchanged</b> — the ingest door was built as a relay's door and
 * its contract is the daemon's own answer, so a carrier that reshaped it would be a third place that
 * contract can drift. The single exception is {@code imageVersion} when the daemon named none (or
 * named {@code "unknown"}, its own fallback): that member is half the key the catalogue stores
 * under, this service chose the image pin the container was created from, and a report keyed on
 * nothing cannot be told apart from another build's. A daemon that names it wins, always.
 */
@ApplicationScoped
public class WorkspaceCapabilityRelay {

  private static final Logger LOG = Logger.getLogger(WorkspaceCapabilityRelay.class);

  /**
   * The daemon route this reads — appended to that container's proxy base path, because no hop in
   * this chain rewrites a path and the daemon serves its API under the address it was told is its
   * own ({@code WorkspaceContainerFactory} injects the prefix at container creation). A bare {@code
   * /agents/available} would 404 against every container this service ever made.
   */
  static final String AVAILABLE_PATH = "agents/available";

  /** What a daemon that could not name its image build says, rather than leaving the member out. */
  private static final String UNNAMED_IMAGE_VERSION = "unknown";

  @Inject WorkspaceTunnels tunnels;

  @Inject ObjectMapper json;

  @Inject WorkspaceContainerFactory containerFactory;

  /**
   * Where the report is written: qits-projects, over HTTP, because the catalogue is that service's.
   * Its own agent container's relay writes in process; this one cannot, and the round trip is the
   * module boundary rather than an accident.
   */
  @Inject AgentCapabilitySink catalogue;

  /**
   * The bearer the workspace daemon requires — this container's own, the value {@code
   * WorkspaceContainerFactory} injected and {@code ContainerProxyRoute} presents. Not the projects
   * daemon's token, which is a different peer's secret entirely.
   */
  @ConfigProperty(name = "qits.workspace.daemon-api-token", defaultValue = "qits-workspace-daemon")
  String daemonApiToken;

  /** Not where we connect — the authority we present. See {@link DaemonAgentClient}'s twin. */
  @ConfigProperty(name = "qits.workspace.daemon-api-port", defaultValue = "13338")
  int daemonApiPort;

  /**
   * How long one read may take before it is abandoned. Short: this is one hop down a loopback tunnel
   * into a container that has just said hello, and nothing waits on the answer.
   */
  @ConfigProperty(
      name = "qits.workspace.agent-capabilities.relay-timeout-ms",
      defaultValue = "10000")
  long timeoutMs;

  /** Off switch, for a daemon whose route misbehaves; the catalogue then keeps what it has. */
  @ConfigProperty(name = "qits.workspace.agent-capabilities.relay-enabled", defaultValue = "true")
  boolean enabled;

  /**
   * How many times the read may be attempted before the window is given up on, the first included.
   * Twelve with the shipped backoff is roughly four minutes, which is a boot self-clone of a wrapper
   * and its submodules with room to spare. One turns the retry off without turning the relay off.
   */
  @ConfigProperty(name = "qits.workspace.agent-capabilities.relay-attempts", defaultValue = "12")
  int attempts;

  /** The first wait after a "not yet" answer; it doubles from here. */
  @ConfigProperty(
      name = "qits.workspace.agent-capabilities.relay-retry-initial-ms",
      defaultValue = "2000")
  long retryInitialMs;

  /** The ceiling the doubling stops at, so a long clone is polled at a steady slow rate. */
  @ConfigProperty(
      name = "qits.workspace.agent-capabilities.relay-retry-max-ms",
      defaultValue = "30000")
  long retryMaxMs;

  /**
   * Workspaces with a relay in flight — <b>for the whole retry window</b>, not for one read. A guard
   * and not a cache: the far side upserts per {@code (harness, image version)}, so relaying twice
   * writes the same row twice and costs nothing but the round trip. What would hurt is a flapping
   * daemon stacking windows.
   */
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

  /** Set at shutdown so a sleeping window stops rather than reading into a closing container set. */
  private volatile boolean stopped;

  @PreDestroy
  void stop() {
    stopped = true;
  }

  /**
   * What one attempt produced. Three of the four are terminal; only {@link Kind#NOT_READY} is worth
   * asking again, and telling it from {@link Kind#ABSENT} is what keeps a daemon that does not serve
   * the route to one attempt while a daemon that is still booting is waited for.
   */
  record Outcome(Kind kind, String detail) {

    enum Kind {
      /** A report was decoded and the catalogue took it. Done. */
      RECORDED,
      /** The daemon does not serve this route. Quiet, and done. */
      ABSENT,
      /** Not this contract, or the ingest door refused it. Loud, and done. */
      BROKEN,
      /** Nothing answered, the answer said "not yet", or the catalogue could not be reached. */
      NOT_READY
    }

    static Outcome recorded(String detail) {
      return new Outcome(Kind.RECORDED, detail);
    }

    static Outcome absent(String detail) {
      return new Outcome(Kind.ABSENT, detail);
    }

    static Outcome broken(String detail) {
      return new Outcome(Kind.BROKEN, detail);
    }

    static Outcome notReady(String detail) {
      return new Outcome(Kind.NOT_READY, detail);
    }
  }

  /**
   * A daemon has said hello for {@code workspaceRowId}: go and ask it what its harnesses can do.
   *
   * <p>Returns immediately. The caller is a WebSocket frame handler on an event loop and must not be
   * given anything to wait for; the work runs on a virtual thread, the way this repo's other
   * off-thread daemon work does.
   */
  public void onDaemonHello(Long workspaceRowId) {
    if (!enabled || workspaceRowId == null) {
      return;
    }
    if (!inFlight.add(workspaceRowId)) {
      return;
    }
    Thread.ofVirtual()
        .name("workspace-capability-relay-" + workspaceRowId)
        .start(
            () -> {
              try {
                relay(workspaceRowId);
              } catch (RuntimeException e) {
                // A background read of an advisory catalogue. Nothing downstream is worth a stack
                // trace on a container start.
                LOG.warnf(
                    "Could not relay the harness capability report for workspace %s: %s",
                    workspaceRowId, e.toString());
              } finally {
                inFlight.remove(workspaceRowId);
              }
            });
  }

  /**
   * The whole window: read, and keep reading while the daemon says "not yet", up to {@link
   * #attempts}.
   *
   * <p>Package-private and synchronous so a suite standing a fake daemon behind a real tunnel can
   * drive it — {@code DaemonStreamRouteTest} — without the virtual thread and without the {@link
   * #enabled} gate that keeps the automatic firing dark under test.
   */
  void relay(Long workspaceRowId) {
    int limit = Math.max(1, attempts);
    long wait = Math.max(1L, retryInitialMs);
    Outcome last = Outcome.notReady("no attempt was made");
    for (int attempt = 1; attempt <= limit && !stopped; attempt++) {
      last = readAndIngest(workspaceRowId);
      if (last.kind() != Outcome.Kind.NOT_READY) {
        return;
      }
      if (attempt == limit || !pause(wait)) {
        break;
      }
      wait = Math.min(Math.max(1L, retryMaxMs), wait * 2);
    }
    if (stopped) {
      return;
    }
    // The one line that makes a future occurrence of this defect visible. Every arm below is
    // deliberately quiet, which is exactly how a relay comes to fill nothing for a whole release
    // without leaving a trace above DEBUG; a bounded window that ends unanswered is not quiet.
    LOG.warnf(
        "Gave up reading the harness capability report from workspace %s after %d attempt(s): %s."
            + " The catalogue keeps what it has. The next Hello from this workspace relays again.",
        workspaceRowId, Integer.valueOf(limit), last.detail());
  }

  /** One answered read: the status and the body, taken off the response in one composition. */
  private record Answer(int status, Buffer body) {}

  /** Sleep, answering whether the window may continue. */
  private boolean pause(long millis) {
    try {
      Thread.sleep(Duration.ofMillis(millis));
      return !stopped;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * One attempt: read through the tunnel, decode, record. Every arm ends in a log line and none of
   * them in an exception.
   *
   * <p>Package-private and synchronous for the tunnel suite, which drives a single attempt where the
   * retry would only slow an assertion down.
   */
  Outcome readAndIngest(Long workspaceRowId) {
    WorkspaceTunnels.TunnelOrigin origin = tunnels.originFor(workspaceRowId).orElse(null);
    if (origin == null) {
      // Retryable, and the arm that most needs to be: the tunnel is opened on demand from the live
      // control socket, so an empty answer here means the daemon has gone away again — or is too
      // old to serve a stream, in which case the next Hello from a rebuilt container asks again.
      LOG.debugf("No tunnel to workspace %s; no capability report read", workspaceRowId);
      return Outcome.notReady("no tunnel to the workspace's container");
    }
    return read(workspaceRowId, origin);
  }

  /**
   * Decode one {@code /agents/available} body and hand it to the catalogue. Package-private so the
   * answers this has to tell apart — a full report, a probe that has not landed, and something that
   * is not this contract at all — are testable without standing a container and a tunnel up.
   */
  Outcome ingest(Long workspaceRowId, String body) {
    if (body == null || body.isBlank()) {
      // NOT broken, and the distinction cost qits-projects a release cycle: measured live on their
      // relay, the daemon-not-ready window presents as a SUCCESSFUL hop with an EMPTY body — the
      // tunnel connects, the daemon's own dial-back has nothing to pipe from, and what comes back
      // is a 2xx carrying nothing. Handed to Jackson that is "No content to map due to
      // end-of-input", which reads as a contract violation and is terminal, so the retry never
      // engaged and the catalogue was never filled. A body that is absent says nothing at all
      // about capabilities and can never be the reason to stop asking.
      LOG.debugf(
          "workspace %s answered %s with an empty body; asking again", workspaceRowId,
          AVAILABLE_PATH);
      return Outcome.notReady("the daemon answered with an empty body");
    }
    JsonNode report;
    try {
      report = json.readTree(body);
    } catch (Exception malformed) {
      LOG.warnf(
          "workspace %s answered %s with a body this service cannot read as a capability report: %s",
          workspaceRowId, AVAILABLE_PATH, malformed.toString());
      return Outcome.broken("the body is not a capability report: " + malformed);
    }
    if (report == null || !report.isObject()) {
      LOG.warnf(
          "workspace %s answered %s with a body that is not an object", workspaceRowId,
          AVAILABLE_PATH);
      return Outcome.broken("the body is not a capability report object");
    }
    JsonNode capabilities = report.get("capabilities");
    if (capabilities == null || !capabilities.isArray() || capabilities.isEmpty()) {
      // NOT the sibling's rule, and the difference is the point: this daemon serves the route from
      // a volatile field that is List.of() until its probe worker lands, so an empty list is the
      // ordinary answer for the first seconds of every container. Terminal here would mean
      // recording nothing on nearly every container, silently.
      LOG.debugf(
          "workspace %s has not reported its harness capabilities yet (the boot probe has not"
              + " landed); asking again",
          workspaceRowId);
      return Outcome.notReady("the daemon named no capabilities yet");
    }
    String outbound = withImageVersion(body, (ObjectNode) report);
    AgentCapabilitySink.Ingest ingested = catalogue.ingest(outbound);
    return switch (ingested.result()) {
      case RECORDED -> {
        LOG.infof(
            "Recorded workspace %s's harness capability report (%d harness(es))",
            workspaceRowId, Integer.valueOf(capabilities.size()));
        yield Outcome.recorded(ingested.detail());
      }
      case REFUSED -> {
        // The door refuses an unknown harness and a credential it will not take. Both are two sides
        // disagreeing, which is loud by the same rule that makes an unparseable body loud.
        LOG.warnf(
            "workspace %s's capability report was refused by qits-projects: %s",
            workspaceRowId, ingested.detail());
        yield Outcome.broken(ingested.detail());
      }
      case UNREACHABLE -> {
        LOG.debugf(
            "workspace %s's capability report could not be delivered: %s",
            workspaceRowId, ingested.detail());
        yield Outcome.notReady(ingested.detail());
      }
      case NOT_CONFIGURED -> {
        LOG.debugf(
            "workspace %s's capability report has nowhere to go: %s",
            workspaceRowId, ingested.detail());
        yield Outcome.absent(ingested.detail());
      }
    };
  }

  /**
   * The body as it will be sent: the daemon's own bytes, unless it could not name the image build.
   *
   * <p>The one edit this class makes, and it is made only when the daemon left the member blank or
   * spelled its own {@code "unknown"} fallback. The image version is half the catalogue's key and
   * this service chose the pin the container was created from, so filling it is naming what we
   * already know rather than an opinion about capabilities.
   */
  private String withImageVersion(String body, ObjectNode report) {
    JsonNode named = report.get("imageVersion");
    String value = named == null || named.isNull() ? "" : named.asText("");
    if (!value.isBlank() && !UNNAMED_IMAGE_VERSION.equals(value)) {
      return body;
    }
    String pin = containerFactory.imageVersion();
    if (pin == null || pin.isBlank()) {
      return body;
    }
    report.put("imageVersion", pin);
    return report.toString();
  }

  /**
   * One GET through the tunnel, classified.
   *
   * <p>The tunnel's own client is used and never a shared one, for the reason {@link
   * WorkspaceTunnels} spells out: an ephemeral port is reused, and a pooled connection behind a
   * shared client is how one workspace's request lands in another's container. {@code setServer} is
   * where we connect and {@code setHost}/{@code setPort} are what we claim to be calling, so the
   * authority stays {@code localhost:<daemon port>} exactly as {@code ContainerProxyRoute} pins it —
   * the daemon must not be able to tell how a request reached it.
   *
   * <p><b>The status split is the classification.</b> A <b>404</b> is a daemon that does not serve
   * this route: absence, terminal. Anything else non-2xx is <b>not ready</b> — the daemon's own API
   * answers 503 on every agent route between its bind and {@code wireAgents}. A hop that failed
   * outright is what an unbound loopback API looks like from here, and is not ready for the same
   * reason.
   */
  private Outcome read(Long workspaceRowId, WorkspaceTunnels.TunnelOrigin origin) {
    String path = ContainerProxyPath.base(workspaceRowId) + AVAILABLE_PATH;
    try {
      // COMPOSED AND AWAITED ONCE, and that is not style — it is this repository's own measured
      // lesson, carried in DaemonAgentClient.send. Awaiting the response and then asking it for its
      // body is two blocking steps with an event loop running between them, and by the time the
      // second is reached the response may already have been delivered and dropped: body() never
      // completes and the read sits on its whole timeout while the daemon has in fact answered.
      // Under the retry that reads as "not yet" and costs a whole extra attempt.
      Answer answer =
          origin
              .client()
              .request(
                  new RequestOptions()
                      .setMethod(HttpMethod.GET)
                      .setServer(SocketAddress.inetSocketAddress(origin.port(), "127.0.0.1"))
                      .setHost("localhost")
                      .setPort(Integer.valueOf(daemonApiPort))
                      .setURI(path)
                      .putHeader("Authorization", "Bearer " + daemonApiToken)
                      .setTimeout(timeoutMs))
              .compose(request -> request.send())
              .compose(
                  response ->
                      response.body().map(body -> new Answer(response.statusCode(), body)))
              .toCompletionStage()
              .toCompletableFuture()
              .get(timeoutMs, TimeUnit.MILLISECONDS);
      Buffer buffer = answer.body();
      int status = answer.status();
      if (status == 404) {
        LOG.debugf(
            "workspace %s does not serve %s (404); no capability report to record",
            workspaceRowId, AVAILABLE_PATH);
        return Outcome.absent("the daemon answered 404 for " + AVAILABLE_PATH);
      }
      if (status / 100 != 2) {
        LOG.debugf(
            "workspace %s answered %d for %s; its agent surface is not up yet",
            workspaceRowId, Integer.valueOf(status), AVAILABLE_PATH);
        return Outcome.notReady("the daemon answered " + status + " for " + AVAILABLE_PATH);
      }
      if (buffer == null) {
        return Outcome.notReady("the daemon answered " + status + " with no body");
      }
      return ingest(workspaceRowId, buffer.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Outcome.notReady("interrupted");
    } catch (Exception e) {
      LOG.debugf(
          "Could not read %s from workspace %s: %s", AVAILABLE_PATH, workspaceRowId, e.toString());
      return Outcome.notReady("could not reach the daemon: " + e);
    }
  }
}
