package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Resolves a workspace id to the active {@link Workspace}, 404ing when there is none. Shared by
 * every per-workspace entry point so the 404 messages stay in lockstep instead of being copy-pasted
 * per service.
 *
 * <p>The caller no longer supplies a repository: the id is the identity, so there is nothing to
 * disambiguate it with. The owning repository is read off the resolved row — callers that need it
 * (container names, on-disk paths) take {@link Workspace#repositoryId} rather than being handed it
 * and trusted. It is still <em>checked</em>: the repository lookup stays, because it was never only
 * a disambiguator. A workspace whose repository the owning application no longer knows is dangling
 * history, and answering 404 for it is the same fail-closed posture as before.
 *
 * <p><b>The editor is the one row that check must not be applied to.</b> It belongs to no repository
 * — it carries a sentinel id, because the column is not nullable — so the lookup would answer "no
 * such repository" for it every single time, and this resolver is what {@code
 * /workspaces/{id}/stop-container}, {@code /recreate-container} and the technical-process lookups
 * all open with. The editor door hands its row id out for exactly those verbs, so 404ing it here
 * would make the answer a lie. The exemption is narrow and reads as what it is: the check asks
 * whether a row's repository still exists, and a row that names none has nothing to be dangling
 * history of.
 */
@ApplicationScoped
public class WorkspaceResolver {

  @Inject RepositoryLookup repositories;

  @Inject WorkspaceRepository workspaceRepository;

  /** The active workspace with this id, or 404. */
  public Workspace resolveActive(Long id) {
    if (id == null) {
      throw new NotFoundException("Workspace not found: null");
    }
    Workspace workspace =
        workspaceRepository
            .findActiveById(id)
            .orElseThrow(() -> new NotFoundException("Workspace not found: " + id));
    if (!workspace.editor) {
      repositories.require(workspace.repositoryId);
    }
    return workspace;
  }
}
