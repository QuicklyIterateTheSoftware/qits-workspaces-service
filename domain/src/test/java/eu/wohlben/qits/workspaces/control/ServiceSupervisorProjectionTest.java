package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.dto.ServiceInstanceDto;
import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The host {@code ServiceSupervisor} is a pure <b>projection</b> of the in-container daemon
 * (docs/epics/qits-workspace-daemon/ Part 4, and the collapse in docs/issues/resolved/
 * 2026-07-25_host-side-service-supervision-should-move-to-daemon.md): it issues only manual
 * start/stop over the {@link WorkspaceServiceDriver} and otherwise mirrors the lifecycle events the
 * daemon streams onto its display state machine, running no process itself. A {@link
 * FakeWorkspaceServiceDriver} plays the daemon — it records the host's start/stop calls and exposes
 * the sink the host subscribed, so a test feeds transitions and asserts the projection (status,
 * proxy target, singleton rule). Definitions are config-declared, staged into the {@link
 * FakeWorkspaceConfigReader} keyed by their {@code id:}.
 */
@QuarkusTest
public class ServiceSupervisorProjectionTest {

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;
  @Inject WorkspaceService workspaceService;
  @Inject FakeWorkspaceConfigReader configReader;
  @Inject ServiceSupervisor supervisor;
  @Inject FakeWorkspaceServiceDriver driver;
  @Inject WorkspaceReadyForServicesRecorder readyRecorder;

  @BeforeEach
  void resetFakes() {
    readyRecorder.clear();
    // Both fakes are shared singletons across this class's test methods.
    driver.reset();
    configReader.clear();
  }

  /** Clone the fixture, add a {@code work} workspace, and provision its (fake) container. */
  private String repoWithWorkspace() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createWorkspace(repoId, "work", "master", "work", null, false, false, true);
    // The proxy origin resolves against a real (fake) container; provision it up front.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "work"));
    // Provisioning fires the ready-for-services event asynchronously, and the lifecycle coupler
    // auto-starts whatever the config declares when it lands. Every test here stages its config
    // *after* this fixture and then starts a service by hand, so the two must not overlap: wait for
    // that pass to finish against the (still empty) config, or it reads the staged one instead and
    // the manual start finds its service already running.
    long deadline = System.currentTimeMillis() + 5_000;
    while (readyRecorder.countFor(repoId, "work") == 0) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("Timed out waiting for the workspace's service phase to settle");
      }
      Thread.sleep(20);
    }
    return repoId;
  }

  private String createService(String repoId, String name, String script) {
    return createService(repoId, name, script, null);
  }

  private String createService(
      String repoId, String name, String script, QitsConfig.WebViewDecl webView) {
    configReader.setConfig(
        workspaceIds.of(repoId, "work"),
        new QitsConfig(
            null,
            null,
            null,
            List.of(
                new QitsConfig.ServiceDecl(
                    name,
                    name,
                    null,
                    script,
                    null,
                    null,
                    RestartPolicy.ON_FAILURE,
                    3,
                    "TERM",
                    null,
                    webView,
                    null)),
            null));
    return name;
  }

  private ServiceInstanceDto instanceOf(String repoId, String serviceId) {
    return supervisor.effectiveServices(workspaceIds.of(repoId, "work")).stream()
        .filter(d -> d.definition().id().equals(serviceId))
        .findFirst()
        .orElseThrow();
  }

  private ServiceStatus statusOf(String repoId, String serviceId) {
    return instanceOf(repoId, serviceId).status();
  }

  @Test
  void manualStartAsksDaemonThenProjectsLifecycle() throws Exception {
    String repoId = repoWithWorkspace();
    String id = createService(repoId, "dev", "sleep 30");

    supervisor.start(workspaceIds.of(repoId, "work"), id); // manual, daemon-backed
    assertTrue(driver.started().contains("dev"), "a manual start asks the daemon to start it");
    assertTrue(driver.signalled().isEmpty(), "start does not signal");

    WorkspaceServiceDriver.ServiceEventSink sink = driver.sink();
    assertNotNull(sink, "the supervisor subscribed a projection sink at startup");

    // Play the daemon: it owns the lifecycle and streams transitions; the host projects them.
    sink.onState(repoId, "work", workspaceIds.of(repoId, "work"), "dev", "STARTING", null);
    sink.onState(repoId, "work", workspaceIds.of(repoId, "work"), "dev", "READY", null);
    assertEquals(ServiceStatus.READY, statusOf(repoId, id));

    sink.onState(repoId, "work", workspaceIds.of(repoId, "work"), "dev", "CRASHED", 1);
    assertEquals(ServiceStatus.CRASHED, statusOf(repoId, id));
  }

  @Test
  void restartingEventBumpsTheProjectedRestartCount() throws Exception {
    String repoId = repoWithWorkspace();
    String id = createService(repoId, "flaky", "false");
    supervisor.start(workspaceIds.of(repoId, "work"), id);

    var sink = driver.sink();
    sink.onState(repoId, "work", workspaceIds.of(repoId, "work"), "flaky", "CRASHED", 1);
    sink.onState(repoId, "work", workspaceIds.of(repoId, "work"), "flaky", "RESTARTING", 1);
    assertEquals(ServiceStatus.RESTARTING, statusOf(repoId, id));
    assertEquals(1, instanceOf(repoId, id).restartCount(), "a RESTARTING event is one restart");
  }

  @Test
  void stopAsksDaemonToSignalThenSettlesOnTheStoppedEvent() throws Exception {
    String repoId = repoWithWorkspace();
    String id = createService(repoId, "dev", "sleep 30");
    supervisor.start(workspaceIds.of(repoId, "work"), id);
    driver.sink().onState(repoId, "work", workspaceIds.of(repoId, "work"), "dev", "READY", null);

    supervisor.stop(workspaceIds.of(repoId, "work"), id);
    assertTrue(driver.signalled().contains("dev"), "stop asks the daemon to signal the service");
    // The daemon owns the process — the host stays READY until the daemon reports it gone.
    assertEquals(ServiceStatus.READY, statusOf(repoId, id));

    driver.sink().onState(repoId, "work", workspaceIds.of(repoId, "work"), "dev", "STOPPED", 0);
    assertEquals(ServiceStatus.STOPPED, statusOf(repoId, id));
  }

  @Test
  void adoptsRunningServiceFromEventWithoutAStart() throws Exception {
    // No start() — the daemon re-reports a running service on reconnect (post qits-restart); the
    // host adopts it from the event, event-driven, with no /proc or tmux probe.
    String repoId = repoWithWorkspace();
    String id = createService(repoId, "dev", "sleep 30");

    driver.sink().onState(repoId, "work", workspaceIds.of(repoId, "work"), "dev", "READY", null);
    assertEquals(ServiceStatus.READY, statusOf(repoId, id));
    assertTrue(driver.started().isEmpty(), "adoption issues no start instruction");
  }

  @Test
  void oneRunningInstancePerWorkspaceAndService() throws Exception {
    String repoId = repoWithWorkspace();
    String id = createService(repoId, "single", "sleep 30");

    supervisor.start(workspaceIds.of(repoId, "work"), id); // now STARTING (live) — a second start is rejected
    assertThrows(
        BadRequestException.class,
        () -> supervisor.start(workspaceIds.of(repoId, "work"), id),
        "second start of the same (workspace, service) must be rejected");
  }

  @Test
  void webViewableServiceExposesProxyTargetAndPath() throws Exception {
    String repoId = repoWithWorkspace();
    String id =
        createService(repoId, "web", "sleep 30", new QitsConfig.WebViewDecl(8123, "greeting", "app"));

    supervisor.start(workspaceIds.of(repoId, "work"), id);
    driver.sink().onState(repoId, "work", workspaceIds.of(repoId, "work"), "web", "READY", null);

    ServiceInstanceDto ready = instanceOf(repoId, id);
    assertEquals(ServiceStatus.READY, ready.status());
    assertEquals(
        "/workspaces/service/" + workspaceIds.of(repoId, "work") + "/" + id + "/app/",
        ready.proxyPath(),
        "the served base is the proxy prefix plus the basePath (entryPath is not part of it)");
    assertEquals("greeting", ready.definition().webView().entryPath());

    var target = supervisor.proxyTarget(workspaceIds.of(repoId, "work"), id);
    assertTrue(target.isPresent(), "a live web-viewable service has a proxy target");
    assertEquals(ServiceStatus.READY, target.get().status());
    // FakeContainerRuntime resolves the target to 127.0.0.1 + the container port; the real runtime
    // returns the container's DNS name on the shared network.
    assertEquals(new ProxyOrigin("127.0.0.1", 8123), target.get().origin());

    assertTrue(
        supervisor.proxyTarget(workspaceIds.of(repoId, "work"), "no-such-service").isEmpty(),
        "unknown service id resolves to nothing");

    supervisor.stop(workspaceIds.of(repoId, "work"), id);
    driver.sink().onState(repoId, "work", workspaceIds.of(repoId, "work"), "web", "STOPPED", 0);
    var stopped = supervisor.proxyTarget(workspaceIds.of(repoId, "work"), id);
    assertTrue(stopped.isPresent(), "a stopped instance still resolves (the proxy 502s on it)");
    assertEquals(ServiceStatus.STOPPED, stopped.get().status());
  }

  @Test
  void serviceWithoutWebViewHasNoProxyTargetOrPath() throws Exception {
    String repoId = repoWithWorkspace();
    String id = createService(repoId, "plain", "sleep 30");

    supervisor.start(workspaceIds.of(repoId, "work"), id);
    driver.sink().onState(repoId, "work", workspaceIds.of(repoId, "work"), "plain", "READY", null);

    assertEquals(null, instanceOf(repoId, id).proxyPath(), "no web-view config, no proxy path");
    assertTrue(supervisor.proxyTarget(workspaceIds.of(repoId, "work"), id).isEmpty());
  }
}
