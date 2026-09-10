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
   * The resolved agent-configuration document this workspace's <em>current container</em> was born
   * with — what qits-projects answered at provision time, and what the container carries in its own
   * environment for as long as it runs. Null whenever no container holds one: before the first
   * provision, and whenever the fetch could not be made (in which case {@link
   * #agentConfigurationError} says why).
   *
   * <p><b>A column for the reason the commissioned pair is one.</b> The document rides the
   * container's environment, the orchestrator has no start verb, and a spec whose environment
   * differs from the running container's is a {@code Recreate.ifChanged} <em>replacement</em>. A
   * document re-fetched at every ensure would therefore replace a container on every edit — and, on
   * its own {@code generatedAt} alone, on every ensure. The row is what makes the spec reproducible,
   * and it is the same rule the epic states from the other side: a running container keeps the
   * configuration it was created with, and an edit applies to the next one.
   *
   * <p>{@code text} rather than {@code @Lob}, like every other unbounded string on this entity. The
   * bytes are qits-projects' and are stored exactly as they arrived — see {@link
   * eu.wohlben.qits.workspaces.control.AgentConfigurationDocument}.
   */
  @Column(name = "agent_configuration", columnDefinition = "text")
  public String agentConfiguration;

  /**
   * Why this workspace's current container was created <b>without</b> a document, or null when it
   * holds one.
   *
   * <p><b>The fallback is recorded, not merely logged.</b> A container that cannot get its document
   * is created anyway and runs on the harness library's shipped defaults, because refusing to create
   * the workspace would trade a configuration outage for a work outage. That trade is only safe
   * while the fallback is visible, so it is written here beside the container's other state and
   * answered on {@link eu.wohlben.qits.workspaces.dto.WorkspaceDto#agentConfigurationError()}.
   */
  @Column(name = "agent_configuration_error", columnDefinition = "text")
  public String agentConfigurationError;

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

  /** When the workspace was resolved (integrated/abandoned); null while ACTIVE. */
  @Column(name = "resolved_at")
  public Instant resolvedAt;

  /** When the row was created; null for rows that predate this column. */
  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  public Instant createdAt;
}
