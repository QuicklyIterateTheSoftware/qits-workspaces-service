package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jboss.logging.Logger;

/**
 * The one {@link EditorProjects}: every project's wrapper, derived from what this platform is
 * worked in.
 *
 * <h2>Where the list comes from, and what that costs</h2>
 *
 * <p><b>There is no "every project" door to ask.</b> This context holds no project table — it keeps
 * a repository id as a string and nothing else, deliberately (see {@link RepositoryLookup}) — and
 * qits-projects exposes a repository and a project's repository listing here, not a listing of
 * projects. So the set is derived from queries that already exist: {@link
 * WorkspaceRepository#activeRepositoryIds} names every repository somebody is working in, {@link
 * RepositoryLookup#find} turns each into the project that owns it, and {@link
 * RepositoryLookup#listByProject} then names that project's wrapper.
 *
 * <p>That is a real narrowing and it is stated rather than hidden: <b>a project nobody has ever
 * opened a workspace in is not in the list.</b> Adding a cross-context door is a bigger commitment
 * than this feature has earned, and the narrowing self-corrects in the direction that matters —
 * the first workspace in a project puts that project in. When a projects listing does exist, this
 * class is the one place to repoint.
 *
 * <p><b>A project added later is picked up on the next container recreate</b>, and there is
 * deliberately no polling, no watch and no scheduler: the list rides the container spec, so the
 * question is only ever asked while a spec is being built.
 *
 * <h2>The archetype is the test, and that is why the field came back</h2>
 *
 * <p>Which repository of a project <em>is</em> the wrapper is a fact only qits-projects holds, and
 * {@code archetype == PROJECT} is how it says so. The per-project editor used to avoid asking by
 * DERIVING the name a wrapper carries ({@code <slug>-<slug>}) and matching it — a convention this
 * context is not entitled to re-derive, and one that went with that editor. So {@link
 * RepositoryLookup.RepositoryView#isWrapper} is bound again, for this reader and no other.
 *
 * <h2>Sorted, because environment is part of the spec</h2>
 *
 * <p>{@link EditorProjects#wrappers} states the sort as a contract; {@code TreeSet} is what makes it
 * true here regardless of the order the registry happens to answer in, and the query behind it
 * orders too so the intermediate work is stable as well.
 *
 * <h2>What a registry that cannot answer does</h2>
 *
 * <p>Nothing fails. A lookup that throws or answers nothing costs that <em>project</em> its entry
 * and the rest of the list is composed — the standing reading everywhere this context touches the
 * registry, that an unreachable registry costs a label and never a workspace.
 *
 * <p><b>The trade-off that buys is churn, and it is the honest one.</b> An entry dropping out during
 * an outage and returning afterwards is a changed spec twice, so an outage mid-poll can recreate the
 * editor's container. The alternative — failing the ensure so the spec is never wrong — makes a
 * registry blink take the editor away from everybody on the platform, which is worse than a
 * container that came back and re-clones. A missing clone is visible inside the editor and
 * recoverable by pressing the door again; a door that refuses is not.
 */
@ApplicationScoped
public class PersistedEditorProjects implements EditorProjects {

  private static final Logger LOG = Logger.getLogger(PersistedEditorProjects.class);

  @Inject WorkspaceRepository workspaces;

  @Inject RepositoryLookup repositories;

  @Override
  @Transactional
  public List<String> wrappers() {
    Set<String> entries = new TreeSet<>();
    for (String projectId : projectIds()) {
      wrapperOf(projectId).ifPresent(entries::add);
    }
    return List.copyOf(entries);
  }

  /**
   * The projects worth asking about: the owner of every repository an active workspace stands on. A {@link LinkedHashSet} over an already-ordered query, so the de-duplication is free and the
   * intermediate order is stable — the final sort is on the composed entries anyway, because two
   * projects' wrappers do not sort the way their ids do.
   */
  private Set<String> projectIds() {
    Set<String> projects = new LinkedHashSet<>();
    for (String repoId : workspaces.activeRepositoryIds()) {
      try {
        repositories
            .find(repoId)
            .map(RepositoryLookup.RepositoryView::projectId)
            .filter(id -> id != null && !id.isBlank())
            .ifPresent(projects::add);
      } catch (RuntimeException registryFailure) {
        LOG.warnf(
            registryFailure,
            "Could not resolve the project of repository %s while composing the editor's project"
                + " list; its project is left out of this spec.",
            repoId);
      }
    }
    return projects;
  }

  /**
   * A project's wrapper as {@code <projectId>/<repoName>}, or empty when the registry cannot be
   * asked or names no wrapper. {@code sorted().findFirst()} rather than a bare {@code findFirst()}
   * so that a project answering with two wrappers — which should not happen and is not this
   * context's to refuse — still composes the same entry at every ensure.
   */
  private Optional<String> wrapperOf(String projectId) {
    List<RepositoryLookup.RepositoryView> registered;
    try {
      registered = repositories.listByProject(projectId);
    } catch (RuntimeException registryFailure) {
      LOG.warnf(
          registryFailure,
          "Could not list the repositories of project %s while composing the editor's project list;"
              + " its wrapper is left out of this spec.",
          projectId);
      return Optional.empty();
    }
    if (registered == null) {
      return Optional.empty();
    }
    return registered.stream()
        .filter(Objects::nonNull)
        .filter(RepositoryLookup.RepositoryView::isWrapper)
        .map(RepositoryLookup.RepositoryView::name)
        .filter(name -> name != null && !name.isBlank())
        .sorted()
        .findFirst()
        .map(name -> projectId + "/" + name);
  }
}
