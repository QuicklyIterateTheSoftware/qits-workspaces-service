package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test helper: records every {@link WorkspaceContainerStopping} the <em>synchronous</em> event bus
 * delivers, capturing whether the container still {@code exists()} at observation time — the proof
 * that firing happens <em>before</em> {@code containers.rm}.
 */
@ApplicationScoped
public class WorkspaceContainerStoppingRecorder {

  /** One observed stopping event plus the container-existence snapshot taken as it was observed. */
  public record Observation(
      WorkspaceContainerStopping event, boolean containerExistedWhenObserved) {}

  @Inject ContainerRuntime containers;

  private final List<Observation> observations = new CopyOnWriteArrayList<>();

  void onStopping(@Observes WorkspaceContainerStopping evt) {
    boolean existed = containers.exists(containers.containerName(evt.workspaceId(), evt.repoId()));
    observations.add(new Observation(evt, existed));
  }

  public void clear() {
    observations.clear();
  }

  public List<Observation> forKey(String repoId, String workspaceId) {
    return observations.stream()
        .filter(
            o -> o.event().repoId().equals(repoId) && o.event().workspaceId().equals(workspaceId))
        .toList();
  }
}
