package eu.wohlben.qits.workspaces.persistence;

import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class WorkspaceRunnerRepository implements PanacheRepositoryBase<WorkspaceRunner, UUID> {

  public Optional<WorkspaceRunner> findByName(String name) {
    return find("name", name).firstResultOptional();
  }

  /**
   * The registered runner a commissioned client belongs to. An unregistered row has no client and
   * never matches.
   */
  public Optional<WorkspaceRunner> findByClientId(String clientId) {
    return find("clientId", clientId).firstResultOptional();
  }

  public List<WorkspaceRunner> listByName() {
    return listAll(Sort.by("name"));
  }

  /** The names of these runners, by id, in one read. A runner that is gone is simply absent. */
  public Map<UUID, String> namesById(Collection<UUID> ids) {
    Map<UUID, String> names = new HashMap<>();
    if (ids == null || ids.isEmpty()) {
      return names;
    }
    for (WorkspaceRunner runner : list("id in ?1", ids)) {
      names.put(runner.id, runner.name);
    }
    return names;
  }
}
