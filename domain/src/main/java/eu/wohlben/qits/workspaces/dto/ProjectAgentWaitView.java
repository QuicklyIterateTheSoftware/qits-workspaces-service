package eu.wohlben.qits.workspaces.dto;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.workspaces.dto.WaitView.SelectionSchema;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A project agent's wait as its door answers it (qits-2596): every field of {@link WaitView}, the
 * agent door's answer, plus the work item it is held for. {@code agentId} is always null — a
 * project agent has no agent row here. A record of its own rather than two more fields on {@link
 * WaitView}, so the agent and desk doors' answers stay as their consumers pinned them.
 */
public record ProjectAgentWaitView(
    String id,
    String agentId,
    String label,
    @Schema(implementation = SelectionSchema.class) JsonNode selection,
    AgentWaitState state,
    Instant createdAt,
    @Schema(description = "When it times out: the timeout it asked for, else the platform's ceiling of 8 h")
        Instant expiresAt,
    String matchedEventId,
    Instant closedAt,
    @Schema(
            description = "What the selection waits on; TIMER for a timer, whose selection is null",
            enumeration = {
              "RELEASE_REQUEST", "CI_RUN", "WORK_ITEM", "COMMENTS", "BRANCH", "OTHER", "TIMER"
            })
        String subjectKind,
    @Schema(description = "The subject's id as the selection names it; null for OTHER and TIMER")
        String subjectRef,
    @Schema(description = "The subject's uuid, once resolved") String subjectId,
    @Schema(description = "The subject's qualified id, once resolved; null for a run")
        String subjectQualifiedId,
    @Schema(description = "The states the selection waits for; empty when it names none")
        List<String> targetStates,
    @Schema(description = "The subject's last known state") String subjectState,
    @Schema(description = "When that state was seen to begin") Instant subjectStateAt,
    @Schema(description = "Runs only: when the run should have finished") Instant subjectExpectedBy,
    @Schema(description = "The last failure to look the subject up") String subjectError,
    @Schema(description = "Why the wait is BROKEN; null in every other state") String brokenReason,
    @Schema(description = "The work item the wait is held for, by its qualified id", example = "qits-123")
        String work,
    @Schema(description = "That work item's uuid") String workId) {

  /** {@code wait} as the project agent's door answers it. */
  public static ProjectAgentWaitView of(AgentWaitDto wait) {
    WaitView view = WaitView.of(wait);
    return new ProjectAgentWaitView(
        view.id(),
        view.agentId(),
        view.label(),
        view.selection(),
        view.state(),
        view.createdAt(),
        view.expiresAt(),
        view.matchedEventId(),
        view.closedAt(),
        view.subjectKind(),
        view.subjectRef(),
        view.subjectId(),
        view.subjectQualifiedId(),
        view.targetStates(),
        view.subjectState(),
        view.subjectStateAt(),
        view.subjectExpectedBy(),
        view.subjectError(),
        view.brokenReason(),
        wait.workQualifiedId(),
        wait.workId());
  }
}
