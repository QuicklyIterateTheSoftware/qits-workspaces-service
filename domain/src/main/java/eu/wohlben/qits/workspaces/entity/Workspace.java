package eu.wohlben.qits.workspaces.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A workspace, soft-deleted: cleanup/discard removes the on-disk workspace and branch but keeps
 * this row as the persistent record of the unit of work — its {@link #status}, the markdown {@link
 * #preamble} (why it was created) and {@link #result} (how it ended). Its {@link WorkspaceEvent}
 * timeline records what happened.
 *
 * <p>There is deliberately no unique constraint on {@code (repositoryId, workspaceId)} — resolved
 * rows accumulate and would collide with a live one, which is why V10 dropped V1's. What <em>is</em>
 * constrained is {@code (repositoryId, branch)} among {@code ACTIVE} rows ({@code
 * UQ_workspace_active_branch}, V3): a workspace is a branch ref plus a container that clones it, so
 * the branch is the resource it claims and only one live workspace may own one.
 *
 * <p>Rows in other bounded contexts that belong to a workspace (commands, bootstrap runs, prompt
 * drafts) reference it by id and are not reachable from here. Because the delete is soft, no FK
 * cascade ever fires for them; {@link eu.wohlben.qits.workspaces.control.WorkspaceResolved} is the
 * event they clean up on.
 *
 * <p><b>A {@link CausedRow}.</b> {@link #causationId} is the platform's generic trace column,
 * stamped from the ambient {@code CausationScope} at persist — which is the REST filter's restored
 * scope, because both creation paths run on the request thread and neither crosses an executor:
 * {@code createWorkspace} behind {@code POST /workspaces/api/workspaces}, and {@code
 * CaptureService.capture} behind the capture ingest, the machine caller most likely to send an
 * {@code X-Qits-Causation-Id}. A workspace created from a browser records none, and null is the
 * honest answer there. Insert-only by the stamp's contract: the column says why this unit of work
 * exists, and the status changes that follow are the {@link WorkspaceEvent} timeline's to explain,
 * each with a cause of its own.
 */
@Entity
@Table(name = "workspace")
@EntityListeners(CausationStamp.class)
public class Workspace extends PanacheEntityBase implements CausedRow {

  /** See the class javadoc; the platform's uniform column, never part of any constraint. */
  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }

  /**
   * The workspace's identity, inside and out. Every child table ({@code workspace_event}, {@code
   * workspace_bootstrap_run}, {@code workspace_prompt_draft}) already FKs to it, and it is what
   * routes, the daemon control socket and every port that names a workspace address. Its entire
   * specification is "unique", so it needs no {@link #repositoryId} beside it to identify a row.
   */
  @Id @GeneratedValue public Long id;

  /**
   * The branch-derived label: a display name and a path/container-name segment, <strong>not</strong>
   * an identifier. It is slug-validated because it becomes a path segment under the repo's
   * workspaces dir. It is unique only per repository and only among ACTIVE rows — every repository
   * tends to have one called {@code main} — and it is reusable once a workspace resolves, which is
   * why every port that holds records for a workspace keys on {@link #id} instead.
   */
  @Column(name = "workspace_id", nullable = false)
  public String workspaceId;

  /**
   * The owning repository, by <strong>id only</strong>. Deliberately not a JPA {@code @ManyToOne}:
   * this context owns its own datasource and Flyway lineage (the {@code artifacts}/{@code ci}
   * precedent), so it can hold no foreign key into another context's tables. A repository deleted
   * elsewhere simply leaves its workspaces behind as dangling history; {@link RepositoryLookup} is
   * how the owning application is consulted about one.
   */
  @Column(name = "repository_id", nullable = false)
  public String repositoryId;

  @Column(name = "parent_id")
  public String parent;

  /**
   * The branch this workspace owns. Stored (not derived from an on-disk checkout) because the
   * checkout now lives inside the workspace's container, not on the host — there is no host path to
   * read {@code git branch --show-current} from.
   */
  @Column(name = "branch")
  public String branch;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public WorkspaceStatus status = WorkspaceStatus.ACTIVE;

  /**
   * The state of this workspace's container — a recreatable cache of the durable branch, not part
   * of the {@link #status} lifecycle. {@code RUNNING} is normally recomputed live from the
   * container listing; the persisted value carries the {@code STOPPED}/{@code PROVISIONING}/{@code
   * FAILED} signal across restarts and out-of-band container loss.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "runtime_status", nullable = false)
  public WorkspaceRuntimeStatus runtimeStatus = WorkspaceRuntimeStatus.STOPPED;

  /**
   * The reason the last re-provision failed (when {@link #runtimeStatus} is FAILED), or — on a
   * RUNNING row — that its coding agent was killed mid-turn ({@code AgentKills}); else null.
   */
  @Column(name = "runtime_error", length = 2000)
  public String runtimeError;

  /*
   * `text`/`bytea` via columnDefinition, and NOT @Lob — the one entity mapping the move to postgres
   * had to change. On H2 a @Lob String was a clob and the two agreed; on postgres @Lob means a LARGE
   * OBJECT, so Hibernate binds an oid and the insert fails against the column V1 declares. Unbounded
   * either way, which is what these fields need.
   */

  /** Markdown: the reason/goal, authored at creation, editable while ACTIVE. */
  @Column(columnDefinition = "text")
  public String preamble;

  /** Markdown: the outcome, authored at integration or abandonment. */
  @Column(columnDefinition = "text")
  public String result;

  /**
   * The workspace token minted for this row's <em>current container</em> (qits-625, {@code V13}):
   * qits-idp's id for it, the {@code sub} the edge stamps on its JWT, and the value. A TARGET row's
   * container always holds one; a DIRECT (admin or editor) row's holds one whenever the deployment
   * has an edge plane (qits-1084), and a DIRECT provision with no edge plane is refused. All three
   * null on a row whose container holds no token.
   *
   * <p>The value is stored because the spec has to be reproducible at every start ({@code
   * WorkspaceCredentials}), and qits-idp answers a value once. <b>Cleared with the deletion, never
   * after it</b>: a row naming a token qits-idp no longer has is a token nobody can find, and the
   * reconcile would have to guess.
   */
  @Column(name = "commissioned_token_id", length = 64)
  public String commissionedTokenId;

  @Column(name = "commissioned_token_subject", length = 255)
  public String commissionedTokenSubject;

  @Column(name = "commissioned_token", columnDefinition = "text")
  public String commissionedToken;

  /**
   * Whether this row's container holds a workspace token (qits-1084), on either placement. A row
   * that holds a token reaches the platform through the edge with it, and is bound by its subject;
   * a row that does not is bound by nothing. Placement does not decide it.
   */
  public boolean holdsToken() {
    return commissionedTokenId != null && !commissionedTokenId.isBlank();
  }

  /**
   * The principal this row's own container presents to the platform: its token's subject ({@code
   * tok-…}) when it {@link #holdsToken holds a token}, else null — which never matches a caller.
   * What the daemon socket's agent binding, the tunnel's dial-back and a dispatch's {@code
   * agentIdentity} all compare a caller with.
   */
  public String boundSubject() {
    return holdsToken() ? commissionedTokenSubject : null;
  }

  /**
   * Whether this workspace runs in <b>admin mode</b>: its container is started with the host's
   * docker socket bound into it, so platform administration can be done from inside a workspace.
   *
   * <p><b>A container holding that socket is root-equivalent on the host</b>, which is why this is
   * a column on the row rather than a flag on a launch. It has to be reproducible at every ensure —
   * the orchestrator has no start verb, so a stopped container is started by presenting its spec
   * again and a spec that differs REPLACES the container ({@link
   * eu.wohlben.qits.workspaces.control.WorkspaceCredentials} carries the same reasoning for the
   * credential pair) — and it makes the posture a property of the workspace, decided once, in the
   * request that created it, where a reviewer can see it.
   *
   * <p><b>False for everything that did not ask.</b> There is no verb that promotes an existing
   * workspace: the whole point is that the socket is granted to the few workspaces somebody
   * deliberately asked for it in, and never acquired by one that has been running for a week.
   */
  @Column(name = "admin", nullable = false)
  public boolean admin = false;

  /**
   * Where this workspace's container runs ({@code V12}, {@code V30}): {@link
   * WorkspacePlacement#DIRECT} on the platform host through qits-containers, or {@link
   * WorkspacePlacement#TARGET} in a WORKSPACE slot of an execution target, leased from qits-system.
   * Decided at create and never changed, like {@link #admin}. Admin rows are always DIRECT; {@code
   * ck_workspace_runner_posture} keeps that. (The {@code editor} column of {@code V7} is unread
   * since the editor was removed, qits-1152.)
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "placement", nullable = false, length = 16)
  public WorkspacePlacement placement = WorkspacePlacement.DIRECT;

  /**
   * The qits-system lease this TARGET row's container runs under ({@code V30}, epic qits-1481), or
   * null: always null on a DIRECT row, null on a TARGET row with no live lease. Set when the lease is
   * created and cleared once it is ENDED; {@code commissioned_token_*} holds that lease's token. No
   * foreign key: the lease lives in qits-system's database.
   */
  @Column(name = "lease_id")
  public UUID leaseId;

  /**
   * The target the current lease was granted on, or null while it is QUEUED or there is none. The
   * work volume {@code ws-<rowId>} lives on that target between leases, which is qits-system's to
   * remember; this column is what the listing shows.
   */
  @Column(name = "target_id")
  public UUID targetId;

  /**
   * The retired workspaces runner's container and volume name this row's work was adopted from
   * ({@code qits-workspaces-runner-ws-<rowId>-<oldRunnerId8>}, {@code V30}), or null. Every lease of
   * the row carries {@code adopt{container, volume}} with it for as long as it is set: the runner
   * mounts the adopted volume only when the lease names it, so a lease without it would get a fresh,
   * empty work volume. Cleared with the volume, by a delete-container.
   */
  @Column(name = "adopted_volume", columnDefinition = "text")
  public String adoptedVolume;

  /**
   * When this TARGET row was last asked to start ({@link WorkspaceRuntimeStatus#QUEUED}), while its
   * lease waits for a slot. Null while the row is not waiting.
   */
  @Column(name = "queued_at")
  public Instant queuedAt;

  /**
   * What a <b>dispatched</b> workspace is for: the qits-projects ticket, or the epic, the dispatch
   * was about. Both null for every workspace a person created by hand, and for every workspace that
   * predates {@code V5}.
   *
   * <p><b>A field, not prose.</b> A dispatch's {@link #preamble} used to be the whole ticket — its
   * title, its status line and its full description — rendered over there and frozen here, while the
   * agent was told in the same breath to read the ticket live over MCP. So the copy was stale by
   * construction and buried the one fact a person scanning the workspace list wants. The reference
   * is that fact; the preamble goes back to being what it is, a person's prose.
   *
   * <p><b>Carried, never resolved.</b> Nothing in this context reads a ticket or an epic, and there
   * is no port that could: they live in another context's store, which is also why neither is a
   * foreign key. The <em>client</em> turns one into a link, because composing it needs this
   * platform's public origin — something the browser is told by the navigation document and this
   * service is not told at all.
   *
   * <p>Two independent fields rather than a {@code (kind, id)} pair: a workspace names at most one
   * in practice, and the schema does not need to enforce that.
   */
  @Column(name = "ticket_id", columnDefinition = "text")
  public String ticketId;

  @Column(name = "epic_id", columnDefinition = "text")
  public String epicId;

  /**
   * The same subject's qualified id as qits-projects spells it — {@code <project-slug>-<number>},
   * e.g. {@code qits-614} — beside {@link #ticketId}/{@link #epicId} ({@code V8}). Null for the same
   * rows those two are null for.
   *
   * <p><b>Read-side only, and the reader is the daemon rather than this service.</b> The in-container
   * workspace-daemon names its agent sessions {@code [❗]<status square> <entityId> <title>} from it
   * and the three facts below ({@code QITS_WORKSPACE_DAEMON_ENTITY_ID}, written by {@link
   * eu.wohlben.qits.workspaces.control.WorkspaceContainerFactory}), and it is a column — rather than
   * an argument threaded through only the provision path — because the orchestrator has no start
   * verb: a stopped container is started by presenting its spec again, and a value present on
   * provision but missing on resume would make every resume a spec change and therefore a
   * replacement.
   */
  @Column(name = "entity_id", columnDefinition = "text")
  public String entityId;

  /**
   * The work item this workspace is bound to: its entity id in qits-projects ({@code V10}). One
   * field for every archetype — epic, ticket, feature, task, campaign — where {@link #ticketId} and
   * {@link #epicId} name only two. Null for a workspace no dispatch bound.
   *
   * <p><b>The UUID, not the qualified id.</b> The UUID never changes. The qualified id ({@link
   * #entityId}, e.g. {@code qits-614}) changes when the project slug changes or the item moves to
   * another project, and this row would keep the old one. So lookups go by this field, and {@link
   * #entityId} is kept for display and for the UI's links.
   *
   * <p>A work item has many workspaces over time (each integrated or abandoned one stays as a row),
   * and at most one ACTIVE one: a dispatch answers the ACTIVE workspace with this id before it
   * makes a new one. The schema does not enforce it.
   */
  @Column(name = "work_id", columnDefinition = "text")
  public String workId;

  /**
   * The same subject's title, status word (qits-projects' enum constant, e.g. {@code REFINED}) and
   * blocked flag ({@code V9}, qits-617) — what the daemon puts after the id and in front of it. Null
   * for every row {@link #entityId} is null for, and for every row that predates {@code V9}; a null
   * {@link #entityBlocked} reads as not blocked.
   *
   * <p><b>Unlike {@link #entityId}, these move.</b> The dispatch writes them, and so does {@code
   * DispatchService.markEntity} every time qits-projects relays a change — whether or not a container
   * is running, so a stopped or recreated one boots with what is true now. Written through those two
   * and nothing else; read by {@code PersistedWorkspaceEntityFacts} for the container spec. What a
   * change costs a STOPPED container (a replacement at its next resume) is in {@code V9}'s header.
   */
  @Column(name = "entity_title", columnDefinition = "text")
  public String entityTitle;

  @Column(name = "entity_status")
  public String entityStatus;

  @Column(name = "entity_blocked")
  public Boolean entityBlocked;

  /**
   * The daemon command the last agent launch this service made here started ({@code V18},
   * qits-895), or null for a row no host-side launch ever reached.
   *
   * <p>It is how the dispatched agent is told apart from a person's other sessions in the same
   * workspace: an agent-activity frame names its command, and only a frame naming this one is
   * relayed to qits-projects as the work item's agent waiting (or not). Replaced by every launch
   * {@code DispatchService} makes — a dispatch's, and a delivery's fallback launch — and by nothing
   * else; a turn delivered to the running agent goes to the same command. Not on any DTO.
   *
   * <p><b>Read-only to the entity</b>: written by {@code WorkspaceRepository.recordDispatchCommand}
   * alone, a bulk update, so that no other transaction's flush of a row it loaded earlier can put
   * the previous command back. Setting this field does nothing.
   */
  @Column(
      name = "dispatch_command_id",
      columnDefinition = "text",
      insertable = false,
      updatable = false)
  public String dispatchCommandId;

  /** When the workspace was resolved (integrated/abandoned); null while ACTIVE. */
  @Column(name = "resolved_at")
  public Instant resolvedAt;

  /** When the row was created; null for rows that predate this column. */
  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  public Instant createdAt;
}
