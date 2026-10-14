package eu.wohlben.qits.workspaces.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An agent: one work item's coding agent and its worktree inside a workspace (qits-1152, D15). The
 * workspace is only the slot it runs in; everything about the work — the item, the wrapper branch,
 * the session, the credential and its Git refs — is here. See {@code V21}.
 *
 * <p><b>In, a {@link CausedRow}.</b> Both creators — the dispatch door and a person's door — write
 * it on the request thread, so the {@code CausationStamp} reads the REST filter's scope. Later
 * updates (placement, session, activity) do not move the stamp, which is insert-only by design: the
 * row says why the agent exists.
 */
@Entity
@Table(name = "agent_worktree")
@EntityListeners(CausationStamp.class)
public class AgentWorktree extends PanacheEntityBase implements CausedRow {

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

  /** Minted by the service before the insert: it names the agent to the daemon and to the idp. */
  @Id public UUID id;

  /** The workspace (slot) it runs in; null while QUEUED for one. */
  @Column(name = "workspace_id")
  public Long workspaceId;

  /** The wrapper repository; every workspace it may run in is scoped to it. */
  @Column(name = "repository_id", nullable = false)
  public String repositoryId;

  @Column(name = "work_id", nullable = false, columnDefinition = "text")
  public String workId;

  @Column(name = "entity_id", columnDefinition = "text")
  public String entityId;

  @Column(name = "ticket_id", columnDefinition = "text")
  public String ticketId;

  @Column(name = "epic_id", columnDefinition = "text")
  public String epicId;

  /** {@code <prefix>/<workId>}; submodule branches are {@code <wrapperBranch>-<the agent's choice>}. */
  @Column(name = "wrapper_branch", nullable = false, columnDefinition = "text")
  public String wrapperBranch;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public AgentHarness harness = AgentHarness.CLAUDE;

  /** Runs only in an admin workspace, and is commissioned the admin kind (D10). */
  @Column(nullable = false)
  public boolean admin = false;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public AgentState state = AgentState.ACTIVE;

  @Enumerated(EnumType.STRING)
  @Column(name = "run_state", nullable = false, length = 16)
  public AgentRunState runState = AgentRunState.QUEUED;

  /** The harness session a restart resumes (D5, D16). */
  @Column(name = "session_id", columnDefinition = "text")
  public String sessionId;

  /** The daemon command the harness runs as; the activity relay names it. */
  @Column(name = "command_id", columnDefinition = "text")
  public String commandId;

  @Column(name = "entity_title", columnDefinition = "text")
  public String entityTitle;

  @Column(name = "entity_status", columnDefinition = "text")
  public String entityStatus;

  @Column(name = "entity_blocked")
  public Boolean entityBlocked;

  @Column(name = "block_source", columnDefinition = "text")
  public String blockSource;

  /** What is still to be said: the instruction until the first start, then delivered turns. */
  @Column(name = "pending_text", columnDefinition = "text")
  public String pendingText;

  @Column(name = "pending_compact", nullable = false)
  public boolean pendingCompact = false;

  @Column(name = "commissioned_token_id", length = 64)
  public String commissionedTokenId;

  @Column(name = "commissioned_token_subject", length = 255)
  public String commissionedTokenSubject;

  @Column(name = "commissioned_token", columnDefinition = "text")
  public String commissionedToken;

  /** The refs the agent credential may push, as a JSON array (see {@code GitRefs}). */
  @Column(name = "git_refs", columnDefinition = "text")
  public String gitRefs;

  /** Since when the harness waits for input; null while it works or is not running. */
  @Column(name = "waiting_since")
  public Instant waitingSince;

  @Column(name = "last_activity_at")
  public Instant lastActivityAt;

  /** When the cleanup sweep last handed leftover work back to the agent (D5, D7). */
  @Column(name = "cleanup_prompted_at")
  public Instant cleanupPromptedAt;

  @Column(name = "obsolete_at")
  public Instant obsoleteAt;

  /**
   * Why its workspace's daemon refused to start it, as the daemon said (qits-1217, {@code V25});
   * null while no start is refused. Cleared by the next start the daemon accepts.
   */
  @Column(name = "start_refusal", columnDefinition = "text")
  public String startRefusal;

  /** When the refusal in {@link #startRefusal} was first seen; null with it. */
  @Column(name = "start_refused_at")
  public Instant startRefusedAt;

  /**
   * The workspace where a start of this agent was last accepted, so where its worktree lives
   * (qits-1233, {@code V26}); null while no start was recorded. An agent never leaves a FAILED
   * workspace that holds its worktree.
   */
  @Column(name = "started_workspace_id")
  public Long startedWorkspaceId;

  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @Column(name = "removed_at")
  public Instant removedAt;

  public boolean holdsToken() {
    return commissionedTokenId != null && !commissionedTokenId.isBlank();
  }
}
