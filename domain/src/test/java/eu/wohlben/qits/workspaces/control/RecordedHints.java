package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test bean observing {@link WorkspaceChangeHint}s off the same CDI async bus the SSE broadcaster in
 * {@code service} uses (the domain twin of {@code service}'s {@code HintCollector}; the two modules
 * share no test classpath). Recording only, so being a bean for every {@code @QuarkusTest} in the
 * module changes no other test. Delivery is async: read with {@link #awaitFor}.
 */
@ApplicationScoped
public class RecordedHints {

  private final List<WorkspaceChangeHint> hints = new CopyOnWriteArrayList<>();

  void onHint(@ObservesAsync WorkspaceChangeHint hint) {
    hints.add(hint);
  }

  /** The hints fired so far for this repository. */
  public List<WorkspaceChangeHint> forRepository(String repoId) {
    return hints.stream().filter(h -> repoId.equals(h.repoId())).toList();
  }

  /**
   * How many {@code topic} hints this workspace has had, once at least {@code atLeast} arrived or
   * {@code timeoutMs} passed.
   */
  public long awaitFor(Long workspaceRowId, WorkspaceChangeHint.Topic topic, long atLeast, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    long count;
    while ((count = count(workspaceRowId, topic)) < atLeast && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    return count;
  }

  private long count(Long workspaceRowId, WorkspaceChangeHint.Topic topic) {
    return hints.stream()
        .filter(h -> workspaceRowId.equals(h.workspaceRowId()) && h.topic() == topic)
        .count();
  }
}
