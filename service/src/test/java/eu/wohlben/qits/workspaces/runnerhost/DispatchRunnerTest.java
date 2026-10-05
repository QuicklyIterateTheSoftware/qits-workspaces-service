package eu.wohlben.qits.workspaces.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Nothing;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.workspaces.control.ContainerProxyPath;
import eu.wohlben.qits.workspaces.control.DaemonProxyTargets;
import eu.wohlben.qits.workspaces.control.DispatchService;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.ProxyOrigin;
import eu.wohlben.qits.workspaces.control.TestGit;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceAddressPlanes;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.control.WorkspaceSubject;
import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspacesrunner.protocol.Estate;
import eu.wohlben.qits.workspacesrunner.protocol.Stop;
import eu.wohlben.qits.workspacesrunner.protocol.Stopped;
import eu.wohlben.qits.workspacesrunner.protocol.Take;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An agent dispatch onto workspaces queued for a one-slot runner (qits-626), end to end over the
 * runner socket: the dispatch answers at once with the row still QUEUED, the launch waits parked
 * for as long as the row waits for a slot, and the runner's take — not a re-press — is what sends
 * it on to the daemon.
 *
 * <p>The runner is {@link FakeWorkspacesRunner} on the real socket, so the take is the real reserve
 * and the real {@code WorkspaceTaken} after its commit. The daemon is a stub HTTP server answering
 * the two calls a launch makes, reached by the real {@code DaemonAgentClient}: a RUNNER row's
 * daemon is reached only through its reverse tunnel, which no container in this suite dials, so
 * {@link DaemonProxyTargets} is replaced for the test to point a row whose container "came up" at
 * the stub — what is under test is when the launch is <em>sent</em>, not the tunnel.
 *
 * <p>Named {@code *Test} rather than {@code *IT}: surefire runs {@code *Test}, and failsafe's
 * {@code *IT} here are the packaged stories and the docker-backed daemon suites, skipped by
 * default. It reuses {@link DaemonControlSocketMachineAuthTest.GateOn} — the runner socket needs
 * the machine gate on — rather than adding a profile.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class DispatchRunnerTest {

  private static final Duration LAUNCH_DEADLINE = Duration.ofSeconds(15);

  @TestHTTPResource(WorkspacesRunnerProtocol.SOCKET_PATH)
  URI endpoint;

  @Inject Vertx vertx;

  @Inject DispatchService dispatches;

  @Inject WorkspaceService workspaceService;

  @Inject FakeRepositoryLookup repositories;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private RunnerRows rows;

  private FakeWorkspacesRunner runner;

  private StubDaemon daemon;

  @BeforeEach
  void setUp() throws Exception {
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.withDomain(WorkspaceRunnerAddressesFixture.DOMAIN),
        WorkspaceRunnerAddresses.class);
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.planesWithDomain(WorkspaceRunnerAddressesFixture.DOMAIN),
        WorkspaceAddressPlanes.class);
    daemon = StubDaemon.start(vertx);
    QuarkusMock.installMockForType(daemon.targets(), DaemonProxyTargets.class);
    rows = new RunnerRows();
  }

  @AfterEach
  void tearDown() {
    if (runner != null) {
      runner.close();
    }
    daemon.close();
    rows.clear();
  }

  @Test
  void aQueuedDispatchIsLaunchedWhenARunnerTakesItWithNoRePress() throws Exception {
    rows.eligible("wr-dispatch", 1);
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    Path origin = Path.of(dataDir, repoId, "origin");
    TestGit.exec(origin.toFile(), "git", "branch", "task-a", "master");
    TestGit.exec(origin.toFile(), "git", "branch", "task-b", "master");
    Long a = rows.queuedOn(repoId, "task-a", Instant.now().minusSeconds(10));
    Long b = rows.queuedOn(repoId, "task-b", Instant.now());
    runner = greeted("wr-dispatch");

    // A: answered at once, queued; then the runner takes it, and the launch follows on its own.
    DispatchService.Dispatch first =
        dispatches.dispatch(repoId, "task-a", false, null, WorkspaceSubject.none(), "work on A");
    assertEquals(DispatchService.AgentLaunch.SCHEDULED, first.agentLaunch());
    assertEquals(WorkspaceRuntimeStatus.QUEUED, first.workspace().runtimeStatus());

    runner.send(new Reserve());
    assertEquals(a.longValue(), ((Take) answer(runner)).rowId());
    daemon.comesUp(a);
    assertEquals("work on A", daemon.awaitLaunch(a));

    // B: the runner's one slot is A's, so B waits in the queue — and its launch with it.
    DispatchService.Dispatch second =
        dispatches.dispatch(repoId, "task-b", false, null, WorkspaceSubject.none(), "work on B");
    assertEquals(DispatchService.AgentLaunch.SCHEDULED, second.agentLaunch());
    assertEquals(WorkspaceRuntimeStatus.QUEUED, second.workspace().runtimeStatus());
    runner.send(new Reserve());
    assertTrue(answer(runner) instanceof Nothing, "the slot is A's");
    assertEquals(WorkspaceRuntimeStatus.QUEUED, rows.read(b).runtimeStatus);
    assertNull(daemon.launched.get(b), "nothing reaches B's daemon while B is queued");

    // Stop A: the slot frees, the runner takes B, and B's launch arrives with no re-press.
    CompletableFuture<Void> stopping =
        CompletableFuture.runAsync(() -> workspaceService.stopContainer(a));
    assertEquals(a.longValue(), runner.await(Stop.class).rowId());
    runner.send(new Stopped(a));
    stopping.get(10, TimeUnit.SECONDS);

    runner.send(new Reserve());
    assertEquals(b.longValue(), ((Take) answer(runner)).rowId());
    daemon.comesUp(b);
    assertEquals("work on B", daemon.awaitLaunch(b));
  }

  private FakeWorkspacesRunner greeted(String clientId) throws Exception {
    FakeWorkspacesRunner dialled =
        FakeWorkspacesRunner.connect(
            vertx,
            endpoint,
            DaemonMachineTokens.tokenWithRoles(
                clientId, Set.of(WorkspaceRunnerSocket.RUNNER_ROLE), "qits-platform"));
    dialled.send(FakeWorkspacesRunner.hello(WorkspacesRunnerBinary.VERSION, List.of()));
    dialled.expect(Ack.class);
    dialled.expect(Estate.class);
    dialled.expect(Backlog.class);
    return dialled;
  }

  /** The answer to a reserve: the first {@code take} or {@code nothing}, past any push. */
  private static RunnerMessage answer(FakeWorkspacesRunner runner) throws Exception {
    long deadline = System.nanoTime() + FakeWorkspacesRunner.SOON.toNanos();
    while (System.nanoTime() < deadline) {
      RunnerMessage next = runner.poll(Duration.ofMillis(100));
      if (next instanceof Take || next instanceof Nothing) {
        return next;
      }
    }
    return fail("neither take nor nothing arrived");
  }

  /**
   * A workspace's daemon as {@code DaemonAgentClient} reaches it: nothing at all until the test says
   * the row's container came up, then an HTTP API with no agent running, then — once launched — a
   * CHAT command running. It records every launch's {@code initialContext} by row.
   */
  static final class StubDaemon {

    final Map<Long, String> launched = new ConcurrentHashMap<>();
    private final Set<Long> up = ConcurrentHashMap.newKeySet();
    private final HttpServer server;

    private StubDaemon(HttpServer server) {
      this.server = server;
    }

    static StubDaemon start(Vertx vertx) throws Exception {
      StubDaemon[] self = new StubDaemon[1];
      HttpServer server =
          vertx
              .createHttpServer()
              .requestHandler(
                  req -> {
                    Long rowId = rowOf(req.path());
                    if (req.method() == HttpMethod.POST && req.path().endsWith("/agents")) {
                      req.bodyHandler(
                          body -> {
                            self[0].launched.put(
                                rowId, new JsonObject(body.toString()).getString("initialContext"));
                            req.response()
                                .setStatusCode(201)
                                .putHeader("Content-Type", "application/json")
                                .end("{\"commandId\":\"c-" + rowId + "\"}");
                          });
                      return;
                    }
                    if (req.path().endsWith("/commands")) {
                      String entries =
                          self[0].launched.containsKey(rowId)
                              ? "[{\"command\":{\"id\":\"c-"
                                  + rowId
                                  + "\",\"status\":\"RUNNING\",\"kind\":\"CHAT\"}}]"
                              : "[]";
                      req.response()
                          .putHeader("Content-Type", "application/json")
                          .end("{\"entries\":" + entries + "}");
                      return;
                    }
                    req.response().setStatusCode(404).end();
                  })
              .listen(0, "127.0.0.1")
              .toCompletionStage()
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS);
      self[0] = new StubDaemon(server);
      return self[0];
    }

    /** The row id out of {@code /workspaces/container/<id>/…}, the daemon's own address. */
    private static Long rowOf(String path) {
      String rest = path.substring(ContainerProxyPath.PREFIX.length());
      return Long.valueOf(rest.substring(0, rest.indexOf('/')));
    }

    void comesUp(Long rowId) {
      up.add(rowId);
    }

    /** The targets a RUNNER row would have with a tunnel: the stub, once its container is up. */
    DaemonProxyTargets targets() {
      int port = server.actualPort();
      return new DaemonProxyTargets() {
        @Override
        public DaemonTarget resolve(Long workspaceRowId) {
          return up.contains(workspaceRowId)
              ? new DaemonTarget(Reachability.READY, new ProxyOrigin("127.0.0.1", port))
              : new DaemonTarget(Reachability.NOT_CONNECTED, null);
        }
      };
    }

    String awaitLaunch(Long rowId) throws InterruptedException {
      long deadline = System.nanoTime() + LAUNCH_DEADLINE.toNanos();
      while (!launched.containsKey(rowId) && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      String instruction = launched.get(rowId);
      if (instruction == null) {
        fail("no agent launch reached workspace " + rowId + "'s daemon");
      }
      return instruction;
    }

    void close() {
      server.close();
    }
  }
}
