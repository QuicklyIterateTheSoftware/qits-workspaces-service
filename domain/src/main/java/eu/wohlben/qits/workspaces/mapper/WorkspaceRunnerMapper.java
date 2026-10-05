package eu.wohlben.qits.workspaces.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunnerCapabilities;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * {@link WorkspaceRunner} to {@link WorkspaceRunnerDto}. Written by hand rather than with MapStruct,
 * because most of the answer is read out of the capabilities object rather than copied from a
 * column, and the runner's word is read and never corrected: a key it did not send, or sent in
 * another shape, is null.
 */
@ApplicationScoped
public class WorkspaceRunnerMapper {

  public WorkspaceRunnerDto toDto(WorkspaceRunner runner) {
    if (runner == null) {
      return null;
    }
    JsonNode said = WorkspaceRunnerCapabilities.decode(runner.capabilities);
    return new WorkspaceRunnerDto(
        runner.id,
        runner.name,
        runner.description,
        runner.slots,
        WorkspaceRunnerCapabilities.text(said, WorkspaceRunnerCapabilities.VERSION),
        WorkspaceRunnerCapabilities.text(said, "arch"),
        WorkspaceRunnerCapabilities.text(said, WorkspaceRunnerCapabilities.DOT_CLAUDE_VOLUME),
        login(said),
        runner.registered(),
        runner.registeredAt,
        runner.quarantined(),
        runner.quarantinedAt,
        runner.quarantineReason,
        runner.eligible(),
        runner.lastSeenAt,
        runner.lastHealthCheckAt,
        runner.lastHealthCheckOk,
        runner.createdAt);
  }

  /** {@code login{claude,kimi,checkedAt}}, or null when the runner has not reported one. */
  static WorkspaceRunnerDto.Login login(JsonNode said) {
    JsonNode login = said == null ? null : said.get(WorkspaceRunnerCapabilities.LOGIN);
    if (login == null || !login.isObject()) {
      return null;
    }
    return new WorkspaceRunnerDto.Login(
        WorkspaceRunnerCapabilities.text(login, "claude"),
        WorkspaceRunnerCapabilities.text(login, "kimi"),
        instant(WorkspaceRunnerCapabilities.text(login, "checkedAt")));
  }

  private static Instant instant(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException unparseable) {
      return null;
    }
  }
}
