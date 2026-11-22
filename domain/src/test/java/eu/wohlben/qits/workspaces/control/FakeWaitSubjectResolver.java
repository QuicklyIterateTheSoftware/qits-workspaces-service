package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The subject lookup (epic qits-1681), scripted per ref: a ref a test {@link #answer}s resolves to
 * that, one it {@link #fail}s throws {@link WaitSubjectResolver.Unresolvable} (one it says is {@link
 * #notFound}, the 404's {@link WaitSubjectResolver.NotFound}, qits-1961), and every other ref
 * resolves to nothing — so being a bean for every {@code @QuarkusTest} in the module changes no other
 * test's waits. A ref may carry a one-shot hook ({@link #onceOnResolve}, qits-2508) that its next
 * lookup runs on the looking-up thread, after it is counted. Refs are unique per test; {@link
 * #forget} after.
 */
@ApplicationScoped
public class FakeWaitSubjectResolver implements WaitSubjectResolver {

  private final Map<String, Resolved> answers = new ConcurrentHashMap<>();

  private final Map<String, String> failures = new ConcurrentHashMap<>();

  private final Map<String, String> missing = new ConcurrentHashMap<>();

  private final List<String> asked = new CopyOnWriteArrayList<>();

  private final Map<String, Runnable> hooks = new ConcurrentHashMap<>();

  @Override
  public Optional<Resolved> resolve(WaitSubjects.Kind kind, String ref, String projectId) {
    asked.add(kind + " " + ref + " " + projectId);
    Runnable hook = hooks.remove(ref);
    if (hook != null) {
      hook.run();
    }
    String gone = missing.get(ref);
    if (gone != null) {
      throw new NotFound(gone);
    }
    String failure = failures.get(ref);
    if (failure != null) {
      throw new Unresolvable(failure);
    }
    return Optional.ofNullable(answers.get(ref));
  }

  public void answer(String ref, Resolved resolved) {
    failures.remove(ref);
    missing.remove(ref);
    answers.put(ref, resolved);
  }

  public void fail(String ref, String message) {
    answers.remove(ref);
    missing.remove(ref);
    failures.put(ref, message);
  }

  /** The provider answers 404 for {@code ref} (qits-1961). */
  public void notFound(String ref, String message) {
    answers.remove(ref);
    failures.remove(ref);
    missing.put(ref, message);
  }

  /** How often {@code ref} was looked up, of any kind. */
  public long askedFor(String ref) {
    return asked.stream().filter(a -> a.split(" ")[1].equals(ref)).count();
  }

  /** How often a subject of {@code kind} was looked up, of any ref: assert it as a delta. */
  public long askedOfKind(WaitSubjects.Kind kind) {
    return asked.stream().filter(a -> a.split(" ")[0].equals(kind.name())).count();
  }

  /** The lookups of {@code ref}, each {@code "<kind> <ref> <projectId>"}. */
  public List<String> lookupsOf(String ref) {
    return asked.stream().filter(a -> a.split(" ")[1].equals(ref)).toList();
  }

  /**
   * Run {@code hook} inside the next lookup of {@code ref}, once, after it is counted and before it
   * answers: where a race with another caller's lookup is staged (qits-2508).
   */
  public void onceOnResolve(String ref, Runnable hook) {
    hooks.put(ref, hook);
  }

  public void forget(String ref) {
    hooks.remove(ref);
    answers.remove(ref);
    failures.remove(ref);
    missing.remove(ref);
  }
}
