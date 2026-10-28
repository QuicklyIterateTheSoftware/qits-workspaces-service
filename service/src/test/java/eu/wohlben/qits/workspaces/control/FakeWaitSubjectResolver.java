package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * The subject lookup (epic qits-1681) for this module's suite: it resolves nothing, so a declared
 * wait keeps the subject its selection names and nothing else, and no test dials qits-projects or
 * qits-ci for it. Wins over {@code wiring/HttpWaitSubjectResolver}, a {@code @DefaultBean}; that
 * adapter's mapping is {@code HttpWaitSubjectResolverTest}'s, and its calls the contract tables'.
 */
@ApplicationScoped
public class FakeWaitSubjectResolver implements WaitSubjectResolver {

  @Override
  public Optional<Resolved> resolve(WaitSubjects.Kind kind, String ref, String projectId) {
    return Optional.empty();
  }
}
