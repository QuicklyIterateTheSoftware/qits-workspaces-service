package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import eu.wohlben.qits.workspaces.daemonhost.StubDaemonTunnels;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Who may reach a workspace's daemon through {@code /workspaces/container/{id}/*} (qits-1386).
 *
 * <p>Under the gate-on profile of {@link DaemonControlSocketMachineAuthTest}, because that is the
 * one application here with no dev user: a header-less request is anonymous, and every other caller
 * is a real RS256 bearer whose roles and {@code sub} come out of a validated token rather than a
 * test identity. Forwarding is proved the way {@link ContainerProxyRouteTest} proves it — a stub
 * daemon behind {@link StubDaemonTunnels} that counts what reached it — so a refusal is also an
 * assertion that nothing was forwarded.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class ContainerProxyAuthorizationTest {

  private static final String PLATFORM_AUDIENCE = "qits-platform";

  @Inject Vertx vertx;
  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject AgentWorktreeRepository agents;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @TestHTTPResource("/")
  URI base;

  private Vertx daemonVertx;
  private StubDaemonTunnels tunnels;
  private final AtomicInteger daemonHits = new AtomicInteger();

  private Long rowA;
  private Long rowB;
  private String subjectA;
  private String subjectB;

  @BeforeEach
  void twoWorkspacesAndAStubDaemon() throws Exception {
    daemonVertx = Vertx.vertx();
    HttpServer server =
        daemonVertx
            .createHttpServer()
            .requestHandler(
                req -> {
                  daemonHits.incrementAndGet();
                  req.response().end("daemon:" + req.path());
                })
            .webSocketHandler(ws -> daemonHits.incrementAndGet())
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    // Every row is "connected": the tunnel branch does not consult the row, so this isolates the
    // decision under test from the container lifecycle.
    tunnels = StubDaemonTunnels.install(daemonVertx, server.actualPort(), id -> true);
    daemonHits.set(0);

    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String labelA = "proxy-a-" + suffix;
    String labelB = "proxy-b-" + suffix;
    workspaceService.createWorkspace(repoId, labelA, "master", labelA, null, false, false, true);
    workspaceService.createWorkspace(repoId, labelB, "master", labelB, null, false, false, true);
    rowA = workspaceIds.of(repoId, labelA);
    rowB = workspaceIds.of(repoId, labelB);
    subjectA = "tok-workspace-" + rowA + "-" + suffix;
    subjectB = "tok-workspace-" + rowB + "-" + suffix;
    holdToken(rowA, subjectA);
    holdToken(rowB, subjectB);
  }

  @AfterEach
  void stopTheStubDaemon() throws Exception {
    if (tunnels != null) {
      tunnels.close();
      tunnels = null;
    }
    if (daemonVertx != null) {
      daemonVertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      daemonVertx = null;
    }
  }

  /** The row holds a workspace token, so its bound subject is that token's (qits-1084). */
  private void holdToken(Long rowId, String subject) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var row = workspaceRepository.findActiveById(rowId).orElseThrow();
              row.commissionedClientId = null;
              row.commissionedTokenId = "tok-id-" + rowId;
              row.commissionedTokenSubject = subject;
              row.commissionedToken = "qits_tok_" + rowId;
            });
  }

  /** A live agent placed in {@code rowId}, holding a token of its own (qits-1152). */
  private void agentIn(Long rowId, String tokenSubject) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree agent = new AgentWorktree();
              agent.id = UUID.randomUUID();
              agent.workspaceId = rowId;
              agent.repositoryId = "proxy-wrapper";
              agent.workId = "w-proxy-" + agent.id;
              agent.wrapperBranch = "ticket/w-proxy";
              agent.createdAt = Instant.now();
              agent.gitRefs = "[]";
              agent.commissionedTokenId = "tok-id-agent-" + agent.id;
              agent.commissionedTokenSubject = tokenSubject;
              agent.commissionedToken = "qits_tok_agent";
              agents.persist(agent);
            });
  }

  private static String bearer(String subject, String... roles) {
    return "Bearer " + DaemonMachineTokens.tokenWithRoles(subject, Set.of(roles), PLATFORM_AUDIENCE);
  }

  private String files(Long rowId) {
    return "/workspaces/container/" + rowId + "/files";
  }

  private void assertForwarded(Long rowId, String authorization) {
    given()
        .header("Authorization", authorization)
        .get(files(rowId))
        .then()
        .statusCode(200)
        .body(containsString("daemon:" + files(rowId)));
    assertEquals(1, daemonHits.get());
  }

  private void assertRefused(Long rowId, String authorization, int status) {
    given()
        .header("Authorization", authorization)
        .get(files(rowId))
        .then()
        .statusCode(status);
    assertEquals(0, daemonHits.get(), "a refused caller must reach no daemon");
  }

  @Test
  void anAnonymousRequestIs401AndReachesNoDaemon() {
    given().get(files(rowA)).then().statusCode(401);
    assertEquals(0, daemonHits.get());
  }

  @Test
  void anAnonymousUpgradeIsRefusedAndNeverAnswered101() throws Exception {
    int status = upgrade("/workspaces/container/" + rowA + "/terminal/commands/1", null);

    assertNotEquals(101, status);
    assertEquals(401, status);
    assertEquals(0, daemonHits.get());
  }

  @Test
  void anAgentBoundToTheWorkspaceRowIsForwarded() {
    assertForwarded(rowA, bearer(subjectA, "qits:agent"));
  }

  @Test
  void anAgentWorktreesOwnTokenIsForwarded() {
    String agentSubject = "tok-agent-" + UUID.randomUUID();
    agentIn(rowA, agentSubject);

    assertForwarded(rowA, bearer(agentSubject, "qits:agent"));
  }

  @Test
  void anAgentWorktreesTokenDoesNotReachAnotherWorkspace() {
    String agentSubject = "tok-agent-" + UUID.randomUUID();
    agentIn(rowA, agentSubject);

    assertRefused(rowB, bearer(agentSubject, "qits:agent"), 403);
  }

  @Test
  void anAgentBoundToAnotherWorkspaceIs403() {
    assertRefused(rowA, bearer(subjectB, "qits:agent"), 403);
  }

  @Test
  void anAgentUpgradeToAnotherWorkspaceIsRefused() throws Exception {
    int status =
        upgrade(
            "/workspaces/container/" + rowA + "/terminal/commands/1",
            bearer(subjectB, "qits:agent"));

    assertEquals(403, status);
    assertEquals(0, daemonHits.get());
  }

  @Test
  void anUnknownWorkspaceIsStill404ForAnAgent() {
    assertRefused(Long.MAX_VALUE, bearer(subjectA, "qits:agent"), 404);
  }

  @Test
  void aCiRunIs403() {
    assertRefused(rowA, bearer(subjectA, "qits:ci-run"), 403);
  }

  @Test
  void aSystemCallerReachesAnyWorkspace() {
    assertForwarded(rowA, bearer("some-service", "qits:system"));
  }

  @Test
  void anAdminReachesAnyWorkspace() {
    assertForwarded(rowB, bearer("a-person", "qits:admin"));
  }

  @Test
  void anAdminAgentReachesAnyWorkspaceEvenBesideTheAgentRole() {
    assertForwarded(rowA, bearer("tok-workspace-admin-elsewhere", "qits:admin-agent", "qits:agent"));
  }

  /** 101 when the upgrade went through, else the status it was refused with. */
  private int upgrade(String path, String authorization) throws Exception {
    WebSocketClient client = vertx.createWebSocketClient();
    try {
      WebSocketConnectOptions options =
          new WebSocketConnectOptions().setHost(base.getHost()).setPort(base.getPort()).setURI(path);
      if (authorization != null) {
        options.addHeader("Authorization", authorization);
      }
      WebSocket socket =
          client.connect(options).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      socket.close();
      return 101;
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof UpgradeRejectedException rejected) {
        return rejected.getStatus();
      }
      throw failed;
    } finally {
      client.close();
    }
  }
}
