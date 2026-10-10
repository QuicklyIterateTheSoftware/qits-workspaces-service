package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import eu.wohlben.qits.workspaces.control.AgentDaemon;
import eu.wohlben.qits.workspaces.control.EntityFacts;
import eu.wohlben.qits.workspaces.testing.contracts.ConsumerPacts;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-workspace-daemon contract</b>: the agent-worktree surface {@link
 * DaemonAgentClient} calls in every workspace container (qits-1152), through the container's
 * reverse tunnel — here {@link StubDaemonTunnels}, pointed at a pact mock server.
 *
 * <p><b>Every row waits on qits-workspace-daemon.</b> It publishes no golden masters yet, so each
 * test is disabled naming the provider state it needs; the operationIds are proposed here. Each
 * state is expected to carry {@code workspaceRowId} (the daemon serves under {@code
 * /workspaces/container/<row id>/}) and {@code agentId}.
 */
@QuarkusTest
@Disabled("needs qits-workspace-daemon golden masters — each row names its provider state")
public class WorkspaceDaemonConsumerPactTest {

  static final String SIGNED_IN = "a workspace daemon with a signed-in harness";
  static final String AGENT_RUNNING = "a workspace daemon with an agent running";

  @Inject DaemonAgentClient client;

  @Inject Vertx vertx;

  @Test
  @Disabled(
      "needs provider state 'a workspace daemon with a signed-in harness' for startAgentWorktree in"
          + " qits-workspace-daemon")
  public void startAgentWorktree() {
    PactDslJsonBody body =
        new PactDslJsonBody()
            .stringType("agentId", "00000000-0000-4000-8000-0000000000a1")
            .stringType("workId", "00000000-0000-4000-8000-000000000001")
            .stringType("entityId", "contract-00000001-10")
            .stringType("wrapperBranch", "ticket/contract-00000001-10")
            .stringValue("harness", "CLAUDE")
            .stringValue("surface", "ticket.dispatch")
            .stringValue("mode", "INTERACTIVE")
            .stringType("instruction", "Work the ticket.");
    body.object("env").closeObject();
    run(
        SIGNED_IN,
        "startAgentWorktree",
        Trigger.operation("dispatchAgent"),
        body,
        List.of("$.commandId", "$.sessionId", "$.resumed"),
        (row, agentId) -> {
          AgentDaemon.Started started =
              client.start(
                  row,
                  new AgentDaemon.Start(
                      agentId,
                      "00000000-0000-4000-8000-000000000001",
                      "contract-00000001-10",
                      "ticket/contract-00000001-10",
                      "CLAUDE",
                      null,
                      Map.of(),
                      "Work the ticket.",
                      null,
                      null));
          assertEquals(AgentDaemon.Outcome.OK, started.outcome());
        });
  }

  @Test
  @Disabled(
      "needs provider state 'a workspace daemon with an agent running' for deliverAgentWorktreeTurn"
          + " in qits-workspace-daemon")
  public void deliverAgentWorktreeTurn() {
    run(
        AGENT_RUNNING,
        "deliverAgentWorktreeTurn",
        Trigger.operation("deliverAgentTurn"),
        new PactDslJsonBody().stringType("text", "Carry on."),
        List.of(),
        (row, agentId) -> assertEquals(AgentDaemon.Outcome.OK, client.turn(row, agentId, "Carry on.")));
  }

  @Test
  @Disabled(
      "needs provider state 'a workspace daemon with an agent running' for setAgentWorktreeEntity"
          + " in qits-workspace-daemon")
  public void setAgentWorktreeEntity() {
    run(
        AGENT_RUNNING,
        "setAgentWorktreeEntity",
        Trigger.operation("markAgentDispatchEntity"),
        new PactDslJsonBody()
            .stringType("title", "Fix the login")
            .stringType("status", "IMPLEMENTING")
            .booleanType("blocked", false),
        List.of(),
        (row, agentId) ->
            assertEquals(
                AgentDaemon.Outcome.OK,
                client.entity(row, agentId, new EntityFacts("Fix the login", "IMPLEMENTING", false), null)));
  }

  @Test
  @Disabled(
      "needs provider state 'a workspace daemon with an agent running' for checkAgentWorktree in"
          + " qits-workspace-daemon")
  public void checkAgentWorktree() {
    run(
        AGENT_RUNNING,
        "checkAgentWorktree",
        Trigger.schedule("AgentService.cleanUp"),
        null,
        List.of("$.clean", "$.dirty", "$.unpushed"),
        (row, agentId) -> assertTrue(client.cleanupCheck(row, agentId).isPresent()));
  }

  // --- support -----------------------------------------------------------------------------------

  interface Call {
    void run(Long row, String agentId);
  }

  private void run(
      String state,
      String operationId,
      Trigger trigger,
      DslPart requestBody,
      List<String> consumes,
      Call call) {
    Map<String, String> params = GoldenMasters.params(GoldenMasters.WORKSPACE_DAEMON, state);
    Long row = Long.valueOf(params.get("workspaceRowId"));
    String agentId = params.get("agentId");
    ConsumerPacts.verify(
        ConsumerPacts.pact(
            GoldenMasters.WORKSPACE_DAEMON,
            builder ->
                GoldenMasters.interaction(
                    builder,
                    GoldenMasters.WORKSPACE_DAEMON,
                    state,
                    operationId,
                    trigger,
                    requestBody,
                    consumes)),
        url -> {
          StubDaemonTunnels tunnels =
              StubDaemonTunnels.install(vertx, URI.create(url).getPort(), id -> id == row.longValue());
          try {
            call.run(row, agentId);
          } finally {
            tunnels.close();
          }
        });
  }
}
