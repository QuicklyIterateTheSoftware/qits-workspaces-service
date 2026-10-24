package eu.wohlben.qits.workspaces.persistence;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.time.Instant;
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
   * Every workspace token id an ACTIVE row currently holds for a container (qits-625, epic
   * qits-1481) — {@link #liveCommissionedClientIds} for the token arm of the reconcile. A token no
   * ACTIVE row names is an orphan, so is one a recreate replaced, and so is one a TARGET row holds
   * with no live lease behind it.
   */
  public List<String> liveCommissionedTokenIds() {
    return list("status = ?1 and commissionedTokenId is not null", WorkspaceStatus.ACTIVE).stream()
        .filter(WorkspaceRepository::holdsTokenForAContainer)
        .map(w -> w.commissionedTokenId)
        .toList();
  }

  /**
   * Whether a row's token is still some container's (epic qits-1481): a DIRECT row's always, a
   * TARGET row's while it has a live lease — and while it reads RUNNING with none, which is a row a
   * retired runner still runs, its daemon dialling home on that token until its next start adopts
   * it. A STOPPED or FAILED TARGET row with no lease holds a token nothing runs with.
   */
  private static boolean holdsTokenForAContainer(Workspace row) {
    return row.placement != WorkspacePlacement.TARGET
        || row.leaseId != null
        || (row.runtimeStatus != WorkspaceRuntimeStatus.STOPPED
            && row.runtimeStatus != WorkspaceRuntimeStatus.FAILED);
  }

  /**
   * The ACTIVE slot workspaces (qits-1152): the rows that name no branch of their own, whose agents
   * carry the work. Old-model rows on a branch are not slots and drain as before (D20).
   */
  public List<Workspace> findActiveSlots() {
    return list("status = ?1 and branch is null order by id", WorkspaceStatus.ACTIVE);
  }

  /**
   * How many ACTIVE TARGET rows wait for a slot: their lease is QUEUED (epic qits-1481). A row that
   * waits, with no lease, for the target holding its legacy volume is not counted: it waits for a
   * node, not for a slot. The agent
   * scheduler creates a new regular workspace only while this is zero, so a busy estate queues
   * agents rather than leases; a slot asked for earlier in the same pass is counted before the next
   * is created.
   */
  public long countWaitingForTarget() {
    return count(
        "status = ?1 and placement = ?2 and runtimeStatus = ?3 and leaseId is not null",
        WorkspaceStatus.ACTIVE,
        WorkspacePlacement.TARGET,
        WorkspaceRuntimeStatus.QUEUED);
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

  // --- target placement (V30, epic qits-1481) ---------------------------------------------------

  /** The row running under {@code leaseId}, whatever its status, or null. */
  public Workspace findByLeaseId(UUID leaseId) {
    return leaseId == null ? null : find("leaseId", leaseId).firstResult();
  }

  /** {@link #findByLeaseId}, locked for an update that has to see the row as it is. */
  public Workspace lockByLeaseId(UUID leaseId) {
    return leaseId == null
        ? null
        : find("leaseId", leaseId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
  }

  /** Every row that names a lease, whatever its status: what the lease reconcile compares. */
  public List<Workspace> findWithLease() {
    return list("leaseId is not null order by id");
  }

  /**
   * A start's write, once qits-system answered the lease (epic qits-1481): the row records the lease
   * and its target and waits QUEUED, stamped {@code queuedAt}, the lease's {@code detail} as its
   * runtime error. A compare-and-swap on a STOPPED or FAILED TARGET row, or a QUEUED one that waited
   * for its legacy target, that names no lease and holds a token, so a stop or a second start in between changes nothing: answers 0 or 1.
   */
  public int recordLease(
      Long id, UUID leaseId, UUID targetId, String detail, String adoptedVolume, Instant queuedAt) {
    return update(
        "leaseId = ?1, targetId = ?2, runtimeStatus = ?3, runtimeError = ?4, adoptedVolume = ?5,"
            + " queuedAt = ?6 where id = ?7 and status = ?8 and placement = ?9 and runtimeStatus in"
            + " ?10 and leaseId is null and commissionedTokenId is not null",
        leaseId,
        targetId,
        WorkspaceRuntimeStatus.QUEUED,
        detail,
        adoptedVolume,
        queuedAt,
        id,
        WorkspaceStatus.ACTIVE,
        WorkspacePlacement.TARGET,
        List.of(
            WorkspaceRuntimeStatus.STOPPED,
            WorkspaceRuntimeStatus.FAILED,
            WorkspaceRuntimeStatus.QUEUED));
  }
}
