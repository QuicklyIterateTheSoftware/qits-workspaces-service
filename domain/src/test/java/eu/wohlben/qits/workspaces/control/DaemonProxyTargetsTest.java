package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link DaemonProxyTargets}: why a daemon with no tunnel cannot be reached. It never answers an
 * origin — the direct {@code container:13338} fallback is gone (qits-780) — so a RUNNER row is
 * {@code NOT_CONNECTED} without asking the {@link ContainerRuntime} anything (qits-812), a DIRECT
 * admin row asks only whether its container runs, and a regular row reaching the DIRECT branch is
 * refused by the router.
 */
class DaemonProxyTargetsTest {

  private final List<String> runtimeCalls = new ArrayList<>();

  private boolean running = true;

  private DaemonProxyTargets targets(Workspace row) {
    DaemonProxyTargets targets = new DaemonProxyTargets();
    targets.workspaces =
        new WorkspaceRepository() {
          @Override
          public Optional<Workspace> findActiveById(Long id) {
            return Optional.ofNullable(row).filter(w -> w.id.equals(id));
          }
        };
    targets.containers =
        (ContainerRuntime)
            Proxy.newProxyInstance(
                ContainerRuntime.class.getClassLoader(),
                new Class<?>[] {ContainerRuntime.class},
                (proxy, method, args) -> {
                  runtimeCalls.add(method.getName());
                  return switch (method.getName()) {
                    case "containerName" -> "qits-ws-" + args[0];
                    case "isRunning" -> running;
                    default -> null;
                  };
                });
    return targets;
  }

  private static Workspace row(WorkspacePlacement placement, boolean admin) {
    Workspace row = new Workspace();
    row.id = 7L;
    row.workspaceId = "work";
    row.repositoryId = "repo";
    row.status = WorkspaceStatus.ACTIVE;
    row.placement = placement;
    row.admin = admin;
    return row;
  }

  @Test
  void anUnknownRowIsNoWorkspace() {
    assertEquals(
        DaemonProxyTargets.Reachability.NO_WORKSPACE,
        targets(row(WorkspacePlacement.DIRECT, true)).resolve(8L));
    assertTrue(runtimeCalls.isEmpty(), runtimeCalls.toString());
  }

  @Test
  void aRunnerRowIsNotConnectedAndTheRuntimeIsNeverAsked() {
    assertEquals(
        DaemonProxyTargets.Reachability.NOT_CONNECTED,
        targets(row(WorkspacePlacement.RUNNER, false)).resolve(7L));
    assertTrue(runtimeCalls.isEmpty(), runtimeCalls.toString());
  }

  @Test
  void anAdminRowWithNoTunnelIsNotConnectedAndNeverDialled() {
    assertEquals(
        DaemonProxyTargets.Reachability.NOT_CONNECTED,
        targets(row(WorkspacePlacement.DIRECT, true)).resolve(7L));
    assertEquals(
        List.of("containerName", "isRunning"),
        runtimeCalls,
        "the runtime is asked whether the container runs, never where to dial it");
  }

  @Test
  void anAdminRowWhoseContainerIsDownSaysSo() {
    running = false;
    assertEquals(
        DaemonProxyTargets.Reachability.NO_CONTAINER,
        targets(row(WorkspacePlacement.DIRECT, true)).resolve(7L));
  }

  @Test
  void aRegularRowReachingTheDirectBranchIsRefused() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> targets(row(WorkspacePlacement.DIRECT, false)).resolve(7L));
    assertEquals("direct placement refused for regular workspace 7", refused.getMessage());
    assertTrue(runtimeCalls.isEmpty(), "qits-containers is never asked: " + runtimeCalls);
  }
}
