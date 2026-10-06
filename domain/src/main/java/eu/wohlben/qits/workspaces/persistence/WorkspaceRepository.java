package eu.wohlben.qits.workspaces.persistence;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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

  /**
   * The active workspace bound to this work item ({@code Workspace.workId}) in this repository, if
   * any. There is at most one in all repositories together ({@code uq_workspace_active_work},
   * {@code V11}).
   */
  public Optional<Workspace> findActiveByRepositoryAndWorkId(String repositoryId, String workId) {
    if (workId == null || workId.isBlank()) {
      return Optional.empty();
    }
    return find(
            "repositoryId = ?1 and workId = ?2 and status = ?3 order by id desc",
            repositoryId,
            workId.trim(),
            WorkspaceStatus.ACTIVE)
        .firstResultOptional();
  }

  /**
   * Every ACTIVE workspace bound to a work item, oldest first — the open workspaces a work list
   * shows. Integrated and abandoned rows are left out, and so are workspaces bound to no work item
   * (hand-made ones, and the editor's row).
   */
  public List<Workspace> findOpenBoundToWork() {
    return list("status = ?1 and workId is not null order by id", WorkspaceStatus.ACTIVE);
  }

  /**
   * Whether this work item already has an active workspace, in any repository — see {@code
   * uq_workspace_active_work} ({@code V11}). False for a null or blank id: those are not
   * constrained.
   */
  public boolean existsActiveByWorkId(String workId) {
    if (workId == null || workId.isBlank()) {
      return false;
    }
    return count("workId = ?1 and status = ?2", workId.trim(), WorkspaceStatus.ACTIVE) > 0;
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
   * Every workspace token id an ACTIVE RUNNER row currently holds (qits-625) — {@link
   * #liveCommissionedClientIds} for the token arm of the reconcile. A token no ACTIVE row names is
   * an orphan, and so is one a recreate replaced.
   */
  public List<String> liveCommissionedTokenIds() {
    return list("status = ?1 and commissionedTokenId is not null", WorkspaceStatus.ACTIVE).stream()
        .map(w -> w.commissionedTokenId)
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
   * container still holds the commission it has to reach — a client pair, or a RUNNER row's token
   * (qits-625). What the reconcile sends again.
   */
  public List<Long> pendingGitRefIds() {
    return list(
            "status = ?1 and gitRefsPending = true"
                + " and (commissionedClientId is not null or commissionedTokenId is not null)",
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
   * It used to read {@code parent is null}, back when a root row meant something: the per-project
   * editor wrote one main workspace per project, through a create of its own. That door is gone
   * (its create went too, in qits-780), and with it the only writer of a parentless row —
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

  /**
   * Every workspace bound to one work item, whatever its status, newest first. {@code workRef} is
   * the item's entity id ({@code work_id}) or its qualified id ({@code entity_id}, e.g. {@code
   * qits-614}); a UUID-shaped value is read as the first, anything else as the second.
   */
  public List<Workspace> findByWorkRef(String workRef) {
    if (workRef == null || workRef.isBlank()) {
      return List.of();
    }
    String ref = workRef.trim();
    if (UUID_SHAPE.matcher(ref).matches()) {
      return list("workId = ?1 order by id desc", ref);
    }
    return list("entityId = ?1 order by id desc", ref);
  }

  private static final java.util.regex.Pattern UUID_SHAPE =
      java.util.regex.Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  /** {@link #findBySubjects} for work ids: every workspace bound to one of them, any status. */
  public List<Workspace> findByWorkIds(Collection<String> workIds) {
    List<String> ids = nonNull(workIds);
    return ids.isEmpty() ? List.of() : list("workId in ?1", ids);
  }

  private static List<String> nonNull(Collection<String> ids) {
    return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
  }

  // --- runner placement (V12) ---------------------------------------------------------------------
  // Each over ACTIVE rows, which is what ix_workspace_runner covers.

  /**
   * How many of this runner's ACTIVE workspaces hold a slot right now: RUNNING, or PROVISIONING (a
   * take the runner has not answered yet). The server's count of a runner's used slots, which wins
   * over the runner's own.
   */
  public long countLiveOnRunner(UUID runnerId) {
    return count(
        "runnerId = ?1 and status = ?2 and runtimeStatus in ?3",
        runnerId,
        WorkspaceStatus.ACTIVE,
        List.of(WorkspaceRuntimeStatus.RUNNING, WorkspaceRuntimeStatus.PROVISIONING));
  }

  /** How many ACTIVE workspaces this runner owns, whatever their containers are doing. */
  public long countActiveOnRunner(UUID runnerId) {
    return count("runnerId = ?1 and status = ?2", runnerId, WorkspaceStatus.ACTIVE);
  }

  /** The ACTIVE rows {@code runnerId} owns, by id. */
  public List<Workspace> findActiveOnRunner(UUID runnerId) {
    return list("runnerId = ?1 and status = ?2 order by id", runnerId, WorkspaceStatus.ACTIVE);
  }

  /**
   * The QUEUED rows sticky to {@code runnerId}: placed on it before, and waiting for one of its
   * slots. Never-placed rows any runner may take are not counted; they are no runner's yet.
   */
  public long countQueuedOnRunner(UUID runnerId) {
    return count(
        "runnerId = ?1 and status = ?2 and runtimeStatus = ?3",
        runnerId,
        WorkspaceStatus.ACTIVE,
        WorkspaceRuntimeStatus.QUEUED);
  }

  /** The row ids of the ACTIVE workspaces this runner owns, oldest first. */
  public List<Long> findActiveIdsOnRunner(UUID runnerId) {
    return list("runnerId = ?1 and status = ?2 order by id", runnerId, WorkspaceStatus.ACTIVE)
        .stream()
        .map(w -> w.id)
        .toList();
  }

  /**
   * Start for a RUNNER row: STOPPED or FAILED becomes QUEUED, stamped {@code queuedAt}, keeping its
   * runner. A compare-and-swap, so two starts and a start racing anything else change the row once:
   * answers how many rows changed, 0 or 1.
   *
   * <p><b>Only a row that holds its workspace token is queued</b> (qits-625): a RUNNER container's
   * every hop is the token, so a row without one must never become claimable. The start mints it
   * first; this clause is the rule as the swap keeps it.
   */
  public int queueForRunner(Long id, Instant queuedAt) {
    return update(
        "runtimeStatus = ?1, queuedAt = ?2, runtimeError = null where id = ?3 and status = ?4"
            + " and placement = ?5 and runtimeStatus in ?6 and commissionedTokenId is not null",
        WorkspaceRuntimeStatus.QUEUED,
        queuedAt,
        id,
        WorkspaceStatus.ACTIVE,
        WorkspacePlacement.RUNNER,
        List.of(WorkspaceRuntimeStatus.STOPPED, WorkspaceRuntimeStatus.FAILED));
  }

  /**
   * Stop for a QUEUED RUNNER row: back to STOPPED with no frame, because nothing runs anywhere yet.
   * A compare-and-swap against a runner's claim: 0 when a runner took the row first.
   */
  public int unqueueForRunner(Long id) {
    return update(
        "runtimeStatus = ?1, queuedAt = null where id = ?2 and status = ?3 and placement = ?4"
            + " and runtimeStatus = ?5",
        WorkspaceRuntimeStatus.STOPPED,
        id,
        WorkspaceStatus.ACTIVE,
        WorkspacePlacement.RUNNER,
        WorkspaceRuntimeStatus.QUEUED);
  }

  /**
   * <b>Reserve is the claim</b> (qits-849): a QUEUED RUNNER row that is unplaced or already this
   * runner's becomes PROVISIONING on it. The WHERE clause is the whole race: of two runners updating
   * one never-placed row, the second re-reads it PROVISIONING after the first commits and changes
   * nothing. Answers the changed-row count, 0 or 1. A row holding no workspace token is never
   * claimed (qits-625), whatever wrote it QUEUED.
   */
  public int claimForRunner(Long id, UUID runnerId) {
    return update(
        "runnerId = ?1, runtimeStatus = ?2, runtimeError = null where id = ?3 and status = ?4"
            + " and placement = ?5 and runtimeStatus = ?6 and (runnerId is null or runnerId = ?1)"
            + " and commissionedTokenId is not null",
        runnerId,
        WorkspaceRuntimeStatus.PROVISIONING,
        id,
        WorkspaceStatus.ACTIVE,
        WorkspacePlacement.RUNNER,
        WorkspaceRuntimeStatus.QUEUED);
  }

  /**
   * The QUEUED rows a runner's reserve may take, in the order it takes them: its own sticky rows
   * first, then never-placed ones, each oldest {@code queuedAt} first.
   */
  public List<Long> queuedCandidatesFor(UUID runnerId) {
    List<Long> candidates = new ArrayList<>();
    candidates.addAll(
        getEntityManager()
            .createQuery(
                "select w.id from Workspace w where w.status = :active and w.placement = :runner"
                    + " and w.runtimeStatus = :queued and w.runnerId = :r"
                    + " order by w.queuedAt, w.id",
                Long.class)
            .setParameter("active", WorkspaceStatus.ACTIVE)
            .setParameter("runner", WorkspacePlacement.RUNNER)
            .setParameter("queued", WorkspaceRuntimeStatus.QUEUED)
            .setParameter("r", runnerId)
            .getResultList());
    candidates.addAll(
        getEntityManager()
            .createQuery(
                "select w.id from Workspace w where w.status = :active and w.placement = :runner"
                    + " and w.runtimeStatus = :queued and w.runnerId is null"
                    + " order by w.queuedAt, w.id",
                Long.class)
            .setParameter("active", WorkspaceStatus.ACTIVE)
            .setParameter("runner", WorkspacePlacement.RUNNER)
            .setParameter("queued", WorkspaceRuntimeStatus.QUEUED)
            .getResultList());
    return candidates;
  }

  /** A runner's backlog: the QUEUED RUNNER rows it may take, its own and the unplaced ones. */
  public long countBacklogFor(UUID runnerId) {
    return count(
        "placement = ?1 and runtimeStatus = ?2 and status = ?3 and (runnerId is null or runnerId"
            + " = ?4)",
        WorkspacePlacement.RUNNER,
        WorkspaceRuntimeStatus.QUEUED,
        WorkspaceStatus.ACTIVE,
        runnerId);
  }

  /** This runner's ACTIVE rows, locked for an update that has to see them as they are. */
  public List<Workspace> lockActiveOnRunner(UUID runnerId) {
    return find("runnerId = ?1 and status = ?2 order by id", runnerId, WorkspaceStatus.ACTIVE)
        .withLock(LockModeType.PESSIMISTIC_WRITE)
        .list();
  }
}
