package eu.wohlben.qits.workspaces.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A single image attached to a workspace's prompt draft — a sketch export or a pasted screenshot,
 * stored as its own row (n:1 with the workspace) rather than base64 inside the draft's opaque
 * {@code content} blob. Keeping the bytes here keeps the blob small, lets the server enforce a
 * per-image cap and a PNG/JPEG magic-byte sniff at upload time, and is what a later step's {@code
 * taskPrompt} MCP tool turns into {@code ImageContent} blocks. The opaque blob references these
 * rows by {@link #id} only.
 *
 * <p>The {@code workspace_id_fk} FK is {@code on delete cascade}, but the workspace row is only
 * soft-deleted, so that cascade never fires in practice — {@code WorkspaceService} (and {@code
 * WorkspacePromptDraftService.deleteDraft}) delete these rows explicitly, same as {@link
 * WorkspacePromptDraft}.
 *
 * <p>{@code @Uncaused} by decision, following the draft it is the payload of. These rows are
 * pre-launch composition state rather than a durable record: they arrive one browser paste at a
 * time through {@code WorkspacePromptAttachmentController} — the only writer, and one with no
 * machine caller that could carry an {@code X-Qits-Causation-Id} — and they are deleted wholesale
 * with the draft and on {@code WorkspaceResolved}. A column that only a human's browser could fill,
 * on a row that does not outlive the composition, buys nothing the draft does not already decline.
 */
@Entity
@Table(name = "agent_prompt_attachment")
@Uncaused
public class WorkspacePromptAttachment extends PanacheEntityBase {

  /** A service-generated {@code UUID.randomUUID().toString()} — the blob references it verbatim. */
  @Id public String id;

  /** The owning {@link Workspace}'s surrogate {@code id}. */
  @Column(name = "agent_id", nullable = false)
  public java.util.UUID agentId;

  /** The effective media type — the sniffed type, which wins over the client's claim. */
  @Column(name = "mime_type", nullable = false)
  public String mimeType;

  /** A human label the composing UI shows ("Sketch 1", "Pasted image 1"). */
  @Column(nullable = false)
  public String label;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public PromptAttachmentSource source;

  /** The raw image bytes (already base64-decoded), served to the agent as an image block. */
  /*
   * `text`/`bytea` via columnDefinition, and NOT @Lob — the one entity mapping the move to postgres
   * had to change. On H2 a @Lob String was a clob and the two agreed; on postgres @Lob means a LARGE
   * OBJECT, so Hibernate binds an oid and the insert fails against the column V1 declares. Unbounded
   * either way, which is what these fields need.
   */
  @Column(columnDefinition = "bytea", nullable = false)
  public byte[] bytes;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
