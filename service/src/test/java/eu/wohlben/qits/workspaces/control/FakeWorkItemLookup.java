package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The work-item lookup a project agent's wait makes (qits-2596), for this module's suite: an item a
 * test {@link #add}s is found by its uuid and its qualified id, every other ref is a 404 — so no test
 * dials qits-projects for it. Wins over {@code wiring/HttpWorkItemLookup}, a {@code @DefaultBean};
 * that adapter's call is the projects contract table's.
 */
@ApplicationScoped
public class FakeWorkItemLookup implements WorkItemLookup {

  private final Map<String, WorkItem> items = new ConcurrentHashMap<>();

  @Override
  public Optional<WorkItem> find(String ref) {
    return Optional.ofNullable(items.get(ref));
  }

  /** {@code item} is known by its id and its qualified id until {@link #forget}. */
  public void add(WorkItem item) {
    items.put(item.id(), item);
    items.put(item.qualifiedId(), item);
  }

  public void forget(WorkItem item) {
    items.remove(item.id());
    items.remove(item.qualifiedId());
  }
}
