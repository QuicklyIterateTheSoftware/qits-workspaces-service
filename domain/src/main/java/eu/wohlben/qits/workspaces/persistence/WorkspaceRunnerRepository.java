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

  /**
   * Whether any runner could take a RUNNER workspace at all: registered, with slots, not
   * quarantined ({@code WorkspaceRunner.eligible()}, as a query). Whether one is connected is not
   * asked: a queued row waits for a runner that comes back. It decides nothing about placement (a
   * regular workspace is RUNNER regardless, qits-774); it only names why a queued row on no runner
   * waits ("no enabled workspace runner").
   */
  public boolean existsEligible() {
    return count("clientId is not null and slots > 0 and quarantinedAt is null") > 0;
  }

  /**
   * The slots of every runner that may take a workspace — registered, in service, with slots
   * (qits-1152): how many workspaces may run on runners at once.
   */
  public long eligibleSlots() {
    Number sum =
        getEntityManager()
            .createQuery(
                "select coalesce(sum(r.slots), 0) from WorkspaceRunner r"
                    + " where r.clientId is not null and r.slots > 0 and r.quarantinedAt is null",
                Number.class)
            .getSingleResult();
    return sum == null ? 0 : sum.longValue();
  }
}
