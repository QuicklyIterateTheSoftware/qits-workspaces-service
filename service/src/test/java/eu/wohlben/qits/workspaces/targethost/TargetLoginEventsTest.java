package eu.wohlben.qits.workspaces.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.FakeTargetPlacement;
import eu.wohlben.qits.workspaces.control.TargetLogins;
import eu.wohlben.qits.workspaces.control.TargetPlacement;
import eu.wohlben.qits.workspaces.entity.AgentHarness;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * qits-system's {@code TargetLoginChanged} and the sign-in reconcile (qits-2088): a target that
 * loses its claude sign-in puts {@code TARGET_LOGIN_ABSENT} on the RUNNING agents whose workspace
 * runs on it, and only those; signed in again, it clears; the reconcile seeds the same from the
 * target listing, and an unreadable listing keeps what is held. The bus is dark in {@code %test},
 * so events are handed in directly.
 */
@QuarkusTest
public class TargetLoginEventsTest {

  private static final String REPO = "target-login-wrapper";

  @Inject TargetLoginEvents events;
  @Inject TargetLogins logins;
  @Inject FakeTargetPlacement placement;
  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceRepository workspaces;
  @Inject AgentWorktreeRepository agents;

  private final List<UUID> agentIds = new ArrayList<>();
  private final List<Long> rowIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    logins.clear();
    placement.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              agentIds.forEach(agents::deleteById);
              rowIds.forEach(workspaces::deleteById);
            });
    agentIds.clear();
    rowIds.clear();
  }

  /** A RUNNING agent on a workspace slot running on {@code targetId}. */
  private UUID agentOn(UUID targetId, Consumer<AgentWorktree> shape) {
    UUID id = UUID.randomUUID();
    Long slot =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace ws = new Workspace();
                  ws.repositoryId = REPO;
                  ws.workspaceId = "ws-login-" + id.toString().substring(0, 8);
                  ws.status = WorkspaceStatus.ACTIVE;
                  ws.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                  ws.placement = WorkspacePlacement.TARGET;
                  ws.targetId = targetId;
                  workspaces.persist(ws);
                  return ws.id;
                });
    rowIds.add(slot);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.workspaceId = slot;
              row.repositoryId = REPO;
              row.workId = "login-" + id;
              row.wrapperBranch = "ticket/" + row.workId;
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              row.runState = AgentRunState.RUNNING;
              row.entityStatus = "IMPLEMENTING";
              row.entityBlocked = false;
              row.commissionedTokenSubject = "tok-workspace-agent-" + id;
              shape.accept(row);
              agents.persist(row);
            });
    agentIds.add(id);
    return id;
  }

  /** A QUEUED agent with no workspace slot yet. */
  private UUID unplaced() {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = REPO;
              row.workId = "login-" + id;
              row.wrapperBranch = "ticket/" + row.workId;
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              agents.persist(row);
            });
    agentIds.add(id);
    return id;
  }

  private AgentWorktree row(UUID id) {
    return QuarkusTransaction.requiringNew().call(() -> agents.findById(id));
  }

  private static TargetLoginChanged changed(UUID targetId, String claude, String checkedAt) {
    return new TargetLoginChanged(
        UUID.randomUUID(), targetId.toString(), "lab", claude, "PRESENT", checkedAt, Instant.now());
  }

  @Test
  void aTargetThatLosesItsClaudeSignInIsOnItsRunningAgentsUntilItIsBack() {
    repositories.register(REPO);
    UUID lab = UUID.randomUUID();
    UUID onLab = agentOn(lab, a -> {});
    UUID elsewhere = agentOn(UUID.randomUUID(), a -> {});
    UUID kimi = agentOn(lab, a -> a.harness = AgentHarness.KIMI);
    UUID queued = agentOn(lab, a -> a.runState = AgentRunState.QUEUED);
    UUID noSlot = unplaced();

    events.onEvent(changed(lab, "ABSENT", "2026-11-11T10:42:00Z"));

    AgentWorktree signedOut = row(onLab);
    assertEquals("TARGET_LOGIN_ABSENT", signedOut.attentionCode);
    assertEquals("Target lab lost its claude sign-in at 10:42", signedOut.attentionReason);
    assertEquals(Instant.parse("2026-11-11T10:42:00Z"), signedOut.attentionSince);
    assertNull(signedOut.attentionWaitId);
    assertNull(row(elsewhere).attentionCode, "another target's agent");
    assertNull(row(kimi).attentionCode, "kimi is still signed in there");
    assertNull(row(queued).attentionCode, "not RUNNING");
    assertNull(row(noSlot).attentionCode, "on no target yet");

    events.onEvent(changed(lab, "PRESENT", "2026-11-11T10:50:00Z"));

    AgentWorktree back = row(onLab);
    assertNull(back.attentionCode);
    assertNull(back.attentionReason);
  }

  @Test
  void anEventNamingNoTargetMovesNothing() {
    events.onEvent(
        new TargetLoginChanged(UUID.randomUUID(), "not-a-uuid", "lab", "ABSENT", null, null, Instant.now()));
    events.onEvent(null);

    assertFalse(logins.anyAbsent());
  }

  @Test
  void theReconcileSeedsTheSignInsFromTheListing() {
    repositories.register(REPO);
    UUID lab = UUID.randomUUID();
    UUID onLab = agentOn(lab, a -> {});
    Instant checkedAt = Instant.parse("2026-11-11T09:15:00Z");
    placement.targets(
        List.of(new TargetPlacement.Target(lab, "lab", null, null, "ABSENT", "PRESENT", checkedAt)));

    assertTrue(events.reconcile());
    assertEquals("TARGET_LOGIN_ABSENT", row(onLab).attentionCode);
    assertFalse(events.reconcile(), "the same listing again changes nothing");

    // An unreadable listing keeps what is held.
    placement.unreachable(new TargetPlacement.LeaseRefused(-1, null, "down", null));
    assertFalse(events.reconcile());
    assertTrue(logins.anyAbsent());
    placement.reset();

    placement.targets(
        List.of(new TargetPlacement.Target(lab, "lab", null, null, "PRESENT", "PRESENT", checkedAt)));
    assertTrue(events.reconcile());
    assertNull(row(onLab).attentionCode);
  }
}
