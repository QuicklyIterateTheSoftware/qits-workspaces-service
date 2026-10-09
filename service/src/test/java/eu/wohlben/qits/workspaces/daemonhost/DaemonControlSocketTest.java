package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.AgentActivityState;
import eu.wohlben.qits.workspaces.control.QitsConfig;
import eu.wohlben.qits.workspaces.control.WorkspaceBootstrapDriver;
import eu.wohlben.qits.workspaces.control.WorkspaceConfigView;
import eu.wohlben.qits.workspaces.control.WorkspaceDaemonInfo;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.control.WorkspaceChangeHint;
import eu.wohlben.qits.workspaces.control.WorkspaceChangeHint.Topic;
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
import eu.wohlben.qits.workspacedaemon.protocol.GitStatus;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.PullBranch;
import eu.wohlben.qits.workspacedaemon.protocol.RunBootstrap;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceInfo;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Proves the workspace-daemon control plane end-to-end in-JVM (docs/epics/qits-workspace-daemon/)
 * with a fake Vert.x WebSocket peer standing in for the native binary — no container, no docker. It
 * covers the handshake ({@code Hello}→{@code Ack} + registry liveness), the backend-initiated
 * {@code RunCommand}→{@code CommandChunk}*→{@code CommandExit} round-trip, the {@code
 * Describe}→{@code WorkspaceInfo} stub, and connection teardown pruning the registry. The same
 * {@link WorkspaceDaemonRegistry} path is exercised by a real container in the extended {@code
 * DaemonControlSocketIT}.
 */
@QuarkusTest
class DaemonControlSocketTest {

  /** The label the daemon announces for itself in its protocol frames. */
  private static final String WORKSPACE_ID = "ws-daemonhost-test";

  /** The identity the control socket is addressed by — the registry keys on this, not the label. */
  private static final long WORKSPACE_ROW_ID = 7L;

  @Inject Vertx vertx;
  @Inject WorkspaceDaemonRegistry registry;
  @Inject DaemonMessageCodec codec;
  @Inject HintRecorder hints;
  @Inject WorkspaceRepository workspaceRepository;

  @Inject RecordingAgentSessionReporter agentSessions;

  @TestHTTPResource("/workspaces/daemon/" + WORKSPACE_ROW_ID)
  URI endpoint;

  /** The {@code configJson}/{@code warning} the fake peer answers a {@link DescribeConfig} with. */
  private volatile String configJson = "{}";

  private volatile String configWarning = null;

  /**
   * A fake workspace-daemon: connects, echoes Hello, and answers RunCommand/Describe like the real
   * binary.
   */
  private FakePeer connect() throws Exception {
    WebSocketClient client = vertx.createWebSocketClient();
    BlockingQueue<DaemonMessage> inbound = new LinkedBlockingQueue<>();
    WebSocketConnectOptions options =
        new WebSocketConnectOptions()
            .setHost(endpoint.getHost())
            .setPort(endpoint.getPort())
            .setURI(endpoint.getPath());
    WebSocket ws =
        client.connect(options).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    ws.textMessageHandler(
        text -> {
          DaemonMessage message = codec.decode(text);
          inbound.add(message);
          switch (message) {
            case RunCommand command -> {
              ws.writeTextMessage(
                  codec.encode(new CommandChunk(command.correlationId(), Stream.STDOUT, "hi\n")));
              ws.writeTextMessage(
                  codec.encode(new CommandChunk(command.correlationId(), Stream.STDERR, "warn\n")));
              ws.writeTextMessage(codec.encode(new CommandExit(command.correlationId(), 0)));
            }
            case Describe ignored ->
                ws.writeTextMessage(
                    codec.encode(
                        new WorkspaceInfo(
                            WORKSPACE_ID, "repo-1", "feature", "main", "deadbeef", true)));
            case DescribeConfig request ->
                ws.writeTextMessage(
                    codec.encode(
                        new ConfigView(
                            WORKSPACE_ID, request.correlationId(), configJson, configWarning)));
            case RunBootstrap ignored -> {
              // Manual re-run: reply with a one-step chain + terminal, as the daemon would.
              ws.writeTextMessage(
                  codec.encode(
                      new BootstrapStep(WORKSPACE_ID, "install", BootstrapStep.Phase.EXECUTE)));
              ws.writeTextMessage(
                  codec.encode(
                      new BootstrapOutcome(
                          WORKSPACE_ID, "install", BootstrapOutcome.Result.SUCCEEDED, 0)));
              ws.writeTextMessage(codec.encode(new Bootstrapped(WORKSPACE_ID, true)));
            }
            default -> {
              /* Ack and others: just recorded in `inbound` */
            }
          }
        });
    // Announce ourselves, exactly as workspace-daemon does on connect.
    ws.writeTextMessage(
        codec.encode(
            new Hello(
                WORKSPACE_ID,
                "repo-1",
                "feature",
                "main",
                1,
                "1.0.0-SNAPSHOT",
                "2026-07-25T09:14:03Z")));
    ws.writeTextMessage(codec.encode(new DaemonLog("INFO", "workspace-daemon online")));
    return new FakePeer(client, ws, inbound);
  }

  @Test
  void handshakeRegistersLivenessAndAcks() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
      assertTrue(registry.isDaemonLive(WORKSPACE_ROW_ID));
      assertInstanceOf(Ack.class, peer.take());
    }
  }

  @Test
  void handshakeRecordsDaemonRegistryInfo() throws Exception {
    try (FakePeer peer = connect()) {
      // The entry appears on socket registration (connectedAt), but version/buildTime are filled
      // when the Hello frame is processed — await that, not mere presence.
      await(() -> registry.lookup(WORKSPACE_ROW_ID).map(i -> i.version() != null).orElse(false));
      WorkspaceDaemonInfo.Info info = registry.lookup(WORKSPACE_ROW_ID).orElseThrow();
      // The build identity the fake peer announced in its Hello is retained for the registry, the
      // build timestamp parsed to an Instant; connectedAt is stamped server-side on registration.
      assertEquals("1.0.0-SNAPSHOT", info.version());
      assertEquals(java.time.Instant.parse("2026-07-25T09:14:03Z"), info.buildTime());
      assertTrue(info.connectedAt() != null);
    }
  }

  @Test
  void lookupIsEmptyForAnUnknownWorkspace() {
    assertTrue(registry.lookup(-1L).isEmpty());
  }

  @Test
  void runCommandRoundTripsOverTheSocket() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));

      WorkspaceDaemonRegistry.CommandResult result =
          registry
              .runCommand(WORKSPACE_ROW_ID, List.of("echo", "hi"), "/workspace", Map.of())
              .get(5, TimeUnit.SECONDS);

      assertEquals(0, result.exitCode());
      assertEquals("hi\n", result.stdout());
      assertEquals("warn\n", result.stderr());
    }
  }

  @Test
  void describeReturnsWorkspaceInfo() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));

      WorkspaceInfo info = registry.describe(WORKSPACE_ROW_ID).get(5, TimeUnit.SECONDS);

      assertEquals("deadbeef", info.head());
      assertTrue(info.dirty());
      assertEquals("feature", info.branch());
    }
  }

  @Test
  void readConfigDeserializesTheDaemonsConfigViewIntoQitsConfig() throws Exception {
    // A QitsConfig-shaped JSON as the daemon's ConfigJson emits it (camelCase keys, empty
    // collections present) — readConfig must map it straight into a QitsConfig over the socket.
    // It still carries a pre-qits-947 daemon's `daemons` (services) key, which the host no longer
    // reads: it must be ignored, not degrade the whole view to an empty config with a warning.
    configJson =
        "{\"repository\":{\"mainBranch\":\"main\",\"archetype\":\"SERVICE\"},"
            + "\"frameworks\":[],"
            + "\"actions\":[{\"name\":\"build\",\"execute\":\"mvn -B verify\",\"interactive\":false,"
            + "\"environment\":{\"CI\":\"true\"}}],"
            + "\"daemons\":[{\"name\":\"dev\",\"start\":\"mvn quarkus:dev\",\"readyPattern\":\"Listening\","
            + "\"environment\":{},\"webView\":{\"port\":8080,\"entryPath\":\"/\"},"
            + "\"observers\":[],\"sources\":[],\"healthChecks\":[]}],"
            + "\"bootstrap\":[]}";
    configWarning = null;
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));

      Optional<WorkspaceConfigView> read = registry.readConfig(WORKSPACE_ROW_ID);

      assertTrue(read.isPresent());
      WorkspaceConfigView view = read.get();
      assertEquals(null, view.warning());
      QitsConfig config = view.config();
      assertEquals("main", config.repository().mainBranch());
      assertEquals(1, config.actions().size());
      assertEquals("build", config.actions().get(0).name());
      assertEquals(Map.of("CI", "true"), config.actions().get(0).environment());
      assertTrue(config.bootstrap().isEmpty());
    }
  }

  @Test
  void readConfigDeserializesExplicitIdsAndDefaultsMissingIdsToNames() throws Exception {
    // Part 5: every declared entry carries an id: — explicit when the file declares one, defaulting
    // to the entry's name when absent. The registry's Jackson deserialization into QitsConfig must
    // honor both — and step over an old daemon's `services` entries without a warning.
    configJson =
        "{\"frameworks\":[],"
            + "\"actions\":["
            + "{\"id\":\"build-ci\",\"name\":\"build\",\"execute\":\"mvn -B verify\"},"
            + "{\"name\":\"test\",\"execute\":\"mvn test\"}],"
            + "\"services\":["
            + "{\"id\":\"dev-server\",\"name\":\"dev\",\"start\":\"mvn quarkus:dev\"},"
            + "{\"name\":\"logs\",\"start\":\"tail -f app.log\"}],"
            + "\"bootstrap\":["
            + "{\"id\":\"install-deps\",\"name\":\"install\",\"execute\":\"./install.sh\"},"
            + "{\"name\":\"seed\",\"execute\":\"./seed.sh\"}]}";
    configWarning = null;
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));

      Optional<WorkspaceConfigView> read = registry.readConfig(WORKSPACE_ROW_ID);

      assertTrue(read.isPresent());
      QitsConfig config = read.get().config();
      assertEquals("build-ci", config.actions().get(0).id());
      assertEquals("test", config.actions().get(1).id(), "id-less action defaults id to name");
      assertEquals(null, read.get().warning(), "an old daemon's services key is not an error");
      assertEquals("install-deps", config.bootstrap().get(0).id());
      assertEquals("seed", config.bootstrap().get(1).id(), "id-less step defaults id to name");
    }
  }

  @Test
  void readConfigSurfacesTheDaemonsWarningWithEmptyConfig() throws Exception {
    configJson = "{\"frameworks\":[],\"actions\":[],\"daemons\":[],\"bootstrap\":[]}";
    configWarning = "Unsupported or missing 'version' (expected 1): null";
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));

      Optional<WorkspaceConfigView> read = registry.readConfig(WORKSPACE_ROW_ID);

      assertTrue(read.isPresent());
      assertEquals("Unsupported or missing 'version' (expected 1): null", read.get().warning());
      assertTrue(read.get().config().isEmpty());
    }
  }

  @Test
  void readConfigIsEmptyWhenNoDaemonIsLive() {
    assertTrue(registry.readConfig(-1L).isEmpty());
  }

  @Test
  void awaitProvisionCompletesOnProvisionedAndStreamsOutput() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
      List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
      var awaiting =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () ->
                  registry.awaitProvision(
                      WORKSPACE_ROW_ID,
                      Duration.ofSeconds(5),
                      Duration.ofSeconds(5),
                      lines::add));

      // Wait until the awaiter's line sink is registered, so the streamed chunk is routed rather
      // than
      // dropped (chunk routing is best-effort before an awaiter exists). The terminal Provisioned
      // itself is race-proof via complete-or-retain.
      await(() -> registry.isAwaitingProvision(WORKSPACE_ROW_ID));
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new CommandChunk(
                      eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol
                          .PROVISION_CORRELATION_ID,
                      Stream.STDOUT,
                      "Cloning into /workspace...\n")));
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new eu.wohlben.qits.workspacedaemon.protocol.Provisioned(
                      WORKSPACE_ID, "cafebabe")));

      var result = awaiting.get(5, TimeUnit.SECONDS);
      assertTrue(result.isPresent());
      assertTrue(result.get().ok());
      assertEquals("cafebabe", result.get().head());
      await(() -> lines.contains("Cloning into /workspace..."));
      assertTrue(lines.contains("Cloning into /workspace..."), lines.toString());
    }
  }

  @Test
  void awaitProvisionFailsOnProvisionFailed() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
      var awaiting =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () ->
                  registry.awaitProvision(
                      WORKSPACE_ROW_ID, Duration.ofSeconds(5), Duration.ofSeconds(5), null));

      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed(
                      WORKSPACE_ID, "git clone exited 128")));

      var result = awaiting.get(5, TimeUnit.SECONDS);
      assertTrue(result.isPresent());
      assertFalse(result.get().ok());
      assertEquals("git clone exited 128", result.get().message());
    }
  }

  @Test
  void awaitProvisionReturnsEmptyWhenNoDaemonConnects() {
    // No peer: the connect window lapses and awaitProvision reports "no daemon" (empty). The daemon
    // is the sole provisioner now, so the caller (WorkspaceService) turns this empty into a
    // provision
    // FAILURE — there is no host-driven fallback.
    var result =
        registry.awaitProvision(-1L, Duration.ofMillis(200), Duration.ofSeconds(1), null);
    assertTrue(result.isEmpty());
  }

  @Test
  void awaitBootstrapStreamsStepsAndCompletesOk() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
      CapturingSink sink = new CapturingSink();
      var awaiting =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () ->
                  registry.awaitBootstrap(
                      WORKSPACE_ROW_ID, sink, Duration.ofSeconds(5), Duration.ofSeconds(5)));

      // The daemon streams the chain autonomously, then the terminal.
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new BootstrapStep(WORKSPACE_ID, "install", BootstrapStep.Phase.EXECUTE)));
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new CommandChunk(
                      DaemonProtocol.bootstrapCorrelationId("install"),
                      Stream.STDOUT,
                      "building\n")));
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new BootstrapOutcome(
                      WORKSPACE_ID, "install", BootstrapOutcome.Result.SUCCEEDED, 0)));
      peer.ws().writeTextMessage(codec.encode(new Bootstrapped(WORKSPACE_ID, true)));

      var result = awaiting.get(5, TimeUnit.SECONDS);
      assertTrue(result.isPresent());
      assertTrue(result.get().ok());
      assertTrue(sink.steps.contains("install:EXECUTE"), sink.steps.toString());
      assertTrue(sink.lines.contains("building"), sink.lines.toString());
      assertTrue(sink.outcomes.contains("install:SUCCEEDED:0"), sink.outcomes.toString());
    }
  }

  @Test
  void awaitBootstrapReportsFailedChain() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
      CapturingSink sink = new CapturingSink();
      var awaiting =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () ->
                  registry.awaitBootstrap(
                      WORKSPACE_ROW_ID, sink, Duration.ofSeconds(5), Duration.ofSeconds(5)));

      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new BootstrapOutcome(
                      WORKSPACE_ID, "install", BootstrapOutcome.Result.FAILED, 7)));
      peer.ws().writeTextMessage(codec.encode(new Bootstrapped(WORKSPACE_ID, false)));

      var result = awaiting.get(5, TimeUnit.SECONDS);
      assertTrue(result.isPresent());
      assertFalse(result.get().ok(), "a failed chain gates daemons off");
      assertTrue(sink.outcomes.contains("install:FAILED:7"), sink.outcomes.toString());
    }
  }

  @Test
  void awaitBootstrapPicksUpATerminalThatArrivedBeforeTheAwait() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
      // The autonomous chain finishes before the host's observer registers its await: the terminal
      // (and the step events) must be buffered/retained and replayed when the sink registers.
      peer.ws()
          .writeTextMessage(
              codec.encode(new BootstrapStep(WORKSPACE_ID, "seed", BootstrapStep.Phase.EXECUTE)));
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new BootstrapOutcome(
                      WORKSPACE_ID, "seed", BootstrapOutcome.Result.SUCCEEDED, 0)));
      peer.ws().writeTextMessage(codec.encode(new Bootstrapped(WORKSPACE_ID, true)));

      // Give the frames time to land on the registry ahead of the await.
      await(() -> registry.isBootstrapPending(WORKSPACE_ROW_ID));
      CapturingSink sink = new CapturingSink();
      var result =
          registry.awaitBootstrap(
              WORKSPACE_ROW_ID, sink, Duration.ofSeconds(5), Duration.ofSeconds(5));

      assertTrue(result.isPresent());
      assertTrue(result.get().ok());
      assertTrue(sink.outcomes.contains("seed:SUCCEEDED:0"), "buffered step replayed on the sink");
    }
  }

  @Test
  void runBootstrapSendsRunBootstrapAndAwaitsTheReply() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
      CapturingSink sink = new CapturingSink();

      var result = registry.runBootstrap(
                      WORKSPACE_ROW_ID, null, sink, Duration.ofSeconds(5));

      assertTrue(result.isPresent());
      assertTrue(result.get().ok());
      assertTrue(sink.outcomes.contains("install:SUCCEEDED:0"), sink.outcomes.toString());
    }
  }

  @Test
  void awaitBootstrapCompletesEvenWhenASinkCallbackThrows() throws Exception {
    // A sink whose onOutcome throws (the recordOutcome-on-deleted-workspace case) must not escape
    // onMessage and tear down the socket — the terminal Bootstrapped must still complete the await.
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
      WorkspaceBootstrapDriver.StepSink throwingSink =
          new WorkspaceBootstrapDriver.StepSink() {
            @Override
            public void onStep(String name, String phase) {}

            @Override
            public void onLine(String name, String line) {}

            @Override
            public void onOutcome(String name, String outcome, Integer exitCode) {
              throw new RuntimeException("simulated recordOutcome failure");
            }
          };
      var awaiting =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () ->
                  registry.awaitBootstrap(
                      WORKSPACE_ROW_ID,
                      throwingSink,
                      Duration.ofSeconds(5),
                      Duration.ofSeconds(5)));

      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new BootstrapOutcome(
                      WORKSPACE_ID, "install", BootstrapOutcome.Result.SUCCEEDED, 0)));
      peer.ws().writeTextMessage(codec.encode(new Bootstrapped(WORKSPACE_ID, true)));

      var result = awaiting.get(5, TimeUnit.SECONDS);
      assertTrue(
          result.isPresent(), "the throwing sink must not prevent the terminal from resolving");
      assertTrue(result.get().ok());
    }
  }

  @Test
  void runBootstrapIsEmptyWhenNoDaemonIsLive() {
    var result =
        registry.runBootstrap(
            -1L,
            null,
            new CapturingSink(),
            Duration.ofSeconds(1));
    assertTrue(result.isEmpty());
  }

  @Test
  void closingTheSocketPrunesTheRegistry() throws Exception {
    FakePeer peer = connect();
    await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
    peer.close();
    await(() -> !registry.isDaemonLive(WORKSPACE_ROW_ID));
    assertFalse(registry.isDaemonLive(WORKSPACE_ROW_ID));
  }

  @Test
  void gitStatusReportCachesTheFlagAndFiresFilesAndGitStatusHints() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
      hints.clear();

      peer.ws().writeTextMessage(codec.encode(new GitStatus(WORKSPACE_ID, false, "abc123")));

      // The flag is cached for the DTO read path.
      await(() -> registry.isClean(WORKSPACE_ROW_ID).isPresent());
      assertEquals(Optional.of(false), registry.isClean(WORKSPACE_ROW_ID));
      // Every report re-homes the old host watcher's FILES trigger (workspace channel)…
      await(() -> hints.has(Topic.FILES, "repo-1", WORKSPACE_ROW_ID));
      assertTrue(hints.has(Topic.FILES, "repo-1", WORKSPACE_ROW_ID), hints.toString());
      // …and a flag change nudges the branch-tree badge on the repository channel (repoId, null).
      await(() -> hints.has(Topic.GIT_STATUS, "repo-1", (Long) null));
      assertTrue(hints.has(Topic.GIT_STATUS, "repo-1", (Long) null), hints.toString());
    }
  }

  @Test
  void unchangedGitStatusFiresFilesButNotGitStatus() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));

      // First report establishes clean=true (fires FILES + GIT_STATUS).
      peer.ws().writeTextMessage(codec.encode(new GitStatus(WORKSPACE_ID, true, "abc123")));
      await(() -> registry.isClean(WORKSPACE_ROW_ID).equals(Optional.of(true)));
      hints.clear();

      // A second report with the SAME flag (a dirty→dirty content edit would look like this): the
      // marker moved on the daemon, so FILES must fire again, but the badge flag didn't change so
      // GIT_STATUS must NOT.
      peer.ws().writeTextMessage(codec.encode(new GitStatus(WORKSPACE_ID, true, "abc123")));
      await(() -> hints.has(Topic.FILES, "repo-1", WORKSPACE_ROW_ID));
      assertTrue(hints.has(Topic.FILES, "repo-1", WORKSPACE_ROW_ID), hints.toString());
      assertFalse(hints.has(Topic.GIT_STATUS, "repo-1", (Long) null), hints.toString());
    }
  }

  @Test
  void disconnectEvictsTheCachedGitStatus() throws Exception {
    FakePeer peer = connect();
    await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
    peer.ws().writeTextMessage(codec.encode(new GitStatus(WORKSPACE_ID, false, "abc123")));
    await(() -> registry.isClean(WORKSPACE_ROW_ID).isPresent());

    peer.close();

    await(() -> registry.isClean(WORKSPACE_ROW_ID).isEmpty());
    assertTrue(registry.isClean(WORKSPACE_ROW_ID).isEmpty(), "cache cleared on disconnect (unknown)");
  }

  @Test
  void agentActivityCachesTheRollupAndFiresTheHintOnlyOnAFlip() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
      hints.clear();

      // A prompt was submitted: BUSY becomes the workspace rollup and fires AGENT_ACTIVITY on the
      // workspace channel (the frame's commandId is opaque; the workspace comes from the
      // connection).
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new AgentActivity(
                      "cmd-1",
                      null,
                      DaemonProtocol.AgentState.BUSY,
                      "UserPromptSubmit",
                      null,
                      null,
                      1L)));
      await(() -> registry.activityFor(WORKSPACE_ROW_ID).isPresent());
      assertEquals(Optional.of(AgentActivityState.BUSY), registry.activityFor(WORKSPACE_ROW_ID));
      await(() -> hints.has(Topic.AGENT_ACTIVITY, "repo-1", WORKSPACE_ROW_ID));
      // The flip is mirrored onto the repository channel (repoId, null) and the global channel
      // (null, null) — the agent-activity bars on the repository/project detail routes ride those.
      await(() -> hints.has(Topic.AGENT_ACTIVITY, "repo-1", (Long) null));
      await(() -> hints.has(Topic.AGENT_ACTIVITY, null, (Long) null));

      hints.clear();
      // A reconnect-style replay of the same BUSY state: the rollup doesn't flip, so no hint.
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new AgentActivity(
                      "cmd-1",
                      null,
                      DaemonProtocol.AgentState.BUSY,
                      "UserPromptSubmit",
                      null,
                      null,
                      2L)));
      // A subsequent Stop flips BUSY→IDLE — its hint proves the duplicate above was processed first
      // (frames run in order on the socket thread), so exactly one AGENT_ACTIVITY hint since
      // clear().
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new AgentActivity(
                      "cmd-1", null, DaemonProtocol.AgentState.IDLE, "Stop", null, null, 3L)));
      await(() -> registry.activityFor(WORKSPACE_ROW_ID).equals(Optional.of(AgentActivityState.IDLE)));
      await(() -> hints.count(Topic.AGENT_ACTIVITY, "repo-1", WORKSPACE_ROW_ID) >= 1);
      assertEquals(1L, hints.count(Topic.AGENT_ACTIVITY, "repo-1", WORKSPACE_ROW_ID), hints.toString());
      // The no-flip duplicate stayed silent on the mirrored channels too.
      assertEquals(1L, hints.count(Topic.AGENT_ACTIVITY, "repo-1", (Long) null), hints.toString());
      assertEquals(1L, hints.count(Topic.AGENT_ACTIVITY, null, (Long) null), hints.toString());
    }
  }

  /**
   * ⚖9. The registry used to <em>evict</em> a command on {@code ENDED}, so the rollup went straight
   * from a live state to nothing and {@code WorkspaceDto.agentActivity} could never carry the value
   * at all. That is not cosmetic: the agent-activity bar is ordered by when a workspace's state last
   * changed precisely so a session that has just stopped sits at the front — it is the one waiting
   * for the next prompt — and evicting it made that workspace disappear at exactly that moment.
   */
  @Test
  void anEndedSessionStaysInTheRollupInsteadOfVanishing() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
      hints.clear();

      report(peer, "cmd-1", DaemonProtocol.AgentState.BUSY, "UserPromptSubmit");
      await(() -> registry.activityFor(WORKSPACE_ROW_ID).equals(Optional.of(AgentActivityState.BUSY)));

      report(peer, "cmd-1", DaemonProtocol.AgentState.ENDED, "SessionEnd");
      await(
          () -> registry.activityFor(WORKSPACE_ROW_ID).equals(Optional.of(AgentActivityState.ENDED)));
      // The flip to ENDED is a change like any other, so the bar hears about it on all three
      // channels rather than silently losing the row.
      await(() -> hints.has(Topic.AGENT_ACTIVITY, "repo-1", WORKSPACE_ROW_ID));
      await(() -> hints.has(Topic.AGENT_ACTIVITY, "repo-1", (Long) null));
      await(() -> hints.has(Topic.AGENT_ACTIVITY, null, (Long) null));

      // A resume writes over the same command's entry — a live report always beats a remembered
      // one, so the TTL only ever governs a session that stayed finished.
      report(peer, "cmd-1", DaemonProtocol.AgentState.BUSY, "UserPromptSubmit");
      await(() -> registry.activityFor(WORKSPACE_ROW_ID).equals(Optional.of(AgentActivityState.BUSY)));
    }
  }

  /**
   * ENDED is the lowest precedence, which is what stops one finished session from mislabelling a
   * workspace that still has a live conversation in it.
   */
  @Test
  void aWorkspaceReadsEndedOnlyWhenEveryTrackedSessionHas() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));

      report(peer, "cmd-a", DaemonProtocol.AgentState.ENDED, "SessionEnd");
      report(peer, "cmd-b", DaemonProtocol.AgentState.IDLE, "Stop");
      await(() -> registry.activityFor(WORKSPACE_ROW_ID).equals(Optional.of(AgentActivityState.IDLE)));

      report(peer, "cmd-b", DaemonProtocol.AgentState.ENDED, "SessionEnd");
      await(
          () -> registry.activityFor(WORKSPACE_ROW_ID).equals(Optional.of(AgentActivityState.ENDED)));
    }
  }

  /**
   * The other half of "keep it, don't evict it": it has to stop being true eventually, and an open
   * browser has to be told. Nothing on the detail page polls, so the sweep's hint is the only way
   * the fade reaches a client — the rollup itself already answers correctly the instant the TTL
   * passes, which is why the sweep is an announcement rather than the thing that makes it true.
   *
   * <p>Driven at a chosen instant rather than by sleeping past the real thirty-minute TTL.
   */
  @Test
  void anEndedSessionExpiresOnTheTtlAndTheExpiryIsAnnounced() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));

      report(peer, "cmd-1", DaemonProtocol.AgentState.ENDED, "SessionEnd");
      await(
          () -> registry.activityFor(WORKSPACE_ROW_ID).equals(Optional.of(AgentActivityState.ENDED)));

      hints.clear();
      // Still inside the window: nothing dropped, nothing announced.
      assertEquals(0, registry.expireEndedSessions(System.currentTimeMillis()));
      assertEquals(Optional.of(AgentActivityState.ENDED), registry.activityFor(WORKSPACE_ROW_ID));
      assertEquals(0L, hints.count(Topic.AGENT_ACTIVITY, "repo-1", WORKSPACE_ROW_ID));

      long pastTheTtl = System.currentTimeMillis() + Duration.ofHours(2).toMillis();
      assertEquals(1, registry.expireEndedSessions(pastTheTtl));
      assertTrue(
          registry.activityFor(WORKSPACE_ROW_ID).isEmpty(),
          "an expired ENDED falls back to no activity at all");
      await(() -> hints.has(Topic.AGENT_ACTIVITY, "repo-1", WORKSPACE_ROW_ID));
      await(() -> hints.has(Topic.AGENT_ACTIVITY, "repo-1", (Long) null));
      await(() -> hints.has(Topic.AGENT_ACTIVITY, null, (Long) null));

      // Idempotent: the entry is gone, so a second sweep has nothing to drop and stays silent.
      hints.clear();
      assertEquals(0, registry.expireEndedSessions(pastTheTtl));
      assertEquals(0L, hints.count(Topic.AGENT_ACTIVITY, "repo-1", WORKSPACE_ROW_ID));
    }
  }

  /** One agent-activity frame, with the fields these tests never vary. */
  private void report(FakePeer peer, String commandId, String state, String hookEvent) {
    peer.ws()
        .writeTextMessage(
            codec.encode(
                new AgentActivity(commandId, null, state, hookEvent, null, null, sequence++)));
  }

  private long sequence = 1L;

  @Test
  void disconnectEvictsTheCachedAgentActivity() throws Exception {
    FakePeer peer = connect();
    await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));
    peer.ws()
        .writeTextMessage(
            codec.encode(
                new AgentActivity(
                    "cmd-1",
                    null,
                    DaemonProtocol.AgentState.BUSY,
                    "UserPromptSubmit",
                    null,
                    null,
                    1L)));
    await(() -> registry.activityFor(WORKSPACE_ROW_ID).isPresent());

    peer.close();

    await(() -> registry.activityFor(WORKSPACE_ROW_ID).isEmpty());
    assertTrue(registry.activityFor(WORKSPACE_ROW_ID).isEmpty(), "cache cleared on disconnect");
  }

  @Test
  void aSessionStartAgentActivityStillDrivesTheSessionLineageWrite() throws Exception {
    // The regression guard for retiring the direct /agent-session endpoint: a SessionStart frame,
    // routed through the daemon, still reaches the session-lineage sink. The sink is the
    // AgentSessionReporter port here rather than a CommandService write — the lineage row belongs
    // to the commands context; what this context owns is calling the port with the frame's fields.
    String sessionId = UUID.randomUUID().toString();
    String commandId = UUID.randomUUID().toString();
    agentSessions.clear();
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));

      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new AgentActivity(
                      commandId,
                      sessionId,
                      DaemonProtocol.AgentState.IDLE,
                      "SessionStart",
                      "startup",
                      "/claude-home/.claude/projects/-workspace/" + sessionId + ".jsonl",
                      1L)));

      await(() -> agentSessions.recorded(commandId, sessionId));
      assertTrue(
          agentSessions.recorded(commandId, sessionId),
          "SessionStart was reported to the session-lineage sink");
    }
  }

  @Test
  void pullFromOriginSendsAPullBranchToTheLiveDaemon() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ROW_ID));

      // A host-side merge advanced this workspace's branch; the registry asks the daemon to pull.
      registry.pullFromOrigin(WORKSPACE_ROW_ID, "feature");

      PullBranch pull = null;
      for (int i = 0; i < 10 && pull == null; i++) {
        if (peer.take() instanceof PullBranch p) {
          pull = p;
        }
      }
      assertTrue(pull != null, "expected a PullBranch frame at the daemon");
      assertEquals("feature", pull.branch());
    }
  }

  @Test
  void pullFromOriginIsANoOpWithoutALiveDaemon() {
    // No daemon connected for this id: fire-and-forget, must not throw (the checkout syncs on its
    // next host git op).
    registry.pullFromOrigin(-1L, "feature");
  }

  /** Spin until {@code condition} holds or a 5s deadline passes. */
  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        return; // let the caller's assertion report the failure
      }
      TimeUnit.MILLISECONDS.sleep(25);
    }
  }

  /** Captures the {@link WorkspaceBootstrapDriver.StepSink} callbacks for assertion. */
  private static final class CapturingSink implements WorkspaceBootstrapDriver.StepSink {
    private final List<String> steps = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void onStep(String name, String phase) {
      steps.add(name + ":" + phase);
    }

    @Override
    public void onLine(String name, String line) {
      lines.add(line);
    }

    @Override
    public void onOutcome(String name, String outcome, Integer exitCode) {
      outcomes.add(name + ":" + outcome + ":" + exitCode);
    }
  }

  /**
   * Collects the async {@link WorkspaceChangeHint}s the registry fires, so the git-status tests can
   * assert the re-homed FILES trigger and the GIT_STATUS badge nudge without the SSE boundary. An
   * {@code @ApplicationScoped} bean discovered by the {@code @QuarkusTest}.
   */
  @jakarta.enterprise.context.ApplicationScoped
  public static class HintRecorder {
    private final List<WorkspaceChangeHint> received =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    void onHint(@jakarta.enterprise.event.ObservesAsync WorkspaceChangeHint hint) {
      received.add(hint);
    }

    void clear() {
      received.clear();
    }

    boolean has(Topic topic, String repoId, Long workspaceId) {
      return count(topic, repoId, workspaceId) > 0;
    }

    long count(Topic topic, String repoId, Long workspaceId) {
      return received.stream()
          .filter(
              h ->
                  h.topic() == topic
                      && java.util.Objects.equals(h.repoId(), repoId)
                      && java.util.Objects.equals(h.workspaceRowId(), workspaceId))
          .count();
    }

    @Override
    public String toString() {
      return received.toString();
    }
  }

  /** The fake workspace-daemon side of the socket. */
  private record FakePeer(
      WebSocketClient client, WebSocket ws, BlockingQueue<DaemonMessage> inbound)
      implements AutoCloseable {

    DaemonMessage take() throws InterruptedException {
      DaemonMessage message = inbound.poll(5, TimeUnit.SECONDS);
      if (message == null) {
        throw new AssertionError("expected a frame from the backend but none arrived");
      }
      return message;
    }

    @Override
    public void close() {
      ws.close();
      client.close();
    }
  }
}
