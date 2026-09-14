package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.dto.ServiceInstanceDto;
import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The container&#8594;service <em>stop</em> coupling: a {@code WorkspaceContainerStopping} event
 * settles a workspace's live services STOPPED (INFO, no crash) instead of leaving them to be
 * misread as a crash and resurrected — deterministically, since the container's imminent {@code rm}
 * may beat the daemon's own STOPPED event. On a graceful stop the daemon is also asked to signal
 * each service for a clean flush. The kill-switch case is {@link ServiceSettleKillSwitchTest}. The
 * host is a pure projection; a {@link FakeWorkspaceServiceDriver} plays the daemon. Definitions are
 * config-declared, staged into the {@link FakeWorkspaceConfigReader}.
 */
@QuarkusTest
// Auto-start OFF so these tests isolate the settle direction; services are started by hand below.
// Auto-STOP is what they are about and is left at its shipped default of on — it used to be stated
// here as `true`, which is the same value and cost a Quarkus restart of its own (see
// AutoStartOffProfile).
@TestProfile(AutoStartOffProfile.class)
public class ServiceLifecycleCouplerSettleTest {

  private static final long AWAIT_MILLIS = 15_000;

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;
  @Inject WorkspaceService workspaceService;
  @Inject FakeWorkspaceConfigReader configReader;
  @Inject FakeWorkspaceServiceDriver driver;
  @Inject ServiceSupervisor supervisor;
  @Inject WorkspaceContainerEventPublisher containerEvents;
  @Inject ContainerRuntime containers;

  @BeforeEach
  void resetFakes() {
    configReader.clear(); // the fake is a shared singleton across this class's test methods
    driver.reset();
  }

  private String repoWithWorkspace() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    workspaceService.createWorkspace(repoId, "work", "master", "work");
    return repoId;
  }

  private String createService(String repoId, String name, String command, RestartPolicy policy) {
    configReader.setConfig(
        workspaceIds.of(repoId, "work"),
        new QitsConfig(
            null,
            null,
            null,
            List.of(
                new QitsConfig.ServiceDecl(
                    name, name, null, command, null, false, policy, 3, "TERM", null, null, null)),
            null));
    return name;
  }

  private ServiceInstanceDto instanceOf(String repoId, String serviceId) {
    return supervisor.effectiveServices(workspaceIds.of(repoId, "work")).stream()
        .filter(i -> i.definition().id().equals(serviceId))
        .findFirst()
        .orElse(null);
  }

  private ServiceInstanceDto awaitStatus(String repoId, String serviceId, ServiceStatus expected)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    ServiceInstanceDto last = null;
    while (System.currentTimeMillis() < deadline) {
      last = instanceOf(repoId, serviceId);
      if (last != null && last.status() == expected) {
        return last;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for " + expected + "; last state: " + last);
  }

  @Test
  public void stoppingEventSettlesReadyServiceWithoutCrashOrRelaunch() throws Exception {
    String repoId = repoWithWorkspace();
    String serviceId = createService(repoId, "dev", "sleep 300", RestartPolicy.ON_FAILURE);
    supervisor.start(workspaceIds.of(repoId, "work"), serviceId);
    driver.sink().onState(repoId, "work", workspaceIds.of(repoId, "work"), "dev", "READY", null);
    awaitStatus(repoId, serviceId, ServiceStatus.READY);

    // A deliberate container stop: settle, don't crash.
    containerEvents.fireStopping(repoId, "work", workspaceIds.of(repoId, "work"), true);

    ServiceInstanceDto settled = awaitStatus(repoId, serviceId, ServiceStatus.STOPPED);
    assertEquals(0, settled.restartCount(), "a settled service is not restarted");
    assertTrue(
        driver.signalled().contains("dev"), "a graceful settle asks the daemon to signal a flush");

    // It stays STOPPED — no crash path, no resurrection.
    Thread.sleep(300);
    assertEquals(
        ServiceStatus.STOPPED,
        instanceOf(repoId, serviceId).status(),
        "the settled service is not resurrected");
  }

  @Test
  public void stoppingEventSettlesARestartingInstance() throws Exception {
    String repoId = repoWithWorkspace();
    String serviceId = createService(repoId, "flaky", "sh -c 'exit 1'", RestartPolicy.ON_FAILURE);
    supervisor.start(workspaceIds.of(repoId, "work"), serviceId);
    // Play the daemon dropping it into RESTARTING (the daemon owns the backoff).
    driver.sink().onState(repoId, "work", workspaceIds.of(repoId, "work"), "flaky", "CRASHED", 1);
    driver.sink().onState(repoId, "work", workspaceIds.of(repoId, "work"), "flaky", "RESTARTING", 1);
    awaitStatus(repoId, serviceId, ServiceStatus.RESTARTING);

    containerEvents.fireStopping(repoId, "work", workspaceIds.of(repoId, "work"), true);

    awaitStatus(repoId, serviceId, ServiceStatus.STOPPED);
    Thread.sleep(300);
    assertEquals(
        ServiceStatus.STOPPED,
        instanceOf(repoId, serviceId).status(),
        "settling a RESTARTING instance leaves it STOPPED");
  }

  @Test
  public void stopContainerDoesNotResurrectItsSettledService() throws Exception {
    String repoId = repoWithWorkspace();
    String serviceId = createService(repoId, "dev", "sleep 300", RestartPolicy.ON_FAILURE);
    // A real running container to stop — the projection start no longer provisions one (the daemon
    // owns execution), so this test that exercises WorkspaceService.stopContainer provisions it.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "work"));
    supervisor.start(workspaceIds.of(repoId, "work"), serviceId);
    driver.sink().onState(repoId, "work", workspaceIds.of(repoId, "work"), "dev", "READY", null);
    awaitStatus(repoId, serviceId, ServiceStatus.READY);
    String container = containers.containerName("work", repoId);

    // A deliberate stop must settle the service synchronously (before the container is paused/
    // removed) so nothing reads the disappearance as a crash to resurrect.
    workspaceService.stopContainer(workspaceIds.of(repoId, "work"));

    Thread.sleep(300);
    // A graceful stop PAUSES in place (docker stop, lossless) rather than removing the container.
    assertTrue(containers.exists(container), "the paused container is kept, not removed");
    assertFalse(
        containers.isRunning(container), "the deliberately stopped container is not resurrected");
    WorkspaceDto dto =
        workspaceService.listWorkspaces(repoId).stream()
            .filter(w -> "work".equals(w.workspaceId()))
            .findFirst()
            .orElseThrow();
    assertEquals(
        WorkspaceRuntimeStatus.STOPPED, dto.runtimeStatus(), "the workspace stays STOPPED");
    assertEquals(
        ServiceStatus.STOPPED,
        instanceOf(repoId, serviceId).status(),
        "and its service stays STOPPED");
  }
}
