package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * The shipped {@link WorkspacePostures}: both answers are columns on the workspace row, so both are
 * read back from there and neither is computed.
 *
 * <p>Reads through the ACTIVE finder, like {@link PersistedWorkspaceCredentials} beside it — a
 * resolved workspace has no container to describe, and an unknown id is neither an admin workspace
 * nor the editor. Every failure direction here therefore falls to <b>false</b>, which is the only
 * direction a privilege may fall to and, for the editor, the only direction that cannot invent one.
 *
 * <h2>There is no memo any more, and its absence is the point</h2>
 *
 * <p>This class used to memoize the editor answer, per row id, for the life of the process, and that
 * memo was load-bearing rather than an optimisation. The answer was <em>derived</em> — repository
 * archetype {@code PROJECT} plus the row's branch equalling that repository's main branch — so it
 * took a live {@link RepositoryLookup} call, and a lookup cannot promise the same answer twice: an
 * unreachable qits-projects threw, the factory read that as "not the editor", and the resume
 * presented a plain-image spec, which under {@code Recreate.ifChanged} <b>replaces</b> the editor's
 * container. The memo, the third "could not ask" answer that was deliberately not written down, and
 * the refusal to remember a 200 that arrived without an archetype or a main branch were all one
 * defence against that.
 *
 * <p>All of it is deleted, because the thing it defended against cannot happen to a column. {@code
 * Workspace.editor} is written once, by the one creator, in this service's own database; reading it
 * costs the indexed row read {@link #isAdmin} was already making, and it gives the same answer at
 * every ensure whether or not any other service is reachable. Keeping a cache in front of it would
 * be a second copy of a local fact, with the one behaviour a memo can still have that a column
 * cannot: outliving the truth.
 */
@ApplicationScoped
public class PersistedWorkspacePostures implements WorkspacePostures {

  @Inject WorkspaceRepository workspaces;

  @Override
  @Transactional
  public boolean isAdmin(Long rowId) {
    if (rowId == null) {
      return false;
    }
    return workspaces.findActiveById(rowId).map(w -> w.admin).orElse(false);
  }

  @Override
  @Transactional
  public boolean isEditor(Long rowId) {
    if (rowId == null) {
      return false;
    }
    return workspaces.findActiveById(rowId).map(w -> w.editor).orElse(false);
  }
}
