package eu.wohlben.qits.workspaces.dto;

import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import java.time.Instant;

/**
 * A declared wait as the doors answer it (qits-1153, W8).
 *
 * @param selection qits-eventstream's {@code EventSelection}, in its JSON form
 * @param expiresAt when it times out; null = never
 * @param matchedEventId the event that matched it; null unless MATCHED
 * @param closedAt when it left OPEN; null while OPEN
 */
public record AgentWaitDto(
    String id,
    String agentId,
    String label,
    String selection,
    AgentWaitState state,
    Instant createdAt,
    Instant expiresAt,
    String matchedEventId,
    Instant closedAt) {}
