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
   * <b>The editor's row</b> — the ACTIVE workspace whose {@code editor} column is set, of which
   * there is at most one ({@code uq_workspace_active_editor}, {@code V7}).
   *
   * <p>One indexed local read, and it is the whole of resolving the editor now: the door asks it to
   * decide whether to write the singleton, the proxy asks it to turn an editor origin into a
   * container. What it replaced was a scan — one qits-projects round trip per repository somebody
   * had opened a main workspace for, to recognise a project's wrapper by the name its slug derives —
   * which existed only because the editor was per project and had to be found by one.
   *
   * <p>{@code firstResultOptional} and not a count-then-read: the index makes a second row
   * impossible, so taking the first is taking the only one.
   */
  public Optional<Workspace> findActiveEditor() {
    return find("editor = true and status = ?1", WorkspaceStatus.ACTIVE).firstResultOptional();
  }

  /**
   * Every distinct repository an ACTIVE workspace stands on.
   *
   * <p>It is the only enumeration this context has, and {@link
   * eu.wohlben.qits.workspaces.control.EditorProjects} is its reader: this service holds no project
   * table and qits-projects publishes no "every project" door here, so the set of projects the
   * shared editor clones is derived from the repositories this platform is <em>worked in</em>. That
   * is a real narrowing and it is deliberate — see {@code PersistedEditorProjects} for what it costs
   * and why the alternative is a new cross-context door.
   *
   * <p><b>EVERY active row, not only the root ones, and that is the whole of this query's history.</b>
   * It used to read {@code parent is null}, back when a root row meant something: {@code
   * createMainWorkspace} wrote one per project and the per-project editor was the thing that called
   * it. That door is gone, and with it the only production writer of a parentless row —
   * {@code createWorkspace} always sets a parent — so the narrow form would have answered EMPTY on a
   * live platform and the shared editor would have cloned nothing, which is precisely the failure
   * the feature exists to prevent. The cost of the wide form is one {@code find} per repository
   * instead of per project's root, paid only while an editor spec is being built.
   *
   * <p>The editor's own row is excluded here rather than at the caller, because its {@code
   * repository_id} is a sentinel ({@code EditorWorkspace.REPOSITORY_ID}) that resolves to nothing
   * and would cost a registry round trip per ensure to learn it.
   *
   * <p>Ordered, and that is not cosmetic: the answer reaches a container's environment, environment
   * is part of the spec, and a spec that reshuffles is a {@code Recreate.ifChanged} replacement of
   * the running editor.
   */
  public List<String> activeRepositoryIds() {
    return getEntityManager()
        .createQuery(
            "select distinct w.repositoryId from Workspace w"
                + " where w.status = :status and w.editor = false"
                + " order by w.repositoryId",
            String.class)
        .setParameter("status", WorkspaceStatus.ACTIVE)
        .getResultList();
  }

  // --- Any-status (history / discovery) ----------------------------------------------------------

  /** Every workspace (active + resolved) for a repository, newest first — for the history view. */
  public List<Workspace> findByRepositoryId(String repositoryId) {
    return list("repositoryId = ?1 order by id desc", repositoryId);
  }

  /**
   * Every workspace that names one of these qits-projects rows as its subject — {@code ticket_id} or
   * {@code epic_id}, the two columns a dispatch writes. Batched on purpose: the caller is a whole
   * tickets panel asking one question about thirty rows, not thirty callers.
   *
   * <p><b>Status narrows nothing here, and each row carries its own instead.</b> A subject reference
   * is a historical fact — this workspace was the one dispatched onto that ticket — and it does not
   * stop being true when the branch lands. Every row the subject names comes back with its {@code
   * status} and its {@code resolvedAt} beside it, so the reader decides what the answer means: a
   * tickets panel that wants the way in looks for the ACTIVE row, and one that wants to say "the
   * work on this ticket was integrated" reads the resolved one instead. Filtering here would make
   * the second reading impossible and the first no cheaper, and it would hide the difference between
   * a ticket whose workspace was integrated and a ticket that never had one at all.
   *
   * <p>Nothing about the container narrows it either. An ACTIVE row is a workspace that still exists
   * and still owns its branch — a stopped one included, because a container is a recreatable cache
   * of the branch and a second dispatch onto that branch would adopt the workspace rather than make
   * another. So a stopped workspace is still the one working on the ticket, and it stays reported
   * and stays navigable.
   *
   * <p>Either collection may be empty; both empty answers empty without touching the database. Null
   * ids are dropped rather than matched — a {@code ticket_id is null} row is every hand-made
   * workspace on the platform, and returning those would be the opposite of the question.
   */
  public List<Workspace> findBySubjects(Collection<String> ticketIds, Collection<String> epicIds) {
    List<String> tickets = nonNull(ticketIds);
    List<String> epics = nonNull(epicIds);
    if (tickets.isEmpty() && epics.isEmpty()) {
      return List.of();
    }
    if (epics.isEmpty()) {
      return list("ticketId in ?1", tickets);
    }
    if (tickets.isEmpty()) {
      return list("epicId in ?1", epics);
    }
    return list("ticketId in ?1 or epicId in ?2", tickets, epics);
  }

  private static List<String> nonNull(Collection<String> ids) {
    return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
  }
}
