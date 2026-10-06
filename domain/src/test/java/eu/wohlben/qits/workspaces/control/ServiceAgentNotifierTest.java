package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.ServiceEventDto;
import eu.wohlben.qits.workspaces.entity.ServiceEventKind;
import eu.wohlben.qits.workspaces.entity.ServiceEventSeverity;
import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The half of the agent sink this context still owns: with nothing to deliver to, the event is
 * formatted, spooled, and handed over exactly once.
 *
 * <p>The monorepo's sibling case — an event landing as one prefixed stream-json user turn on the
 * newest running chat's stdin — is <strong>not asserted anywhere any more</strong>. It needed
 * {@code CommandService.launchChat} plus {@code CommandRegistry.attach}, and delivery is now
 * {@link WorkspaceChatInbox}'s contract, so the assertion belongs beside that port's
 * implementation in the command context.
 */
@QuarkusTest
public class ServiceAgentNotifierTest {

  @Inject FakeRepositoryLookup repositories;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Inject WorkspaceService workspaceService;

  @Inject ServiceAgentNotifier notifier;

  @Inject ServiceEventSpool spool;

  private String repoWithWorkspace() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createWorkspace(repoId, "work", "master", "work");
    return repoId;
  }

  private static ServiceEventDto event(String repoId, String summary, String excerpt) {
    return new ServiceEventDto(
        repoId,
        "work",
         1L,
        "service-1",
        "dev-server",
        ServiceEventKind.STATUS_CHANGED,
        ServiceEventSeverity.ERROR,
        ServiceStatus.CRASHED,
        summary,
        excerpt,
        "cmd-1",
        null,
        null,
        null,
        null,
        Instant.now());
  }

  @Test
  public void spoolsWhenNoChatIsRunning() throws Exception {
    String repoId = repoWithWorkspace();

    notifier.deliver(event(repoId, "crashed (exit 1)", "boom"));

    List<String> spooled = spool.drain(repoId, "work");
    assertEquals(1, spooled.size());
    assertTrue(
        spooled.get(0).startsWith("[service:dev-server] ERROR: crashed (exit 1)"), spooled.get(0));
    assertTrue(spooled.get(0).contains("boom"), spooled.get(0));
    assertEquals(List.of(), spool.drain(repoId, "work"), "drain empties the spool");
  }
}
