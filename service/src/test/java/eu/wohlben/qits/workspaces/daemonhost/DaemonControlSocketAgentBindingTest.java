package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A {@code qits:agent} caller may open only the control socket of the workspace it was commissioned
 * for; a {@code qits:system} caller keeps today's behaviour. Real RS256 bearers against the gate-on
 * profile of {@link DaemonControlSocketMachineAuthTest}, so the roles and the {@code sub} come out of
 * a validated token rather than a test identity.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class DaemonControlSocketAgentBindingTest {

  private static final String OWN_AUDIENCE = "qits-workspaces";
  private static final Set<String> AGENT = Set.of("qits:agent");

  @Inject Vertx vertx;
  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceRepository workspaceRepository;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @TestHTTPResource("/")
  URI base;

  private String labelA;
  private String labelB;
  private Long rowA;
  private Long rowB;
  private String clientA;
  private String clientB;

  /** Two workspaces, each with a commissioned client of its own. Labels are unique per run. */
  @BeforeEach
  void twoCommissionedWorkspaces() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    labelA = "bind-a-" + suffix;
    labelB = "bind-b-" + suffix;
    workspaceService.createWorkspace(repoId, labelA, "master", labelA);
    workspaceService.createWorkspace(repoId, labelB, "master", labelB);
    rowA = workspaceIds.of(repoId, labelA);
    rowB = workspaceIds.of(repoId, labelB);
    clientA = "dyn-workspace-" + rowA + "-" + suffix;
    clientB = "dyn-workspace-" + rowB + "-" + suffix;
    commission(rowA, clientA);
    commission(rowB, clientB);
  }

  private void commission(Long rowId, String clientId) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaceRepository.findActiveById(rowId).orElseThrow().commissionedClientId =
                    clientId);
  }

  @Test
  void anAgentOpensItsOwnWorkspacesSocket() throws Exception {
    String token = DaemonMachineTokens.tokenWithRoles(clientA, AGENT, OWN_AUDIENCE);

    assertEquals(101, connect("/workspaces/daemon/" + rowA, token));
  }

  @Test
  void anAgentIsRefusedAnotherWorkspacesSocket() throws Exception {
    String token = DaemonMachineTokens.tokenWithRoles(clientA, AGENT, OWN_AUDIENCE);

    assertEquals(403, connect("/workspaces/daemon/" + rowB, token));
  }

  @Test
  void anAgentIsRefusedAWorkspaceWithNoCommission() throws Exception {
    commission(rowB, null);
    String token = DaemonMachineTokens.tokenWithRoles(clientB, AGENT, OWN_AUDIENCE);

    assertEquals(403, connect("/workspaces/daemon/" + rowB, token));
  }

  @Test
  void aSystemCallerKeepsTodaysBehaviour() throws Exception {
    // Any sub, any workspace: qits:system is not bound, until agents switch roles.
    String token = DaemonMachineTokens.token("some-other-client", OWN_AUDIENCE);

    assertEquals(101, connect("/workspaces/daemon/" + rowB, token));
  }

  @Test
  void theLegacyPathBindsTheAgentToItsOwnWorkspaceToo() throws Exception {
    String token = DaemonMachineTokens.tokenWithRoles(clientA, AGENT, OWN_AUDIENCE);

    assertEquals(101, connect("/api/workspace-daemon/" + labelA, token));
    assertEquals(403, connect("/api/workspace-daemon/" + labelB, token));
  }

  /** 101 when the socket opened, else the status the upgrade was refused with. */
  private int connect(String path, String token) throws Exception {
    WebSocketClient client = vertx.createWebSocketClient();
    try {
      WebSocketConnectOptions options =
          new WebSocketConnectOptions()
              .setHost(base.getHost())
              .setPort(base.getPort())
              .setURI(path)
              .addHeader("Authorization", "Bearer " + token);
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
