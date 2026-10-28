package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The subject lookup (epic qits-1681), scripted per ref: a ref a test {@link #answer}s resolves to
 * that, one it {@link #fail}s throws {@link WaitSubjectResolver.Unresolvable}, and every other ref
 * resolves to nothing — so being a bean for every {@code @QuarkusTest} in the module changes no other
 * test's waits. Refs are unique per test; {@link #forget} after.
 */
@ApplicationScoped
public class FakeWaitSubjectResolver implements WaitSubjectResolver {

  private final Map<String, Resolved> answers = new ConcurrentHashMap<>();

  private final Map<String, String> failures = new ConcurrentHashMap<>();

  private final List<String> asked = new CopyOnWriteArrayList<>();

  @Override
  public Optional<Resolved> resolve(WaitSubjects.Kind kind, String ref, String projectId) {
    asked.add(kind + " " + ref + " " + projectId);
    String failure = failures.get(ref);
    if (failure != null) {
      throw new Unresolvable(failure);
    }
    return Optional.ofNullable(answers.get(ref));
  }

  public void answer(String ref, Resolved resolved) {
    failures.remove(ref);
    answers.put(ref, resolved);
  }

  public void fail(String ref, String message) {
    answers.remove(ref);
    failures.put(ref, message);
  }

  /** How often {@code ref} was looked up, of any kind. */
  public long askedFor(String ref) {
    return asked.stream().filter(a -> a.split(" ")[1].equals(ref)).count();
  }

  /** The lookups of {@code ref}, each {@code "<kind> <ref> <projectId>"}. */
  public List<String> lookupsOf(String ref) {
    return asked.stream().filter(a -> a.split(" ")[1].equals(ref)).toList();
  }

  public void forget(String ref) {
    answers.remove(ref);
    failures.remove(ref);
  }
}
