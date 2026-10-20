package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import eu.wohlben.qits.workspaces.control.AgentDaemon;
import eu.wohlben.qits.workspaces.control.EntityFacts;
import eu.wohlben.qits.workspaces.testing.contracts.ContractRow;
import eu.wohlben.qits.workspaces.testing.contracts.ContractTable;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Request;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import io.quarkus.arc.Arc;
import io.vertx.core.Vertx;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * <b>What qits-workspaces asks a workspace daemon, and why</b> (qits-1149): the agent-worktree
 * surface {@link DaemonAgentClient} calls in every workspace container (qits-1152), through the
 * container's reverse tunnel — here {@link StubDaemonTunnels}, pointed at a pact mock server. The
 * table {@code WorkspaceDaemonConsumerPactTest} runs and {@code ConsumerPactFilesTest} writes to
 * {@code pacts/qits-workspaces-service_qits-workspace-daemon.json}.
 *
 * <p><b>One trigger.</b> Every daemon call is the scheduler's ({@code AgentService.reconcile}): the
 * doors write rows, and the pass after them drives the daemons — start, turn, entity facts, the
 * cleanup check and the removal.
 *
 * <p><b>Every answer the client branches on is a row</b>: an accepted call ({@code OK}), a start
 * refused because nobody signed the harness in ({@code REFUSED}), an agent the daemon does not know
 * ({@code UNKNOWN_AGENT}, the cleanup check's 404 that is not "No such endpoint" included), leftover
 * work and a busy harness refusing a removal ({@code REFUSED}, {@code BUSY}), and a forced removal.
 * Every state carries {@code workspaceRowId} (the daemon serves under {@code
 * /workspaces/container/<row id>/}) and {@code agentId}.
 *
 * <p><b>{@code agentConfig} (qits-1382).</b> The accepted start (state {@link #SIGNED_IN}) is
 * extended rather than given a sibling interaction: the golden masters record only one 200 for
 * {@code startAgentWorktree} ({@link #SIGNED_IN}), so a second interaction naming the same state
 * and operation would collide on ({@code description}, state) — see {@code ContractTable}'s
 * constructor. The refused start ({@link #SIGNED_OUT}) keeps the plain body, so the committed file
 * still shows a start with no {@code agentConfig}.
 */
public final class WorkspaceDaemonContract {

  // qits-workspace-daemon's states (golden masters 2026.1018.152111).
  static final String SIGNED_IN = "a workspace daemon with a signed-in harness";
  static final String AGENT_RUNNING = "a workspace daemon with an agent running";
  static final String SIGNED_OUT = "a workspace daemon with a harness nobody signed in";
  static final String WITH_FILES = "a workspace daemon with an agent whose worktree holds files";
  static final String LEFTOVER_WORK =
      "a workspace daemon with an agent whose worktree holds uncommitted work";
  static final String BUSY = "a workspace daemon with an agent busy in the background";

  static final Trigger RECONCILE = Trigger.schedule("AgentService.reconcile");

  private static final String WORK_ID = "00000000-0000-4000-8000-000000000001";
  private static final String ENTITY_ID = "contract-00000001-10";
  private static final String WRAPPER_BRANCH = "ticket/contract-00000001-10";

  // qits-1382: the qits-agent-config repository a start may name, switched by configuration.
  private static final String AGENT_CONFIG_REPOSITORY_ID = "00000000-0000-4000-8000-0000000000c1";
  private static final String AGENT_CONFIG_REPOSITORY_PATH = "qits/qits-agent-config";

  public static final ContractTable TABLE =
      new ContractTable(
          GoldenMasters.WORKSPACE_DAEMON,
          List.of(
              // The ACCEPTED start is the one interaction extended to carry agentConfig (qits-1382):
              // the refusal below keeps the plain shape, so the pact still shows a start without it.
              row(
                  SIGNED_IN,
                  "startAgentWorktree",
                  startBodyWithAgentConfig(),
                  List.of("$.commandId", "$.sessionId", "$.resumed", "$.launched"),
                  (client, row, agentId) ->
                      assertEquals(
                          AgentDaemon.Outcome.OK,
                          client.start(row, startWithAgentConfig(agentId)).outcome())),
              row(
                  AGENT_RUNNING,
                  "deliverAgentTurn",
                  turnBody(),
                  List.of(),
                  (client, row, agentId) ->
                      assertEquals(AgentDaemon.Outcome.OK, client.turn(row, agentId, "Carry on."))),
              row(
                  AGENT_RUNNING,
                  "setAgentEntity",
                  entityBody(),
                  List.of(),
                  (client, row, agentId) ->
                      assertEquals(AgentDaemon.Outcome.OK, client.entity(row, agentId, facts(), null))),
              row(
                  AGENT_RUNNING,
                  "agentCleanupCheck",
                  null,
                  List.of("$.clean", "$.dirty", "$.unpushed", "$.busy"),
                  (client, row, agentId) -> {
                    AgentDaemon.CleanupAnswer answer = client.cleanupCheck(row, agentId);
                    assertTrue(answer.isFound(), String.valueOf(answer));
                    assertTrue(answer.check().clean(), "nothing to lose");
                    assertFalse(answer.check().busy(), "not busy");
                  }),
              row(
                  AGENT_RUNNING,
                  "removeAgentWorktree",
                  null,
                  List.of(),
                  (client, row, agentId) ->
                      assertEquals(AgentDaemon.Outcome.OK, client.remove(row, agentId, false))),
              // --- the refusals (qits-workspace-daemon-rr-126) -----------------------------------
              row(
                  SIGNED_OUT,
                  "startAgentWorktree",
                  startBody(),
                  List.of(),
                  (client, row, agentId) ->
                      assertEquals(
                          AgentDaemon.Outcome.REFUSED, client.start(row, start(agentId)).outcome())),
              row(
                  SIGNED_IN,
                  "deliverAgentTurn",
                  turnBody(),
                  List.of(),
                  (client, row, agentId) ->
                      assertEquals(
                          AgentDaemon.Outcome.UNKNOWN_AGENT, client.turn(row, agentId, "Carry on."))),
              row(
                  SIGNED_IN,
                  "setAgentEntity",
                  entityBody(),
                  List.of(),
                  (client, row, agentId) ->
                      assertEquals(
                          AgentDaemon.Outcome.UNKNOWN_AGENT,
                          client.entity(row, agentId, facts(), null))),
              // The 404 is the daemon not knowing the agent, told apart from "No such endpoint" by
              // its message: the pact binds the message's presence, the client reads its value.
              row(
                  SIGNED_IN,
                  "agentCleanupCheck",
                  null,
                  List.of("$.message"),
                  (client, row, agentId) ->
                      assertEquals(
                          AgentDaemon.Outcome.UNKNOWN_AGENT,
                          client.cleanupCheck(row, agentId).outcome())),
              row(
                  SIGNED_IN,
                  "removeAgentWorktree",
                  null,
                  List.of(),
                  (client, row, agentId) ->
                      assertEquals(
                          AgentDaemon.Outcome.UNKNOWN_AGENT, client.remove(row, agentId, false))),
              row(
                  LEFTOVER_WORK,
                  "agentCleanupCheck",
                  null,
                  List.of("$.clean", "$.dirty", "$.unpushed", "$.busy"),
                  (client, row, agentId) -> {
                    AgentDaemon.CleanupAnswer answer = client.cleanupCheck(row, agentId);
                    assertTrue(answer.isFound(), String.valueOf(answer));
                    assertFalse(answer.check().clean(), "work would be lost");
                    assertFalse(answer.check().dirty().isEmpty(), "the dirty worktree is named");
                  }),
              row(
                  LEFTOVER_WORK,
                  "removeAgentWorktree",
                  null,
                  List.of("$.clean", "$.busy"),
                  (client, row, agentId) ->
                      assertEquals(AgentDaemon.Outcome.REFUSED, client.remove(row, agentId, false))),
              row(
                  BUSY,
                  "agentCleanupCheck",
                  null,
                  List.of("$.clean", "$.dirty", "$.unpushed", "$.busy"),
                  (client, row, agentId) -> {
                    AgentDaemon.CleanupAnswer answer = client.cleanupCheck(row, agentId);
                    assertTrue(answer.isFound(), String.valueOf(answer));
                    assertTrue(answer.check().busy(), "the harness is still working");
                  }),
              row(
                  BUSY,
                  "removeAgentWorktree",
                  null,
                  List.of("$.clean", "$.busy"),
                  (client, row, agentId) ->
                      assertEquals(AgentDaemon.Outcome.BUSY, client.remove(row, agentId, false))),
              // A person's forced discard (discardAgent with force) removes whatever is left.
              ContractRow.of(
                  Trigger.operation("discardAgent"),
                  WITH_FILES,
                  "removeAgentWorktree",
                  Request.NONE,
                  List.of(),
                  call(
                      (client, row, agentId) ->
                          assertEquals(AgentDaemon.Outcome.OK, client.remove(row, agentId, true))))));

  private WorkspaceDaemonContract() {}

  /** One daemon call, with the row id and agent id its state names. */
  @FunctionalInterface
  interface DaemonCall {
    void run(DaemonAgentClient client, Long workspaceRowId, String agentId);
  }

  private static ContractRow row(
      String state, String operationId, DslPart body, List<String> consumes, DaemonCall call) {
    return ContractRow.of(RECONCILE, state, operationId, Request.json(body), consumes, call(call));
  }

  /** The shipped client, its tunnel for the state's row pointed at the mock server. */
  private static ContractRow.Call call(DaemonCall call) {
    return (url, params) -> {
      Long row = Long.valueOf(params.get("workspaceRowId"));
      Vertx vertx = Arc.container().instance(Vertx.class).get();
      StubDaemonTunnels tunnels =
          StubDaemonTunnels.install(vertx, URI.create(url).getPort(), id -> id == row.longValue());
      try {
        call.run(Arc.container().instance(DaemonAgentClient.class).get(), row, params.get("agentId"));
      } finally {
        tunnels.close();
      }
    };
  }

  private static AgentDaemon.Start start(String agentId) {
    return new AgentDaemon.Start(
        agentId,
        WORK_ID,
        ENTITY_ID,
        WRAPPER_BRANCH,
        "CLAUDE",
        null,
        Map.of(),
        "Work the ticket.",
        null,
        null,
        null);
  }

  /** The same start, with the agent-config checkout a configured daemon acts on (qits-1382). */
  private static AgentDaemon.Start startWithAgentConfig(String agentId) {
    return new AgentDaemon.Start(
        agentId,
        WORK_ID,
        ENTITY_ID,
        WRAPPER_BRANCH,
        "CLAUDE",
        null,
        Map.of(),
        "Work the ticket.",
        null,
        null,
        new AgentDaemon.AgentConfig(AGENT_CONFIG_REPOSITORY_ID, AGENT_CONFIG_REPOSITORY_PATH));
  }

  private static EntityFacts facts() {
    return new EntityFacts("Fix the login", "IMPLEMENTING", false);
  }

  /** What {@link DaemonAgentClient#start} sends for a fresh agent with no entity facts. */
  private static DslPart startBody() {
    PactDslJsonBody body =
        new PactDslJsonBody()
            .stringType("agentId", "00000000-0000-4000-8000-0000000000a1")
            .stringType("workId", WORK_ID)
            .stringType("entityId", ENTITY_ID)
            .stringType("wrapperBranch", WRAPPER_BRANCH)
            .stringValue("harness", "CLAUDE")
            .stringValue("surface", DaemonAgentClient.SURFACE)
            .stringValue("mode", DaemonAgentClient.MODE)
            .stringType("instruction", "Work the ticket.");
    body.object("env").closeObject();
    return body;
  }

  /** {@link #startBody()}, plus the agent-config checkout (qits-1382). */
  private static DslPart startBodyWithAgentConfig() {
    PactDslJsonBody body = (PactDslJsonBody) startBody();
    body.object("agentConfig")
        .stringType("repositoryId", AGENT_CONFIG_REPOSITORY_ID)
        .stringType("repositoryPath", AGENT_CONFIG_REPOSITORY_PATH)
        .closeObject();
    return body;
  }

  private static DslPart turnBody() {
    return new PactDslJsonBody().stringType("text", "Carry on.");
  }

  private static DslPart entityBody() {
    return new PactDslJsonBody()
        .stringType("title", "Fix the login")
        .stringType("status", "IMPLEMENTING")
        .booleanType("blocked", false);
  }
}
