package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.AgentBranchRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The agent tables (qits-1152, {@code V21}) as the schema keeps them: one ACTIVE agent per work
 * item, any number of RUNNING agents per workspace (qits-1212, {@code V24}), a QUEUED agent in no
 * workspace, a STOPPED one in its workspace or none (epic qits-1667, {@code V35}), and a pushed branch recorded once per (agent, repository, branch).
 */
@QuarkusTest
public class AgentSchemaTest {

  @Inject AgentWorktreeRepository agents;
  @Inject AgentBranchRepository branches;
  @Inject WorkspaceRepository workspaces;

  private Long slot() {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Workspace row = new Workspace();
              row.repositoryId = "schema-" + UUID.randomUUID();
              row.workspaceId = "ws-schema";
              row.status = WorkspaceStatus.ACTIVE;
              row.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
              row.placement = WorkspacePlacement.TARGET;
              workspaces.persist(row);
              return row.id;
            });
  }

  private UUID insert(String workId, Consumer<AgentWorktree> shape) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree agent = new AgentWorktree();
              agent.id = id;
              agent.repositoryId = "wrapper";
              agent.workId = workId;
              agent.wrapperBranch = "ticket/" + workId;
              agent.createdAt = Instant.now();
              shape.accept(agent);
              agents.persist(agent);
              agents.flush();
            });
    return id;
  }

  @Test
  void oneActiveAgentPerWorkItem() {
    String workId = "schema-" + UUID.randomUUID();
    insert(workId, a -> {});
    assertThrows(RuntimeException.class, () -> insert(workId, a -> {}));
    assertDoesNotThrow(() -> insert(workId, a -> a.state = AgentState.OBSOLETE));
  }

  @Test
  void aWorkspaceMayHoldTwoRunningAgents() {
    Long slot = slot();
    Consumer<AgentWorktree> running =
        a -> {
          a.workspaceId = slot;
          a.runState = AgentRunState.RUNNING;
        };
    insert("schema-" + UUID.randomUUID(), running);
    assertDoesNotThrow(() -> insert("schema-" + UUID.randomUUID(), running));
    assertDoesNotThrow(
        () ->
            insert(
                "schema-" + UUID.randomUUID(),
                a -> {
                  a.workspaceId = slot;
                  a.runState = AgentRunState.YIELDED;
                }));
  }

  @Test
  void onlyAQueuedOrStoppedAgentHasNoWorkspace() {
    assertThrows(
        RuntimeException.class,
        () -> insert("schema-" + UUID.randomUUID(), a -> a.runState = AgentRunState.YIELDED));
    // V35 (epic qits-1667): an agent stopped while still QUEUED gave up its queue place.
    assertDoesNotThrow(
        () -> insert("schema-" + UUID.randomUUID(), a -> a.runState = AgentRunState.STOPPED));
  }

  @Test
  void aStoppedAgentKeepsItsWorkspace() {
    // V35 replaced V21's inline run_state check (agent_worktree_run_state_check): STOPPED is a
    // run state the column takes, placed or not.
    Long slot = slot();
    UUID id =
        insert(
            "schema-" + UUID.randomUUID(),
            a -> {
              a.workspaceId = slot;
              a.runState = AgentRunState.STOPPED;
            });
    AgentWorktree stored = QuarkusTransaction.requiringNew().call(() -> agents.findById(id));
    assertEquals(AgentRunState.STOPPED, stored.runState);
    assertEquals(slot, stored.workspaceId);
  }

  @Test
  void aPushIsRecordedOnceAndMovesItsSha() {
    UUID id = insert("schema-" + UUID.randomUUID(), a -> {});
    QuarkusTransaction.requiringNew()
        .run(() -> branches.upsert(id, "repo-a", "ticket/x-fix", "aaa", Instant.now()));
    QuarkusTransaction.requiringNew()
        .run(() -> branches.upsert(id, "repo-a", "ticket/x-fix", "bbb", Instant.now()));

    var pushed = QuarkusTransaction.requiringNew().call(() -> branches.findByAgent(id));
    assertEquals(1, pushed.size());
    assertEquals("bbb", pushed.get(0).sha);
    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(() -> branches.agentsOn("repo-a", "ticket/x-fix"))
            .contains(id));
  }
}
