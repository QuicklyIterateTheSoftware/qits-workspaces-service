package eu.wohlben.qits.workspaces.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A wait as the doors answer it, with what it waits on (epic qits-1681). The subject fields are
 * null for a desk's wait and for a wait declared before they existed; {@code subjectId}, {@code
 * subjectQualifiedId}, {@code subjectExpectedBy} and {@code subjectError} stay null until the
 * subject is looked up.
 *
 * <p>Here and not on the wait door since epic qits-1681: {@link AgentDto#waits} carries the agent's
 * OPEN waits in this same shape, so the listing and the wait doors answer one schema. Its selection
 * is the JSON object, never the stored string.
 */
public record WaitView(
    String id,
    String agentId,
    String label,
    @Schema(implementation = SelectionSchema.class) JsonNode selection,
    AgentWaitState state,
    Instant createdAt,
    Instant expiresAt,
    String matchedEventId,
    Instant closedAt,
    @Schema(
            description = "What the selection waits on",
            enumeration = {"RELEASE_REQUEST", "CI_RUN", "WORK_ITEM", "COMMENTS", "BRANCH", "OTHER"})
        String subjectKind,
    @Schema(description = "The subject's id as the selection names it; null for OTHER")
        String subjectRef,
    @Schema(description = "The subject's uuid, once resolved") String subjectId,
    @Schema(description = "The subject's qualified id, once resolved; null for a run")
        String subjectQualifiedId,
    @Schema(description = "The states the selection waits for; empty when it names none")
        List<String> targetStates,
    @Schema(description = "The subject's last known state") String subjectState,
    @Schema(description = "When that state was seen to begin") Instant subjectStateAt,
    @Schema(description = "Runs only: when the run should have finished") Instant subjectExpectedBy,
    @Schema(description = "The last failure to look the subject up") String subjectError) {

  /** Parses a stored selection; a bare tree, so nothing here needs reflection in a native image. */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** The selection's JSON form, for the openapi document only. */
  @Schema(
      name = "EventSelection",
      description =
          "qits-eventstream's EventSelection: the event name, matched exactly, and `when` groups"
              + " (ORed) of conditions (ANDed). Empty `when` = every event of that name.")
  public record SelectionSchema(
      @Schema(required = true, examples = {"ReleaseRequestStateChanged", "BranchDeliveryChanged"})
          String event,
      List<List<ConditionSchema>> when) {}

  /** One condition, for the openapi document only. */
  @Schema(name = "EventSelectionCondition")
  public record ConditionSchema(
      @Schema(required = true, description = "Dot path; `name[]` = some element of the array")
          String path,
      @Schema(required = true, enumeration = {"exact", "prefix", "exists"}) String op,
      @Schema(
              description = "A string for exact and prefix; true or false for exists (false = absent)",
              type = SchemaType.STRING)
          Object value) {}

  /** {@code wait} as the doors answer it; an unreadable selection is answered as its text. */
  public static WaitView of(AgentWaitDto wait) {
    JsonNode selection;
    try {
      selection = JSON.readTree(wait.selection());
    } catch (Exception unreadable) {
      selection = JsonNodeFactory.instance.textNode(wait.selection());
    }
    return new WaitView(
        wait.id(),
        wait.agentId(),
        wait.label(),
        selection,
        wait.state(),
        wait.createdAt(),
        wait.expiresAt(),
        wait.matchedEventId(),
        wait.closedAt(),
        wait.subjectKind(),
        wait.subjectRef(),
        wait.subjectId(),
        wait.subjectQualifiedId(),
        wait.targetStates(),
        wait.subjectState(),
        wait.subjectStateAt(),
        wait.subjectExpectedBy(),
        wait.subjectError());
  }
}
