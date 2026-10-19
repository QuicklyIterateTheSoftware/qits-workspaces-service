package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.events.AgentRemoved;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code AgentService.start} fills a start's {@link AgentDaemon.AgentConfig} only where both
 * {@code qits.workspace.agent-config.*} properties are set (qits-1382). {@link AgentServiceTest}'s
 * default profile ships both blank, so every start it drives already proves the absent half; this
 * class is the present half, on {@link SharedTestOverridesProfile}, which pins the pair for it.
 */
@QuarkusTest
@TestProfile(SharedTestOverridesProfile.class)
public class AgentConfigStartTest {

  @Inject AgentService service;
  @Inject AgentWorktreeRepository agents;
  @Inject FakeRepositoryLookup repositories;
  @Inject FakeAgentDaemon daemon;
  @Inject FakeWorkspaceDaemonLiveness liveness;
  @Inject FakeCredentialCommissioner commissioner;

  private String repoId;

  @BeforeEach
  void setUp() {
    daemon.reset();
    commissioner.reset();
    repoId = "agent-config-" + UUID.randomUUID().toString().substring(0, 8);
    repositories.register(repoId, "main");
    QuarkusMock.installMockForType(
        new WorkspaceAddressPlanes() {
          @Override
          public WorkspaceAddressPlane plane() {
            return WorkspaceAddressPlane.of("example.test", List.of("registry.dev.localhost:8080"));
          }
        },
        WorkspaceAddressPlanes.class);
  }

  @AfterEach
  void tearDown() {
    List<UUID> left =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    agents.list("repositoryId = ?1 and state <> ?2", repoId, AgentState.REMOVED)
                        .stream()
                        .map(a -> a.id)
                        .toList());
    left.forEach(id -> service.markRemoved(id, AgentRemoved.DISCARDED, false, "the test is over"));
    commissioner.reset();
  }

  private AgentService.Dispatch dispatch(String workId, String instruction) {
    return service.dispatch(
        new AgentService.AgentRequest(
            repoId,
            "ticket/" + workId,
            workId,
            workId,
            workId,
            null,
            true,
            null,
            instruction,
            new EntityFacts("A title", "REFINED", false)));
  }

  @Test
  void bothPropertiesSetFillsAStartsAgentConfig() {
    AgentService.Dispatch dispatched = dispatch("w-agent-config", "go");
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    assertNotNull(slot, "an admin agent always gets a DIRECT slot");
    liveness.markLive(slot);

    service.reconcile();

    AgentDaemon.Start start =
        daemon.starts().stream().filter(s -> s.agentId().equals(agentId)).findFirst().orElseThrow();
    assertNotNull(start.agentConfig(), "both properties are set on this profile");
    assertEquals("00000000-0000-4000-8000-0000000000c1", start.agentConfig().repositoryId());
    assertEquals("test-project/qits-agent-config", start.agentConfig().repositoryPath());

    liveness.markDead(slot);
  }
}
