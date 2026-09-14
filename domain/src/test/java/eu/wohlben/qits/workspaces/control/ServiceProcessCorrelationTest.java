package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.TechnicalProcessFrame;
import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Cross-thread correlation of the technical process with the service phase: the id rides {@code
 * WorkspaceContainerStarted} onto the async observer thread, so an auto-started service's streamed
 * startup lines land in the {@code service:<name>} segment of the <em>same</em> process that
 * streamed the provision, a READY event settles that segment, and the process reaches {@code done}
 * only once every auto-start service settled. Under the pure-projection host the daemon owns the
 * lifecycle; a {@link FakeWorkspaceServiceDriver} plays its streamed line + READY. The auto-start
 * definition is config-declared, staged into the {@link FakeWorkspaceConfigReader}.
 */
// NO @TestProfile: its two overrides were a fresh temp origins-dir (TestOrigin already keys every
// origin by a UUID, so the shipped one is enough) and qits.services.autostart-enabled=true, which
// is ServiceLifecycleCoupler's own defaultValue. Neither was a difference, and the profile cost a
// Quarkus restart — see AutoStartOffProfile for why that is measured in metaspace. Bug e6f0bdfa.
@QuarkusTest
public class ServiceProcessCorrelationTest {

  private static final long AWAIT_MILLIS = 15_000;

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;
  @Inject WorkspaceService workspaceService;
  @Inject FakeWorkspaceConfigReader configReader;
  @Inject FakeWorkspaceServiceDriver driver;
  @Inject ServiceSupervisor supervisor;
  @Inject TechnicalProcessRegistry registry;

  private static final class Replay implements TechnicalProcess.Listener {
    final List<TechnicalProcessFrame> frames = new ArrayList<>();

    @Override
    public void onFrame(TechnicalProcessFrame frame) {
      frames.add(frame);
    }

    @Override
    public void onDone() {}

    @Override
    public boolean isOpen() {
      return true;
    }
  }

  @Test
  public void streamedServiceLinesLandInTheStartProcessAndReadySettlesTheSegment()
      throws Exception {
    driver.reset();
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    workspaceService.createWorkspace(repoId, "work", "master", "work");
    configReader.setConfig(
        workspaceIds.of(repoId, "work"),
        new QitsConfig(
            null,
            null,
            null,
            List.of(
                new QitsConfig.ServiceDecl(
                    "web",
                    "web",
                    null,
                    "echo hello-from-service; sleep 300",
                    null,
                    true,
                    RestartPolicy.NEVER,
                    0,
                    "TERM",
                    null,
                    null,
                    null)),
            null));

    String processId = workspaceService.beginEnsureContainer(workspaceIds.of(repoId, "work"));
    TechnicalProcess process = registry.find(processId).orElseThrow();

    // Wait until the auto-start coupler pre-registered the process-tracked "web" projection
    // (STARTING) — only then does a streamed line/READY resolve to the process-linked instance.
    awaitStatus(repoId, "web", ServiceStatus.STARTING);

    // Play the daemon: it streams the service's startup output, then reports READY.
    driver.sink().onLine(repoId, "work", workspaceIds.of(repoId, "work"), "web", "STDOUT", "hello-from-service");
    driver.sink().onState(repoId, "work", workspaceIds.of(repoId, "work"), "web", "READY", null);

    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (!process.isTerminal() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(process.isTerminal(), "done must fire once provision + the service settled");

    Replay replay = new Replay();
    process.attach(replay);
    String segment = TechnicalProcess.serviceSegment("web");
    assertTrue(
        replay.frames.stream()
            .anyMatch(
                f ->
                    "line".equals(f.kind())
                        && segment.equals(f.segment())
                        && f.line().contains("hello-from-service")),
        "the service's streamed output lands in its segment of the same process");
    TechnicalProcessFrame settle =
        replay.frames.stream()
            .filter(f -> "segment-settled".equals(f.kind()) && segment.equals(f.segment()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("service segment never settled"));
    assertEquals("ok", settle.status(), "STARTING→READY settles the segment ok");
    assertEquals(
        "ok",
        replay.frames.stream()
            .filter(f -> "done".equals(f.kind()))
            .findFirst()
            .orElseThrow()
            .status());
  }

  private void awaitStatus(String repoId, String serviceId, ServiceStatus expected)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    ServiceStatus last = null;
    while (System.currentTimeMillis() < deadline) {
      var i =
          supervisor.effectiveServices(workspaceIds.of(repoId, "work")).stream()
              .filter(d -> d.definition().id().equals(serviceId))
              .findFirst()
              .orElse(null);
      last = i != null ? i.status() : null;
      if (last == expected) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for " + expected + "; last: " + last);
  }
}
