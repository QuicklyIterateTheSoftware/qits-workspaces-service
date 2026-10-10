package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.error.PayloadTooLargeException;
import eu.wohlben.qits.workspaces.dto.WorkspacePromptAttachmentDataDto;
import eu.wohlben.qits.workspaces.entity.PromptAttachmentSource;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.WorkspacePromptAttachment;
import eu.wohlben.qits.workspaces.persistence.WorkspacePromptAttachmentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Ingest and removal of a workspace's prompt attachments (image rows beside the draft). Like the
 * draft, these are pure host-side data — no container is materialized — so they work on a {@code
 * STOPPED} workspace. Upload decodes the base64 payload, enforces a per-image byte cap (413) and a
 * PNG/JPEG magic-byte sniff (400 for anything else), and stores the <em>sniffed</em> media type —
 * the bytes are the truth, the client's claimed {@code mimeType} is only a hint.
 *
 * <p><b>Only the ingest holds through a postgres cutover</b> ({@link DbRetry#inNewTx} on {@link
 * #addAttachment}). That one is the arrival of bytes nothing else has a copy of. Replacing and
 * removing an attachment act on an image already on screen and already in the database, so a
 * failed one is the same click again — worth less than the review cost of a wider wrapped set. See
 * AGENTS.md, "Surviving a postgres cutover".
 */
@ApplicationScoped
public class WorkspacePromptAttachmentService {

  @Inject AgentResolver agentResolver;

  @Inject WorkspacePromptAttachmentRepository attachmentRepository;

  @Inject WorkspaceChangePublisher changePublisher;

  /** Per-image cap on the decoded bytes; over this yields a 413. */
  @ConfigProperty(name = "qits.workspace.prompt-attachment-max-bytes", defaultValue = "2097152")
  long maxBytes;

  /**
   * Ingests one image attachment. Validates the payload before touching the DB — invalid base64 or
   * a non-PNG/JPEG payload is a 400, an oversized one a 413. The stored {@code mimeType} is the
   * sniffed type (it wins over the claimed one); {@code claimedMimeType} is accepted for symmetry
   * with the client request but only the bytes decide.
   *
   * <p><b>The insert is held through a postgres cutover</b> ({@link DbRetry#inNewTx}). This is a
   * paste: the bytes are in one browser's clipboard buffer and a 500 asks a person to find the
   * screenshot again. Two things make the retry exactly-once rather than "probably once" — the row
   * id is minted <em>before</em> the retried unit, so every attempt writes the same primary key,
   * and the unit flushes, which is what puts the insert on the statement side of the commit line
   * where a lost connection is a certain no-commit. Without the flush an ORM would send it during
   * the commit round trip, and {@code inNewTx} reports that rather than repeating it.
   */
  public WorkspacePromptAttachment addAttachment(
      java.util.UUID id,
      String claimedMimeType,
      String label,
      String source,
      String dataBase64) {
    PromptAttachmentSource parsedSource = parseSource(source);

    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(dataBase64);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Attachment data is not valid base64", e);
    }
    if (bytes.length > maxBytes) {
      throw new PayloadTooLargeException("Attachment exceeds the " + maxBytes + "-byte limit");
    }
    String sniffed = sniffImageType(bytes);
    if (sniffed == null) {
      throw new BadRequestException("Attachment is not a PNG or JPEG image");
    }

    // Minted here rather than inside the retried unit: the id IS the row's identity, so a second
    // attempt must reuse it. Generated per attempt, a retry would be a different row.
    String attachmentId = UUID.randomUUID().toString();

    Attached attached =
        DbRetry.inNewTx(
            "attach image to workspace " + id,
            () -> insertAttachment(id, attachmentId, sniffed, label, parsedSource, bytes));
    // Outside the retried unit — the body is database-only by rule. Fires the attachments-only SSE
    // topic (not prompt-draft) so another open view refreshes its GET-list without every
    // prompt-text autosave re-downloading the image payloads.
    changePublisher.fire(attached.repositoryId(), null, WorkspaceChangeHint.Topic.PROMPT_ATTACHMENTS);
    return attached.attachment();
  }

  /** One attempt of {@link #addAttachment}'s database work: resolve, insert, flush. */
  private Attached insertAttachment(
      java.util.UUID id,
      String attachmentId,
      String mimeType,
      String label,
      PromptAttachmentSource source,
      byte[] bytes) {
    AgentWorktree agent = agentResolver.resolveLive(id);
    WorkspacePromptAttachment attachment = new WorkspacePromptAttachment();
    attachment.id = attachmentId;
    attachment.agentId = agent.id;
    attachment.mimeType = mimeType;
    attachment.label = label;
    attachment.source = source;
    attachment.bytes = bytes;
    attachmentRepository.persist(attachment);
    // The last thing the unit does, and it is what the retry is worth anything for: it moves the
    // insert out of the commit round trip (undecidable, reported) into the statement phase
    // (certainly not committed, retried). It also stamps createdAt, which the caller returns.
    attachmentRepository.flush();
    return new Attached(attachment, agent.repositoryId);
  }

  /**
   * What one retried write attempt hands back: the row, plus the owning repository the change hint
   * outside the transaction needs.
   */
  private record Attached(WorkspacePromptAttachment attachment, String repositoryId) {}

  /**
   * A workspace's attachments (oldest first) with their base64-encoded image payloads, so the
   * compose UI can rehydrate its thumbnail rows on load. Read inside a transaction so the
   * {@code @Lob} bytes are encoded while the session is open. Empty (not 404) when the workspace
   * has none.
   */
  @Transactional
  public List<WorkspacePromptAttachmentDataDto> listAttachments(java.util.UUID id) {
    AgentWorktree agent = agentResolver.resolveLive(id);
    String repoId = agent.repositoryId;
    return attachmentRepository.listByAgentId(agent.id).stream()
        .map(
            a ->
                new WorkspacePromptAttachmentDataDto(
                    a.id,
                    a.mimeType,
                    a.label,
                    a.source,
                    a.createdAt,
                    Base64.getEncoder().encodeToString(a.bytes)))
        .toList();
  }

  /** Returns one attachment scoped to its active workspace, including its raw image bytes. */
  @Transactional
  public WorkspacePromptAttachment getAttachment(java.util.UUID id, String attachmentId) {
    AgentWorktree agent = agentResolver.resolveLive(id);
    return attachmentRepository
        .findByAgentIdAndId(agent.id, attachmentId)
        .orElseThrow(() -> new NotFoundException("Attachment not found: " + attachmentId));
  }

  /** Replaces an attachment's image in place, preserving the id used by durable document URLs. */
  @Transactional
  public WorkspacePromptAttachment updateAttachment(
      java.util.UUID id,
      String attachmentId,
      String claimedMimeType,
      String label,
      String source,
      String dataBase64) {
    PromptAttachmentSource parsedSource = parseSource(source);
    byte[] bytes = decodeAndValidate(dataBase64);
    AgentWorktree agent = agentResolver.resolveLive(id);
    WorkspacePromptAttachment attachment =
        attachmentRepository
            .findByAgentIdAndId(agent.id, attachmentId)
            .orElseThrow(() -> new NotFoundException("Attachment not found: " + attachmentId));
    attachment.mimeType = sniffImageType(bytes);
    attachment.label = label;
    attachment.source = parsedSource;
    attachment.bytes = bytes;
    changePublisher.fire(
        agent.repositoryId, agent.workspaceId, WorkspaceChangeHint.Topic.PROMPT_ATTACHMENTS);
    return attachment;
  }

  /** Removes one attachment scoped to its workspace; 404 if the workspace or the row is unknown. */
  @Transactional
  public void deleteAttachment(java.util.UUID id, String attachmentId) {
    AgentWorktree agent = agentResolver.resolveLive(id);
    String repoId = agent.repositoryId;
    if (!attachmentRepository.deleteByAgentIdAndId(agent.id, attachmentId)) {
      throw new NotFoundException("Attachment not found: " + attachmentId);
    }
    changePublisher.fire(repoId, agent.workspaceId, WorkspaceChangeHint.Topic.PROMPT_ATTACHMENTS);
  }

  private static PromptAttachmentSource parseSource(String source) {
    try {
      return PromptAttachmentSource.valueOf(source.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Unknown attachment source: " + source, e);
    }
  }

  private byte[] decodeAndValidate(String dataBase64) {
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(dataBase64);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Attachment data is not valid base64", e);
    }
    if (bytes.length > maxBytes) {
      throw new PayloadTooLargeException("Attachment exceeds the " + maxBytes + "-byte limit");
    }
    if (sniffImageType(bytes) == null) {
      throw new BadRequestException("Attachment is not a PNG or JPEG image");
    }
    return bytes;
  }

  /**
   * Magic-byte detection for the two accepted image types — {@code image/png} or {@code
   * image/jpeg}, else {@code null}. Signatures mirror {@code artifacts}' {@code MediaTypeSniffer};
   * inlined here so {@code domain} needs no dependency on {@code artifacts} for a two-signature
   * check.
   */
  private static String sniffImageType(byte[] b) {
    if (startsWith(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
      return "image/png";
    }
    if (startsWith(b, 0xFF, 0xD8, 0xFF)) {
      return "image/jpeg";
    }
    return null;
  }

  private static boolean startsWith(byte[] b, int... prefix) {
    if (b == null || b.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if ((b[i] & 0xFF) != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
