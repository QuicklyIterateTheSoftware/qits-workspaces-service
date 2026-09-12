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
   * why {@link eu.wohlben.qits.workspaces.control.WorkspaceCommandHistory} keys on {@link #id}
   * instead.
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

  /** The reason the last re-provision failed (when {@link #runtimeStatus} is FAILED); else null. */
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
   * The idp client commissioned for this workspace's <em>current container</em>, and its secret.
   * Both null whenever no container holds a credential — before the first provision, after a
   * teardown, and in every deployment with no issuer wired.
   *
   * <p>They are two columns and not a value object because they are read back one at a time by
   * {@code PersistedWorkspaceCredentials} and cleared together by every teardown seam. <b>Cleared
   * with the decommission, never after it</b>: a row naming a client qits-idp no longer has is a
   * credential nobody can find, and the reconcile would have to guess.
   *
   * <p>The secret is here for the reason {@code WorkspaceCredentials} spells out — the container's
   * spec must be reproducible at every ensure, and the issuer hands a secret out once. It is
   * {@code text} rather than {@code @Lob}, like every other unbounded string on this entity.
   */
  @Column(name = "commissioned_client_id", columnDefinition = "text")
  public String commissionedClientId;

  @Column(name = "commissioned_client_secret", columnDefinition = "text")
  public String commissionedClientSecret;

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
   * The Git refs this workspace's container may push, as a JSON array ({@code V6}). Read and written
   * through {@link eu.wohlben.qits.workspaces.control.GitRefs}, never by hand.
   *
   * <p>Written at creation — the list a dispatch sent, or the workspace's own branch — and after
   * that it only narrows. Null on rows that predate {@code V6}; those are commissioned with their
   * own branch, the same default a new row stores. Never the repository's default branch: a main
   * workspace stores (or, when null, is commissioned with) an empty list.
   */
  @Column(name = "git_refs", columnDefinition = "text")
  public String gitRefs;

  /**
   * True while {@link #gitRefs} is narrower than what qits-idp holds for {@link
   * #commissionedClientId} — a narrowing whose update has not reached the idp yet. The commission
   * reconcile retries it; a new commission clears it.
   */
  @Column(name = "git_refs_pending", nullable = false)
  public boolean gitRefsPending = false;

  /** When the workspace was resolved (integrated/abandoned); null while ACTIVE. */
  @Column(name = "resolved_at")
  public Instant resolvedAt;

  /** When the row was created; null for rows that predate this column. */
  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  public Instant createdAt;
}
