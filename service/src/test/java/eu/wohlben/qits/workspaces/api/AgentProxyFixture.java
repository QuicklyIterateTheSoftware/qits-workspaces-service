package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import eu.wohlben.qits.workspaces.daemonhost.StubDaemonTunnels;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What {@link AgentProxyRouteTest} and {@link AgentProxyAuthorizationTest} share: a stub daemon
 * behind {@link StubDaemonTunnels} (every row "connected", so the decision under test is isolated
 * from the container lifecycle) that echoes the URI it was called on and checks qits' bearer on the
 * handshake as the real daemon does, two workspace rows, and agents placed in them.
 */
final class AgentProxyFixture {

  static final String PLATFORM_AUDIENCE = "qits-platform";

  private final AtomicInteger hits = new AtomicInteger();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> method = new AtomicReference<>();

  private final Vertx daemonVertx;
  private final StubDaemonTunnels tunnels;
  private final AgentWorktreeRepository agents;
  final Long rowA;
  final Long rowB;

  private AgentProxyFixture(
      String daemonToken,
      String dataDir,
      FakeRepositoryLookup repositories,
      WorkspaceService workspaceService,
      WorkspaceIds workspaceIds,
      AgentWorktreeRepository agents)
      throws Exception {
    this.agents = agents;
    this.daemonVertx = Vertx.vertx();
    HttpServer server =
        daemonVertx
            .createHttpServer()
            .requestHandler(
                req -> {
                  hits.incrementAndGet();
                  authorization.set(req.getHeader("Authorization"));
                  method.set(req.method().name());
                  req.response().end("daemon:" + req.uri());
                })
            // The real daemon authenticates the handshake with qits' bearer, so the stub does too:
            // a stub that accepted any upgrade would hide a bearer the hand-rolled path forgot.
            .webSocketHandshakeHandler(
                handshake -> {
                  hits.incrementAndGet();
                  if (!("Bearer " + daemonToken).equals(handshake.headers().get("Authorization"))) {
                    handshake.reject(401);
                  } else {
                    handshake.accept();
                  }
                })
            .webSocketHandler(
                ws -> ws.textMessageHandler(msg -> ws.writeTextMessage("ws-daemon:" + ws.uri())))
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    this.tunnels = StubDaemonTunnels.install(daemonVertx, server.actualPort(), id -> true);

    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String labelA = "agent-proxy-a-" + suffix;
    String labelB = "agent-proxy-b-" + suffix;
    workspaceService.createWorkspace(repoId, labelA, "master", labelA, null, false, false, true);
    workspaceService.createWorkspace(repoId, labelB, "master", labelB, null, false, false, true);
    this.rowA = workspaceIds.of(repoId, labelA);
    this.rowB = workspaceIds.of(repoId, labelB);
  }

  static AgentProxyFixture start(
      String daemonToken,
      String dataDir,
      FakeRepositoryLookup repositories,
      WorkspaceService workspaceService,
      WorkspaceIds workspaceIds,
      AgentWorktreeRepository agents)
      throws Exception {
    return new AgentProxyFixture(
        daemonToken, dataDir, repositories, workspaceService, workspaceIds, agents);
  }

  /** How many requests and handshakes reached the stub daemon. */
  int hits() {
    return hits.get();
  }

  String lastAuthorization() {
    return authorization.get();
  }

  String lastMethod() {
    return method.get();
  }

  void stop() throws Exception {
    tunnels.close();
    daemonVertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  /** An agent row, as the scheduler would have written it. */
  UUID agent(Long workspaceId, AgentRunState runState, String commandId, String tokenSubject) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              AgentWorktree agent = new AgentWorktree();
              agent.id = UUID.randomUUID();
              agent.workspaceId = workspaceId;
              agent.runState = runState;
              agent.commandId = commandId;
              agent.repositoryId = "agent-proxy-wrapper";
              agent.workId = "w-agent-proxy-" + agent.id;
              agent.wrapperBranch = "ticket/w-agent-proxy-" + agent.id;
              agent.createdAt = Instant.now();
              agent.gitRefs = "[]";
              if (tokenSubject != null) {
                agent.commissionedTokenId = "tok-id-agent-" + agent.id;
                agent.commissionedTokenSubject = tokenSubject;
                agent.commissionedToken = "qits_tok_agent";
              }
              agents.persist(agent);
              return agent.id;
            });
  }

  void moveTo(UUID agentId, Long workspaceId) {
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(agentId).workspaceId = workspaceId);
  }

  void remove(UUID agentId) {
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(agentId).state = AgentState.REMOVED);
  }

  static String bearer(String subject, String... roles) {
    return "Bearer "
        + DaemonMachineTokens.tokenWithRoles(subject, Set.of(roles), PLATFORM_AUDIENCE);
  }

  /**
   * Open a WebSocket through the proxy and send one frame: the daemon's echo (the URI it was
   * upgraded on) when it went through, else {@code "status:<n>"} for the refusal.
   */
  static String upgrade(Vertx vertx, URI base, String path, String authorization)
      throws Exception {
    WebSocketClient client = vertx.createWebSocketClient();
    try {
      WebSocketConnectOptions options =
          new WebSocketConnectOptions().setHost(base.getHost()).setPort(base.getPort()).setURI(path);
      if (authorization != null) {
        options.addHeader("Authorization", authorization);
      }
      WebSocket socket =
          client.connect(options).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      CompletableFuture<String> reply = new CompletableFuture<>();
      socket.textMessageHandler(reply::complete);
      socket.writeTextMessage("hi");
      String answer = reply.get(10, TimeUnit.SECONDS);
      socket.close();
      return answer;
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof UpgradeRejectedException rejected) {
        return "status:" + rejected.getStatus();
      }
      throw failed;
    } finally {
      client.close();
    }
  }
}
