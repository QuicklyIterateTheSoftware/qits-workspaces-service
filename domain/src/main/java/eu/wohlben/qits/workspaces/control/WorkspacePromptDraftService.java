package eu.wohlben.qits.workspaces.control;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.error.PayloadTooLargeException;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.WorkspacePromptDraft;
import eu.wohlben.qits.workspaces.persistence.WorkspacePromptAttachmentRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspacePromptDraftRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * CRUD for a workspace's persisted prompt draft (one row per workspace). The draft is host-side
 * data only — no container involvement — so it works on a {@code STOPPED} workspace without
 * materializing anything. The {@code content} blob is stored opaquely (the server validates only
 * that it is well-formed JSON within a size cap); {@code serializedPrompt} rides alongside as the
 * server-readable launch-ready markdown.
 *
 * <p><b>The two writes here hold through a postgres cutover</b> ({@link DbRetry#inNewTx}) rather
 * than answering a 500. Both are idempotent by construction — the save is one {@code insert … on
 * conflict} and the discard is a delete of what may not be there — so a second attempt is not a
 * second effect, which is what makes them the safest possible first adopters. See AGENTS.md,
 * "Surviving a postgres cutover".
 */
@ApplicationScoped
public class WorkspacePromptDraftService {

  @Inject AgentResolver agentResolver;

  @Inject WorkspacePromptDraftRepository draftRepository;

  @Inject WorkspacePromptAttachmentRepository attachmentRepository;

  @Inject WorkspaceChangePublisher changePublisher;

  @Inject ObjectMapper objectMapper;

  /**
   * The draft payload is capped here — the combined UTF-8 size of {@code content} and {@code
   * serializedPrompt} over this yields a 413 (both are unbounded {@code @Lob}s, so both count).
   */
  @ConfigProperty(name = "qits.workspace.prompt-draft-max-bytes", defaultValue = "2097152")
  long maxBytes;

  /**
   * Whether this workspace has a draft the {@code taskPrompt} tool would serve — a non-blank {@code
   * serializedPrompt} or at least one attachment. Read-only, container-free; the launch path uses
   * it to decide whether a bootstrap turn is worth pushing (no draft ⇒ nothing to fetch, so the
   * session stays idle).
   */
  @Transactional
  public boolean hasDeliverablePrompt(java.util.UUID id) {
    AgentWorktree agent = agentResolver.resolveLive(id);
    String repoId = agent.repositoryId;
    boolean hasMarkdown =
        draftRepository
            .findByAgentId(agent.id)
            .map(d -> d.serializedPrompt)
            .filter(s -> s != null && !s.isBlank())
            .isPresent();
    return hasMarkdown || !attachmentRepository.listByAgentId(agent.id).isEmpty();
  }

  /** The workspace's current draft, or 404 when none has been saved. */
  public WorkspacePromptDraft getDraft(java.util.UUID id) {
    AgentWorktree agent = agentResolver.resolveLive(id);
    String repoId = agent.repositoryId;
    return draftRepository
        .findByAgentId(agent.id)
        .orElseThrow(() -> new NotFoundException("No prompt draft for agent: " + id));
  }

  /**
   * Idempotent upsert of a workspace's draft. Validates the opaque {@code content} against the size
   * cap (413) and JSON well-formedness (400) before writing; {@code serializedPrompt} is stored
   * verbatim. Returns the persisted entity so the caller can read the fresh {@code updatedAt}.
   *
   * <p><b>The write is held through a cutover rather than 500ing.</b> This is the autosave path —
   * it fires on a debounce while someone types, and a 500 here is composition the browser believed
   * was saved. {@link DbRetry#inNewTx} owns the transaction, so the {@code @Transactional} that
   * used to be here would only get in its way; the validation above stays outside it, because it
   * touches no database and re-running it on every attempt buys nothing.
   */
  public WorkspacePromptDraft saveDraft(java.util.UUID id, String content, String serializedPrompt) {
    // Validate the payload before touching the DB — the cheap in-memory guards fail fast, so a
    // buggy autosave loop of rejected requests costs no repository round-trips.
    long serializedBytes =
        serializedPrompt == null ? 0 : serializedPrompt.getBytes(StandardCharsets.UTF_8).length;
    if (content.getBytes(StandardCharsets.UTF_8).length + serializedBytes > maxBytes) {
      throw new PayloadTooLargeException("Prompt draft exceeds the " + maxBytes + "-byte limit");
    }
    try {
      // readValue (not readTree) so empty/blank input and trailing garbage after a complete value
      // are both rejected — readTree accepts an empty document and stops at the first value.
      objectMapper
          .readerFor(JsonNode.class)
          .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .readValue(content);
    } catch (Exception e) {
      throw new BadRequestException("Prompt draft content is not valid JSON", e);
    }

    Written written =
        DbRetry.inNewTx(
            "save prompt draft " + id, () -> writeDraft(id, content, serializedPrompt));
    // Fired AFTER the retried unit, not inside it: the body is database-only by rule, and a hint
    // emitted per attempt would be one SSE wake-up per lost connection. Notifies other open clients
    // (another device/browser) to rehydrate — they apply the refetched draft only when their local
    // copy is pristine, so this never clobbers mid-typing.
    changePublisher.fire(written.repositoryId(), null, WorkspaceChangeHint.Topic.PROMPT_DRAFT);
    return written.draft();
  }

  /** One attempt of {@link #saveDraft}'s database work: the upsert and the read-back, and no more. */
  private Written writeDraft(java.util.UUID id, String content, String serializedPrompt) {
    AgentWorktree agent = agentResolver.resolveLive(id);
    // Atomic DB-level upsert (`insert … on conflict`) rather than a read-then-insert: the PK *is* the
    // workspace id (shared 1:1 PK/FK), so two concurrent first-saves for a draftless workspace —
    // the exact cross-device flow this feature targets — would both find no row and both insert the
    // same PK, and the loser's insert would 500 on the constraint violation. The upsert serializes
    // them under the row lock (last write wins), so a first-insert race is clean, not a 500.
    // See docs/issues/resolved/2026-07-20_prompt-draft-concurrent-first-insert-500.md.
    // It is also what makes this retriable: `insert … on conflict` executes as a statement, so the
    // write is on the statement side of the commit line and a re-run of it is the same one row.
    draftRepository.upsert(agent.id, content, serializedPrompt);
    // Re-read so the returned entity carries the DB-assigned updatedAt (the value a later GET will
    // return) — the client stores it to dedup its own SSE echo, so it must match byte-for-byte.
    WorkspacePromptDraft draft =
        draftRepository
            .findByAgentId(agent.id)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "No prompt draft for agent: " + agent.id));
    return new Written(draft, agent.repositoryId);
  }

  /**
   * What one retried write attempt hands back: the row, plus the owning repository the change hint
   * outside the transaction needs. The hint cannot re-read it — that would be a second query on a
   * connection the retry just proved is worth being careful with.
   */
  private record Written(WorkspacePromptDraft draft, String repositoryId) {}

  /**
   * Records that this workspace's draft was handed to an agent run — stamps {@code last_run_at},
   * copies the live {@code prompt_version} into {@code last_run_prompt_version}, and records the
   * launched {@code commandId} (which owns the run's session lineage). Fires a {@code PROMPT_DRAFT}
   * hint so open views reflect "handed to the agent". A no-op when the workspace has no draft row.
   * Called from {@code AgentLaunchService} after a launch that delivered the bootstrap turn.
   */
  @Transactional
  public void recordRun(java.util.UUID id, String commandId) {
    AgentWorktree agent = agentResolver.resolveLive(id);
    String repoId = agent.repositoryId;
    draftRepository.recordRun(agent.id, commandId);
    changePublisher.fire(repoId, agent.workspaceId, WorkspaceChangeHint.Topic.PROMPT_DRAFT);
  }

  /**
   * Removes a workspace's draft and its attachment rows (idempotent — a no-op when none exist). The
   * attachments are the draft's payload, so clearing the draft clears them too.
   *
   * <p>Held through a cutover for the same reason as {@link #saveDraft}, and safe for the same one:
   * both statements are "delete this if it is there", so a second attempt deletes nothing twice.
   * The two are deliberately in ONE transaction still — a draft kept with its images gone is a
   * broken composition, and the retry re-runs the pair or neither.
   */
  public void deleteDraft(java.util.UUID id) {
    String repositoryId = DbRetry.inNewTx("discard prompt draft " + id, () -> deleteDraftRows(id));
    changePublisher.fire(repositoryId, null, WorkspaceChangeHint.Topic.PROMPT_DRAFT);
  }

  /** One attempt of {@link #deleteDraft}'s database work. */
  private String deleteDraftRows(java.util.UUID id) {
    AgentWorktree agent = agentResolver.resolveLive(id);
    draftRepository.deleteByAgentId(agent.id);
    attachmentRepository.deleteByAgentId(agent.id);
    return agent.repositoryId;
  }
}
