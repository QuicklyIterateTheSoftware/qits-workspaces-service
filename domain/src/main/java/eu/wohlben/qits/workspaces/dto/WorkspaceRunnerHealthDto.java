package eu.wohlben.qits.workspaces.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * A workspace runner's newest health check in full, as {@code GET /runners/{id}/health} answers it
 * (qits-850): {@link WorkspaceRunnerDto.Health} with the request it answered and every check's
 * {@code data}. Read out of the capabilities' {@code health} key by {@code WorkspaceRunnerMapper}.
 *
 * @param at when it settled
 * @param ok whether every check passed
 * @param detail the runner's line for a person, or why the check settled without an answer
 * @param requestId the {@code healthCheck} it answered; null for a runner that echoes none
 * @param dataOmitted true when the checks' data was dropped because the report was too large to
 *     keep; their verdicts are all there
 * @param checks each named check, in the runner's order
 */
public record WorkspaceRunnerHealthDto(
    Instant at,
    boolean ok,
    String detail,
    String requestId,
    boolean dataOmitted,
    List<CheckReport> checks) {

  /**
   * One named check in full.
   *
   * @param name the check's stable name
   * @param ok whether it found what it looks for
   * @param detail its line for a person
   * @param data the check's own facts, a JSON object whose shape is the check's (the {@code
   *     nodeInventory} check's containers, volumes and runner container, say); empty when it has
   *     none
   */
  public record CheckReport(
      String name,
      boolean ok,
      String detail,
      JsonNode data) {}
}
