package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.events.AgentHarnessExited;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspacedaemon.protocol.AgentHarnessExit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-1236's wiring: a daemon's {@link AgentHarnessExit} frame (capability 11) reaches {@code
 * AgentService.onHarnessExited} through {@code WorkspaceDaemonRegistry.onMessage}, the same idiom
 * {@link eu.wohlben.qits.workspaces.bus.EventstreamLifecycleAnnouncerTest} uses to see a lifecycle
 * event all the way to the (dark-in-test) bus: the frame goes through {@code onMessage} directly,
 * and what is asserted is the {@link AgentHarnessExited} it produces.
 */
@QuarkusTest
class WorkspaceDaemonRegistryHarnessExitTest {

  @Inject WorkspaceDaemonRegistry registry;

  @Inject AgentWorktreeRepository agents;

  private final List<QitsEvent> published = new CopyOnWriteArrayList<>();

  @BeforeEach
  void recordTheBus() {
    QuarkusMock.installMockForType(
        new QitsEventBus() {
          @Override
          public void publish(QitsEvent event, UUID parentEventId) {
            published.add(event);
          }
        },
        QitsEventBus.class);
  }

  @Test
  void aHarnessExitFrameAnnouncesAgentHarnessExited() throws Exception {
    UUID agentId = agent("repo-harness-exit", "w-harness-exit");
    Instant at = Instant.parse("2026-10-14T09:00:00Z");

    registry.onMessage(
        42L,
        null,
        new AgentHarnessExit(
            agentId.toString(),
            "cmd-7",
            "claude",
            "EXITED",
            0,
            AgentHarnessExit.SELF,
            at));

    AgentHarnessExited event = await(AgentHarnessExited.class);
    assertEquals(agentId.toString(), event.agentId());
    assertEquals("w-harness-exit", event.workId());
    assertEquals("42", event.workspaceId());
    assertEquals("repo-harness-exit", event.repositoryId());
    assertEquals("cmd-7", event.commandId());
    assertEquals("EXITED", event.status());
    assertEquals(0, event.exitCode());
    assertEquals(AgentHarnessExit.SELF, event.stoppedBy());
    assertEquals(at, event.occurredAt());
  }

  @Test
  void aNullAgentIdIsTheSignInTerminalAndAnnouncesNothing() throws Exception {
    registry.onMessage(
        42L,
        null,
        new AgentHarnessExit(null, "cmd-8", "claude", "EXITED", 0, AgentHarnessExit.SELF, Instant.now()));

    // Give the sink thread a moment it would need if it were (wrongly) dispatching anything.
    TimeUnit.MILLISECONDS.sleep(200);
    assertTrue(published.isEmpty(), published.toString());
  }

  private UUID agent(String repositoryId, String workId) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = repositoryId;
              row.workId = workId;
              row.wrapperBranch = "ticket/" + workId;
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              agents.persist(row);
            });
    return id;
  }

  @SuppressWarnings("unchecked")
  private <T extends QitsEvent> T await(Class<T> type) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      for (QitsEvent event : published) {
        if (type.isInstance(event)) {
          return (T) event;
        }
      }
      TimeUnit.MILLISECONDS.sleep(25);
    }
    throw new AssertionError("no " + type.getSimpleName() + " published: " + published);
  }
}
