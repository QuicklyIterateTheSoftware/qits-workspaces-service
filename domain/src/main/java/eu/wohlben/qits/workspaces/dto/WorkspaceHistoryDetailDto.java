package eu.wohlben.qits.workspaces.dto;

import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import java.time.Instant;
import java.util.List;

/**
 * A workspace's full history: its narrative and its event timeline.
 *
 * <p>Everything here survives resolution because it is a database row. The agent transcripts do
 * too, but they live on a shared volume and are fetched per workspace on their own routes rather
 * than inlined — a session is megabytes of JSONL, and this record is what a page loads first.
 *
 * <p>{@code placement} is where the container ran. A RUNNER workspace's transcripts were written on
 * its runner's node, which this service cannot read, so a client says so instead of offering an
 * archive that will be empty (epic qits-624, accepted and deferred).
 */
public record WorkspaceHistoryDetailDto(
    Long id,
    String workspaceId,
    String parent,
    WorkspaceStatus status,
    String preamble,
    String result,
    Instant createdAt,
    Instant resolvedAt,
    List<WorkspaceEventDto> events,
    WorkspacePlacement placement) {}
