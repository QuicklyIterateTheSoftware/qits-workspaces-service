package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.archrules.NecessaryTestProfileDuplication;
import eu.wohlben.qits.workspaces.dto.ServiceInstanceDto;
import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The {@code qits.services.autostop-enabled=false} kill switch suppresses the settle coupling: a
 * container-stopping event settles nothing, leaving the service to the daemon's own machinery.
 */
@QuarkusTest
@TestProfile(ServiceSettleKillSwitchTest.TestProfile.class)
public class ServiceSettleKillSwitchTest {

  /**
   * A {@link NecessaryTestProfileDuplication} against {@link AutoStartOffProfile}, which it
   * otherwise matches key for key.
   *
   * <p>The difference is {@code autostop-enabled=false}, and it is this class's whole subject: with
   * the settle coupling switched off, a stopping event must leave a READY service READY. That is the
   * exact negation of what {@code ServiceLifecycleCouplerSettleTest} — the other {@link
   * AutoStartOffProfile} consumer — exists to assert, namely that the same event settles the same
   * service STOPPED without a crash or a relaunch. One map cannot hold both readings, so folding the
   * two would not cost a restart, it would delete a test's premise: whichever value won, the other
   * class would be asserting against a coupling in the wrong state.
   *
   * <p>{@code autostart-enabled=false} rides along because the service here is started by hand, the
   * same isolation the settle class takes. It is not the reason for the duplication — the autostop
   * line is.
   */
  public static class TestProfile implements QuarkusTestProfile, NecessaryTestProfileDuplication {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.services.autostop-enabled", "false",
          "qits.services.autostart-enabled", "false");
    }
  }

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
  public void killSwitchSuppressesSettle() throws Exception {
    driver.reset();
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    workspaceService.createWorkspace(repoId, "work", "master", "work");
    String serviceId = "dev";
    configReader.setConfig(
        workspaceIds.of(repoId, "work"),
        new QitsConfig(
            null,
            null,
            null,
            List.of(
                new QitsConfig.ServiceDecl(
                    serviceId,
                    "dev",
                    null,
                    "sleep 300",
                    null,
                    false,
                    RestartPolicy.ON_FAILURE,
                    3,
                    "TERM",
                    null,
                    null,
                    null)),
            null));
    supervisor.start(workspaceIds.of(repoId, "work"), serviceId);
    driver.sink().onState(repoId, "work", workspaceIds.of(repoId, "work"), "dev", "READY", null);

    // The settle event fires, but the kill switch means the coupler ignores it: the service (still
    // owned by the live daemon) stays READY rather than being settled STOPPED.
    containerEvents.fireStopping(repoId, "work", workspaceIds.of(repoId, "work"), true);

    Thread.sleep(300);
    assertEquals(
        ServiceStatus.READY,
        instanceOf(repoId, serviceId).status(),
        "kill switch off ⇒ the stopping event settles nothing");
  }

  private ServiceInstanceDto instanceOf(String repoId, String serviceId) {
    return supervisor.effectiveServices(workspaceIds.of(repoId, "work")).stream()
        .filter(i -> i.definition().id().equals(serviceId))
        .findFirst()
        .orElseThrow();
  }
}
