package eu.wohlben.qits.workspaces.persistence;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class WorkspaceRepository implements PanacheRepository<Workspace> {

  // --- ACTIVE-only (operational) -----------------------------------------------------------------
  // Workspaces are soft-deleted, so resolved rows linger. Everything that operates on a live
  // workspace
  // (terminal, command launch, merge, discard, branch resolution) must use these ACTIVE finders.

  /**
   * The active workspace with this id. Takes no repository: the id is the identity, and a unique id
   * is already unique — pairing it with {@code repositoryId} to select a row would be redundant.
   */
  public Optional<Workspace> findActiveById(Long id) {
    return find("id = ?1 and status = ?2", id, WorkspaceStatus.ACTIVE).firstResultOptional();
  }

  /**
   * @deprecated the string id is a label, not an identity — it is unique only per repository and
   *     reusable once a workspace resolves. Retained for {@code createWorkspace}'s
   *     one-ACTIVE-per-label guard and for resolving a user-typed merge target; address a workspace
   *     with {@link #findActiveById}.
   */
  @Deprecated
  public Optional<Workspace> findActiveByRepositoryAndWorkspaceId(
      String repositoryId, String workspaceId) {
    return find(
            "repositoryId = ?1 and workspaceId = ?2 and status = ?3",
            repositoryId,
            workspaceId,
            WorkspaceStatus.ACTIVE)
        .firstResultOptional();
  }

  public List<Workspace> findActiveByRepositoryId(String repositoryId) {
    return list("repositoryId = ?1 and status = ?2", repositoryId, WorkspaceStatus.ACTIVE);
  }

  public boolean existsActiveByRepositoryAndWorkspaceId(String repositoryId, String workspaceId) {
    return count(
            "repositoryId = ?1 and workspaceId = ?2 and status = ?3",
            repositoryId,
            workspaceId,
            WorkspaceStatus.ACTIVE)
        > 0;
  }

  /**
   * The active workspace that owns {@code branch} in this repository, if any. The branch — not the
   * workspace id — is the resource a workspace claims: a workspace <em>is</em> a branch ref plus a
   * container that clones it, so two active workspaces on one branch means two checkouts committing
   * and auto-pushing to the same ref. At most one can exist, which {@code
   * UQ_workspace_active_branch} (V3) enforces structurally.
   */
  public Optional<Workspace> findActiveByRepositoryAndBranch(String repositoryId, String branch) {
    return find(
            "repositoryId = ?1 and branch = ?2 and status = ?3",
            repositoryId,
            branch,
            WorkspaceStatus.ACTIVE)
        .firstResultOptional();
  }

  /** Whether {@code branch} already has an active workspace — see {@link
   * #findActiveByRepositoryAndBranch}. */
  public boolean existsActiveByRepositoryAndBranch(String repositoryId, String branch) {
    return count(
            "repositoryId = ?1 and branch = ?2 and status = ?3",
            repositoryId,
            branch,
            WorkspaceStatus.ACTIVE)
        > 0;
  }

  /**
   * Every commissioned client id an ACTIVE workspace currently claims — what the commission
   * reconcile keeps and decommissions everything else.
   *
   * <p>ACTIVE and non-null together are the whole claim, and both halves matter. A resolved row's
   * credential was given back when it resolved, and a row whose container was deleted has a null
   * column, so either state leaves whatever qits-idp still holds an orphan. The id is compared
   * rather than the row's existence, so a recreate — which mints a new pair over the old one — makes
   * the previous client an orphan the moment it is replaced.
   */
  public List<String> liveCommissionedClientIds() {
    return list("status = ?1 and commissionedClientId is not null", WorkspaceStatus.ACTIVE).stream()
        .map(w -> w.commissionedClientId)
        .toList();
  }

  /**
   * Every ACTIVE workspace that stores a Git ref list — the candidates a new workspace may narrow.
   * Rows that predate the column store none; their list is only their own branch, which is never
   * narrowed away, so they are never candidates.
   */
  public List<Workspace> findActiveWithGitRefs() {
    return list("status = ?1 and gitRefs is not null", WorkspaceStatus.ACTIVE);
  }

  /**
   * The ACTIVE workspaces whose narrowed Git ref list has not reached qits-idp yet, and whose
   * container still holds the commission it has to reach. What the reconcile sends again.
   */
  public List<Long> pendingGitRefIds() {
    return list(
            "status = ?1 and gitRefsPending = true and commissionedClientId is not null",
            WorkspaceStatus.ACTIVE)
        .stream()
        .map(w -> w.id)
        .toList();
  }

  /**
   * The repositories that have a <b>root</b> workspace here — an ACTIVE row with no parent, which is
   * what {@code createMainWorkspace} writes and nothing else does. Distinct, so it is one id per
   * repository rather than one per row.
   *
   * <p>The candidate set for {@code EditorProxyTargets}: an editor's origin names a project, and the
   * project's wrapper repository is recognised by its name among these. It is deliberately narrow —
   * every branched workspace is excluded by the parent alone — and it is small by construction, one
   * entry per repository somebody has ever opened a main workspace for.
   */
  public List<String> activeRootRepositoryIds() {
    return find("status = ?1 and parent is null", WorkspaceStatus.ACTIVE).stream()
        .map(w -> w.repositoryId)
        .distinct()
        .toList();
  }

  /**
   * The live workspaces that name one of these qits-projects rows as their subject — {@code
   * ticket_id} or {@code epic_id}, the two columns a dispatch writes. Batched on purpose: the caller
   * is a whole tickets panel asking one question about thirty rows, not thirty callers.
   *
   * <p><b>Live means {@code ACTIVE}, and nothing about the container.</b> This is the rule for the
   * whole feature and it is written here, once, so no client decides it a second time. An ACTIVE row
   * is a workspace that still exists and still owns its branch — a stopped one included, because a
   * container is a recreatable cache of the branch and a second dispatch onto that branch would
   * adopt the workspace rather than make another. So a stopped workspace is still the one working on
   * the ticket, and it stays reported and stays navigable. What ends the reference is resolution:
   * integrating or abandoning the workspace moves the row out of ACTIVE, it stops being reported
   * from that moment, and nothing had to remember to clear a pointer — which is the whole reason the
   * reference lives on the workspace and not on the ticket.
   *
   * <p>Either collection may be empty; both empty answers empty without touching the database. Null
   * ids are dropped rather than matched — a {@code ticket_id is null} row is every hand-made
   * workspace on the platform, and returning those would be the opposite of the question.
   */
  public List<Workspace> findActiveBySubjects(
      Collection<String> ticketIds, Collection<String> epicIds) {
    List<String> tickets = nonNull(ticketIds);
    List<String> epics = nonNull(epicIds);
    if (tickets.isEmpty() && epics.isEmpty()) {
      return List.of();
    }
    if (epics.isEmpty()) {
      return list("status = ?1 and ticketId in ?2", WorkspaceStatus.ACTIVE, tickets);
    }
    if (tickets.isEmpty()) {
      return list("status = ?1 and epicId in ?2", WorkspaceStatus.ACTIVE, epics);
    }
    return list(
        "status = ?1 and (ticketId in ?2 or epicId in ?3)", WorkspaceStatus.ACTIVE, tickets, epics);
  }

  private static List<String> nonNull(Collection<String> ids) {
    return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
  }

  // --- Any-status (history / discovery) ----------------------------------------------------------

  /** Every workspace (active + resolved) for a repository, newest first — for the history view. */
  public List<Workspace> findByRepositoryId(String repositoryId) {
    return list("repositoryId = ?1 order by id desc", repositoryId);
  }
}
