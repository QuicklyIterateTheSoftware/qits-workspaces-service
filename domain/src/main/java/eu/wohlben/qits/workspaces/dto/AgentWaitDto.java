package eu.wohlben.qits.workspaces.dto;

import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import java.time.Instant;
import java.util.List;

/**
 * A declared wait as the doors answer it (qits-1153, W8), with its subject (epic qits-1681, {@code
 * V33}). Every subject field is null for a desk's wait and for a wait declared before {@code V33}.
 *
 * @param selection qits-eventstream's {@code EventSelection}, in its JSON form
 * @param expiresAt when it times out; null = never
 * @param matchedEventId the event that matched it; null unless MATCHED
 * @param closedAt when it left OPEN; null while OPEN
 * @param subjectKind {@code RELEASE_REQUEST}, {@code CI_RUN}, {@code WORK_ITEM}, {@code COMMENTS}
 *     or {@code OTHER}
 * @param subjectRef the value the selection names, as written; null for OTHER
 * @param subjectId the subject's uuid, once resolved
 * @param subjectQualifiedId the subject's qualified id, once resolved; null for a run
 * @param targetStates the states the selection waits for; empty when it names none
 * @param subjectState the subject's last known state
 * @param subjectStateAt when that state was seen to begin
 * @param subjectExpectedBy runs only: when the run should have finished
 * @param subjectError the last failure to look the subject up
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
    Instant closedAt,
    String subjectKind,
    String subjectRef,
    String subjectId,
    String subjectQualifiedId,
    List<String> targetStates,
    String subjectState,
    Instant subjectStateAt,
    Instant subjectExpectedBy,
    String subjectError) {}
