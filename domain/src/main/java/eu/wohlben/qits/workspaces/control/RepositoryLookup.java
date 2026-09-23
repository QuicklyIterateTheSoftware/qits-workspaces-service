package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.NotFoundException;
import java.util.List;
import java.util.Optional;

/**
 * The owning application's repository registry, narrowed to what the workspaces context actually
 * needs of it: does this repository exist, which project owns it, what is it called, and what is
 * its main branch.
 *
 * <p>A workspace has no meaning without a repository to branch from, but this context deliberately
 * holds no foreign key into the repositories tables (see {@link
 * eu.wohlben.qits.workspaces.entity.Workspace#repositoryId}). This port is the seam that replaces
 * it: the two facts are pulled through an interface instead of a join, so the workspaces schema
 * stays independently migratable and this jar carries no repositories code.
 *
 * <p>Unlike the workspace-daemon SPIs, which are injected as {@code Instance<T>} because a daemon
 * genuinely may not be connected, this one is a <strong>mandatory</strong> {@code @Inject}: an
 * application that pulls this jar in without implementing it is misconfigured, and should fail at
 * startup rather than 404 every workspace at runtime.
 */
public interface RepositoryLookup {

  /**
   * The repository facts this context reads.
   *
   * <p>{@code projectId} and {@code name} arrived for the {@code SCMRelease} this service used to
   * publish — the event named the project a release belonged to and the repository a CI selection
   * could address — and the publisher is qits-projects' now. They stay because the <b>workspace
   * daemon</b> receives both, so its name-addressed clone lets committed relative submodule URLs
   * resolve to sibling repositories. {@code name} is the registered name — the coordinate that is
   * the same on every platform instance, while {@code id} is whatever that instance's registry
   * minted (a manifest repository's id equals its name, a self-seeded one's is a UUID). Both are
   * nullable: a registry that does not answer with one costs a label, never a workspace.
   *
   * <p><b>There was a fifth field, {@code archetype}, and it is gone.</b> It was bound for exactly
   * one question — is this repository a project's wrapper ({@code PROJECT}) — which, paired with
   * {@code mainBranch}, was the whole of deciding that a workspace ran the richer editor image. The
   * editor is one container for the platform now and says so with a column of its own
   * ({@code Workspace.editor}), so nothing in this context asks that question any more. The field
   * went with its only reader rather than staying as a binding nobody reads; qits-projects still
   * answers it, and {@code ignoreUnknown} on the wire record is what makes ignoring it free.
   */
  record RepositoryView(String id, String name, String projectId, String mainBranch) {}

  /** The repository behind {@code repoId}, or empty when it does not exist. */
  Optional<RepositoryView> find(String repoId);

  /**
   * Every repository registered in a project — what resolves a wrapper's committed submodule urls
   * to repositories this service may branch.
   *
   * <p>A {@code default} only because {@link #find} is the single abstract method a stub is written
   * as a lambda against. Answering empty is not a supported implementation: a project always holds
   * at least the repository being asked about, so {@code WorkspaceService} reads an empty list as a
   * registry that did not answer and refuses — branching a wrapper alone and leaving every
   * submodule behind reads as success and is not.
   */
  default List<RepositoryView> listByProject(String projectId) {
    return List.of();
  }

  /** {@link #find} or 404 — the guard nearly every workspace entry point opens with. */
  default RepositoryView require(String repoId) {
    return find(repoId).orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
  }
}
