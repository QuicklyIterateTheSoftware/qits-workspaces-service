package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import eu.wohlben.qits.workspaces.control.EntityFacts;
import eu.wohlben.qits.workspaces.control.WorkspaceAgentLauncher.AgentState;
import eu.wohlben.qits.workspaces.control.WorkspaceAgentLauncher.DeliveryOutcome;
import eu.wohlben.qits.workspaces.control.WorkspaceAgentLauncher.Launch;
import eu.wohlben.qits.workspaces.testing.contracts.ConsumerPacts;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-workspace-daemon contract</b>: the agent surface {@link
 * DaemonAgentClient} calls in every workspace container, through the container's reverse tunnel —
 * here {@link StubDaemonTunnels}, pointed at a pact mock server.
 *
 * <p><b>Every row waits on qits-workspace-daemon.</b> It publishes no golden masters yet, so each
 * test is disabled naming the provider state it needs; the operationIds are proposed here. Each
 * state is expected to carry {@code workspaceRowId}: the daemon serves under {@code
 * /workspaces/container/<row id>/}, the prefix this service hands it at container creation, and the
 * client sends the full path.
 *
 * <p><b>What is read.</b> The running-command list for each entry's {@code command.status}, {@code
 * command.kind} and {@code command.agentSessions}; a launch's {@code command.id}; a turn's {@code
 * delivered}. The blocked marker and the subject facts are read by status alone. The bodies sent are
 * this service's own, so they are matched exactly.
 */
@QuarkusTest
@Disabled("needs qits-workspace-daemon golden masters — each row names its provider state")
public class WorkspaceDaemonConsumerPactTest {

  static final String IDLE = "a workspace daemon with no agent running";
  static final String AGENT_RUNNING = "a workspace daemon with a chat agent running";

  @Inject DaemonAgentClient client;

  @Inject Vertx vertx;

  @Test
  @Disabled(
      "needs provider state 'a workspace daemon with a chat agent running' for listCommands in"
          + " qits-workspace-daemon")
  public void listCommands() {
    run(
        AGENT_RUNNING,
        "listCommands",
        Trigger.operation("dispatchAgent"),
        null,
        List.of(
            "$.entries[*].command.status",
            "$.entries[*].command.kind",
            "$.entries[*].command.agentSessions"),
        row -> assertEquals(AgentState.RUNNING, client.agentState(row)));
  }

  @Test
  @Disabled(
      "needs provider state 'a workspace daemon with no agent running' for launchAgent in"
          + " qits-workspace-daemon")
  public void launchAgent() {
    DslPart body =
        new PactDslJsonBody()
            .stringValue("scope", "REPOSITORY")
            .stringValue("surface", "ticket.dispatch")
            .stringValue("mode", "CHAT")
            .stringType("initialContext", "Work the ticket.")
            .booleanValue("deliverTaskPrompt", false);
    run(
        IDLE,
        "launchAgent",
        Trigger.operation("dispatchAgent"),
        body,
        List.of("$.command.id"),
        row -> {
          Launch launch = client.launch(row, "Work the ticket.");
          assertTrue(launch.accepted());
          assertTrue(launch.commandId() != null, "the launch names its command");
        });
  }

  @Test
  @Disabled(
      "needs provider state 'a workspace daemon with a chat agent running' for deliverAgentTurn"
          + " in qits-workspace-daemon")
  public void deliverAgentTurn() {
    run(
        AGENT_RUNNING,
        "deliverAgentTurn",
        Trigger.operation("deliverAgentTurn"),
        new PactDslJsonBody().stringType("text", "Carry on."),
        List.of("$.delivered"),
        row -> assertEquals(DeliveryOutcome.DELIVERED, client.deliver(row, "Carry on.")));
  }

  @Test
  @Disabled(
      "needs provider state 'a workspace daemon with a chat agent running' for setAgentBlocked in"
          + " qits-workspace-daemon")
  public void setAgentBlocked() {
    run(
        AGENT_RUNNING,
        "setAgentBlocked",
        Trigger.operation("markAgentBlocked"),
        new PactDslJsonBody().booleanType("blocked", true),
        List.of(),
        row -> assertTrue(client.setBlocked(row, true)));
  }

  @Test
  @Disabled(
      "needs provider state 'a workspace daemon with a chat agent running' for setAgentEntity in"
          + " qits-workspace-daemon")
  public void setAgentEntity() {
    run(
        AGENT_RUNNING,
        "setAgentEntity",
        Trigger.operation("markAgentEntity"),
        new PactDslJsonBody()
            .stringType("title", "Fix the login")
            .stringType("status", "IMPLEMENTING")
            .booleanType("blocked", false),
        List.of(),
        row ->
            assertTrue(
                client.setEntity(row, new EntityFacts("Fix the login", "IMPLEMENTING", false))));
  }

  // --- support -----------------------------------------------------------------------------------

  private void run(
      String state,
      String operationId,
      Trigger trigger,
      DslPart requestBody,
      List<String> consumes,
      Consumer<Long> call) {
    Long row =
        Long.valueOf(
            GoldenMasters.params(GoldenMasters.WORKSPACE_DAEMON, state).get("workspaceRowId"));
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
            call.accept(row);
          } finally {
            tunnels.close();
          }
        });
  }
}
