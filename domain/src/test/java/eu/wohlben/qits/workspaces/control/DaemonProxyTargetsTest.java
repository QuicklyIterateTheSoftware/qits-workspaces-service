package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * {@link DaemonProxyTargets} (qits-812): a RUNNER row's daemon is on a runner's node, reachable
 * through its tunnel or not at all, so the resolution answers {@code NOT_CONNECTED} without asking
 * the {@link ContainerRuntime} anything — it never yields the {@code container:13338} origin. A
 * DIRECT row resolves through the runtime as it always did.
 */
class DaemonProxyTargetsTest {

  private final List<String> runtimeCalls = new ArrayList<>();

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
                    case "isRunning" -> true;
                    case "resolveTarget" -> new ProxyOrigin("qits-ws-" + row.workspaceId, 13338);
                    default -> null;
                  };
                });
    targets.daemonApiPort = 13338;
    return targets;
  }

  private static Workspace row(WorkspacePlacement placement) {
    Workspace row = new Workspace();
    row.id = 7L;
    row.workspaceId = "work";
    row.repositoryId = "repo";
    row.status = WorkspaceStatus.ACTIVE;
    row.placement = placement;
    return row;
  }

  @Test
  void aRunnerRowIsNotConnectedAndTheRuntimeIsNeverAsked() {
    DaemonProxyTargets.DaemonTarget target = targets(row(WorkspacePlacement.RUNNER)).resolve(7L);

    assertEquals(DaemonProxyTargets.Reachability.NOT_CONNECTED, target.reachability());
    assertNull(target.origin(), "never the container:13338 origin");
    assertTrue(runtimeCalls.isEmpty(), runtimeCalls.toString());
  }

  @Test
  void aDirectRowStillResolvesThroughTheRuntime() {
    DaemonProxyTargets.DaemonTarget target = targets(row(WorkspacePlacement.DIRECT)).resolve(7L);

    assertEquals(DaemonProxyTargets.Reachability.READY, target.reachability());
    assertEquals(new ProxyOrigin("qits-ws-work", 13338), target.origin());
    assertEquals(List.of("containerName", "isRunning", "resolveTarget"), runtimeCalls);
  }
}
