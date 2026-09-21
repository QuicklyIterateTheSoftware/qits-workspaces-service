package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.dto.ArchivedSessionDto;
import eu.wohlben.qits.workspaces.dto.WorkspaceEventDto;
import eu.wohlben.qits.workspaces.dto.WorkspaceHistoryDetailDto;
import eu.wohlben.qits.workspaces.dto.WorkspaceHistoryDto;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceEvent;
import eu.wohlben.qits.workspaces.persistence.WorkspaceEventRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * Read/edit side of the workspace history: lists all workspaces (active + resolved) for a
 * repository and assembles a single workspace's full record — its narrative and its event timeline,
 * plus the agent transcripts that outlived it. Keyed by the surrogate id, since {@code workspaceId}
 * is reusable once resolved.
 *
 * <p>The record used to promise the commands that ran in the workspace as well. Those commands only
 * ever existed in the container's in-memory store and die with the container, so no implementation
 * of the port that described them could return a non-empty list — a field that cannot be non-empty
 * is a lie in the schema, and it is gone. The agent transcripts are the opposite case and the
 * reason this class still reaches outside the database at all: they are written to a volume the
 * container shares with the whole estate, and that volume survives resolution.
 */
@ApplicationScoped
public class WorkspaceHistoryService {

  @Inject WorkspaceRepository workspaceRepository;

  @Inject WorkspaceEventRepository workspaceEventRepository;

  /**
   * Optional: the transcripts live on a mounted volume, not in this context's store. Absent yields
   * an empty session list rather than an error — a workspace's narrative and timeline are this
   * context's own, and stand without it.
   */
  @Inject Instance<ArchivedAgentTranscripts> archivedTranscripts;

  @Transactional
  public List<WorkspaceHistoryDto> list(String repoId) {
    return workspaceRepository.findByRepositoryId(repoId).stream()
        .map(
            wt ->
                new WorkspaceHistoryDto(
                    wt.id, wt.workspaceId, wt.parent, wt.status, wt.createdAt, wt.resolvedAt))
        .toList();
  }

  @Transactional
  public WorkspaceHistoryDetailDto get(Long id) {
    Workspace workspace = requireWorkspace(id);
    List<WorkspaceEventDto> events =
        workspaceEventRepository.findByWorkspaceOrderByAt(id).stream()
            .map(WorkspaceHistoryService::toEventDto)
            .toList();
    return new WorkspaceHistoryDetailDto(
        workspace.id,
        workspace.workspaceId,
        workspace.parent,
        workspace.status,
        workspace.preamble,
        workspace.result,
        workspace.createdAt,
        workspace.resolvedAt,
        events);
  }

  /**
   * The agent sessions attributed to this workspace, oldest first. An empty list is a valid answer
   * — a workspace where no agent ever ran, one resolved before the shared volume was mounted here,
   * or a deployment with no mount at all — and never an error.
   */
  @Transactional
  public List<ArchivedSessionDto> agentSessions(Long id) {
    Workspace workspace = requireWorkspace(id);
    return archivedTranscripts.isResolvable()
        ? archivedTranscripts.get().sessionsFor(workspace)
        : List.of();
  }

  /**
   * One session's raw JSONL lines. The session id is resolved against what {@link #agentSessions}
   * would report for this workspace, so a session belonging to another workspace answers exactly as
   * one that never existed does: a 404, with nothing of the caller's string reaching a path.
   */
  @Transactional
  public List<String> agentTranscript(Long id, String sessionId) {
    Workspace workspace = requireWorkspace(id);
    if (!archivedTranscripts.isResolvable()) {
      throw new NotFoundException("Agent session not found: " + sessionId);
    }
    List<String> lines = archivedTranscripts.get().transcriptOf(workspace, sessionId);
    // The port raises this itself for an id that does not attribute; this is the same answer for an
    // implementation that returns empty instead. An attributed session always has at least the
    // record that named the branch, so empty can only mean "not one of yours" — and that must be
    // indistinguishable from "never existed", or the door reports what else is on a shared volume.
    if (lines.isEmpty()) {
      throw new NotFoundException("Agent session not found: " + sessionId);
    }
    return lines;
  }

  /** Edit the markdown narrative; null fields are left unchanged. */
  @Transactional
  public WorkspaceHistoryDetailDto updateNarrative(Long id, String preamble, String result) {
    Workspace workspace = requireWorkspace(id);
    if (preamble != null) {
      workspace.preamble = preamble;
    }
    if (result != null) {
      workspace.result = result;
    }
    return get(id);
  }

  /**
   * The history row with this id, resolved or active. No repository is passed in to cross-check:
   * the id identifies the row on its own, and a caller that supplied a mismatched repository was
   * only ever telling us something we could read off the row itself.
   */
  private Workspace requireWorkspace(Long id) {
    Workspace workspace = id == null ? null : workspaceRepository.findById(id);
    if (workspace == null) {
      throw new NotFoundException("Workspace not found: " + id);
    }
    return workspace;
  }

  private static WorkspaceEventDto toEventDto(WorkspaceEvent e) {
    return new WorkspaceEventDto(e.type, e.branch, e.parent, e.target, e.commit, e.note, e.at);
  }
}
