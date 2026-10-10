package eu.wohlben.qits.workspaces.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A workspace's persisted prompt-composition draft — one row per workspace, rehydrated on load so a
 * half-built prompt survives a refresh (and follows the operator across devices). The draft is
 * <em>semi-opaque</em>: {@link #content} is a JSON document the composing UI owns and the server
 * never interprets, while {@link #serializedPrompt} is the launch-ready markdown the client already
 * produced — the server-readable part a later step's {@code taskPrompt} MCP tool serves to the
 * agent.
 *
 * <p>The primary key <em>is</em> the owning {@link Workspace}'s {@code id} ({@code
 * workspace_id_fk}, a shared PK/FK): the draft is strictly 1:1 with a workspace, so no separate
 * generated id is needed. The workspace row is soft-deleted, so the on-delete-cascade FK never
 * fires in the normal discard flow — {@code WorkspaceService.doDiscard} deletes this row
 * explicitly.
 *
 * <p>{@code @Uncaused} by decision, twice over. No row here is ever written through Hibernate:
 * {@code WorkspacePromptDraftRepository.upsert} is a native {@code insert … on conflict}, so
 * {@code @PrePersist} — and with it the {@code CausationStamp} — cannot fire at all. And the row is
 * an updatable singleton, rewritten by every autosave, so even a hand-set value would name one
 * edit and then misname every one after it.
 */
@Entity
@Table(name = "agent_prompt_draft")
@Uncaused
public class WorkspacePromptDraft extends PanacheEntityBase {

  /** Shares the owning {@link Workspace}'s {@code id} — this is both the PK and the FK. */
  @Id
  @Column(name = "agent_id")
  public java.util.UUID agentId;

  /**
   * Opaque composition JSON (picks, references, canvas, chat draft, …), never read by the server.
   */
  /*
   * `text`/`bytea` via columnDefinition, and NOT @Lob — the one entity mapping the move to postgres
   * had to change. On H2 a @Lob String was a clob and the two agreed; on postgres @Lob means a LARGE
   * OBJECT, so Hibernate binds an oid and the insert fails against the column V1 declares. Unbounded
   * either way, which is what these fields need.
   */
  @Column(columnDefinition = "text", nullable = false)
  public String content;

  /** The launch-ready markdown the client serialized — served verbatim to the agent, nullable. */
  @Column(name = "serialized_prompt", columnDefinition = "text")
  public String serializedPrompt;

  /**
   * A monotonic counter bumped by every content-changing {@code upsert}. It names the exact draft
   * state a launch handed to the agent (recorded in {@link #lastRunPromptVersion}), so the Agents
   * tab can tell an un-run edit from an already-delivered one and not silently re-run it on
   * re-attach. Distinct from {@link #updatedAt} (a wall-clock timestamp two rapid saves could
   * share) — this is a strict integer ordering.
   */
  @Column(name = "prompt_version", nullable = false)
  public long promptVersion;

  /** When a bootstrap turn was last delivered from this draft to an agent run; null until then. */
  @Column(name = "last_run_at")
  public Instant lastRunAt;

  /** The {@link #promptVersion} that was delivered on the last run; null until the first run. */
  @Column(name = "last_run_prompt_version")
  public Long lastRunPromptVersion;

  /**
   * The launched {@code Command} id of the last run — it owns the agent-session lineage (its
   * session refs carry the {@code sessionId}), so this links a composed prompt version to the
   * session that ran it. Null until the first run.
   */
  @Column(name = "last_run_command_id")
  public String lastRunCommandId;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
