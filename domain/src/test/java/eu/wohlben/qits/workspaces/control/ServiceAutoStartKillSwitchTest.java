package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.ServiceInstanceDto;
import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The {@code qits.services.autostart-enabled=false} kill switch suppresses the whole coupling: a
 * container-started event brings up nothing, even for a default (auto-start) daemon.
 */
@QuarkusTest
@TestProfile(AutoStartOffProfile.class)
public class ServiceAutoStartKillSwitchTest {

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;
  @Inject WorkspaceService workspaceService;
  @Inject FakeWorkspaceConfigReader configReader;
  @Inject FakeWorkspaceServiceDriver driver;
  @Inject ServiceSupervisor supervisor;
  @Inject WorkspaceContainerEventPublisher containerEvents;

  @Test
  public void killSwitchSuppressesAutoStart() throws Exception {
    driver.reset();
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    workspaceService.createWorkspace(repoId, "work", "master", "work");
    String serviceId = "auto";
    configReader.setConfig(
        workspaceIds.of(repoId, "work"),
        new QitsConfig(
            null,
            null,
            null,
            List.of(
                new QitsConfig.ServiceDecl(
                    serviceId,
                    "auto",
                    null,
                    "sleep 300",
                    null,
                    true, // autoStart, but the
                    // kill switch overrides it
                    RestartPolicy.NEVER,
                    0,
                    "TERM",
                    null,
                    null,
                    null)),
            null));

    containerEvents.fireStarted(repoId, "work", workspaceIds.of(repoId, "work"));

    // Give the async observer ample time to (not) act, then confirm nothing launched — the service
    // is still listed, but as an unstarted STOPPED placeholder.
    Thread.sleep(1500);
    ServiceInstanceDto instance =
        supervisor.effectiveServices(workspaceIds.of(repoId, "work")).stream()
            .filter(i -> i.definition().id().equals(serviceId))
            .findFirst()
            .orElseThrow();
    assertEquals(
        ServiceStatus.STOPPED,
        instance.status(),
        "kill switch off ⇒ no auto-start, service stays STOPPED");
    assertTrue(driver.started().isEmpty(), "kill switch off ⇒ the daemon was never asked to start");
  }
}
