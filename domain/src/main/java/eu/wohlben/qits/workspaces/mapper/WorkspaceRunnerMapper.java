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

  /**
   * What only the service knows about a runner: whether it is connected (the sockets live there),
   * the pinned runner version, the row counts and the login commands composed from its addresses.
   *
   * @param connected whether it holds a socket right now
   * @param connectedSince since when, without a break; null while it does not
   * @param pinnedVersion the version every runner is upgraded to
   * @param running RUNNING + PROVISIONING rows on it
   * @param owned ACTIVE rows on it
   * @param queued QUEUED rows sticky to it
   * @param loginCommand the Claude login command, or null while it is withheld (the volume is
   *     unknown, or the runner has not yet proven the current workspace image is on its node)
   * @param kimiLoginCommand the Kimi one, likewise
   * @param loginCommandPending whether the commands are withheld for a KNOWN volume: the SPA's cue
   *     that the runner is still fetching the workspace image rather than that nothing was reported
   */
  public record Live(
      boolean connected,
      Instant connectedSince,
      String pinnedVersion,
      int running,
      int owned,
      int queued,
      String loginCommand,
      String kimiLoginCommand,
      boolean loginCommandPending) {

    /** Nothing known beyond the row: not connected, no pin, no counts, no commands, not pending. */
    public static final Live NONE = new Live(false, null, null, 0, 0, 0, null, null, false);
  }

  /** The row alone, as {@link Live#NONE} reads it. */
  public WorkspaceRunnerDto toDto(WorkspaceRunner runner) {
    return toDto(runner, Live.NONE);
  }

  /** The row together with what the service knows live about it. */
  public WorkspaceRunnerDto toDto(WorkspaceRunner runner, Live live) {
    if (runner == null) {
      return null;
    }
    Live known = live == null ? Live.NONE : live;
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
        runner.createdAt,
        known.connected(),
        known.connectedSince(),
        known.pinnedVersion(),
        known.running(),
        known.owned(),
        known.queued(),
        known.loginCommand(),
        known.kimiLoginCommand(),
        known.loginCommandPending());
  }

  /** {@code login{claude,kimi,checkedAt}}, or null when the runner has not reported one. */
  public static WorkspaceRunnerDto.Login login(JsonNode said) {
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
