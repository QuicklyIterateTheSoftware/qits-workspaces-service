package eu.wohlben.qits.workspaces.dto;

import java.time.Instant;
import java.util.List;

/**
 * One agent session that ran in a workspace, as its history record shows it.
 *
 * <p>A listing shape and not a transcript: the lines themselves are fetched per session, because a
 * long session is megabytes of JSONL and a workspace may have several.
 *
 * <p>{@code messageCount} counts conversation turns by the harness's own rule — a {@code
 * user}/{@code assistant} envelope actually carrying text — so the number means the same thing here
 * as it does on a live workspace's agent surface. Counting lines instead would count tool traffic
 * and read several times higher for the same conversation.
 *
 * <p><b>{@code startedAt} and {@code endedAt} are the first and last record that carried a
 * timestamp, and nothing is ever substituted for either.</b> A transcript has no "end" record — the
 * harness simply stops writing — so a session whose container was destroyed mid-run reports the last
 * line that reached the volume, which is the honest answer and not a truncation to hide. In
 * particular {@code endedAt} is never {@code now} and never a stand-in for a value that was not
 * read. It is declared nullable because a session with no timestamped record at all has no span;
 * such a session cannot be attributed to a workspace either (there is nothing to place in the
 * workspace's window), so in practice a session that is reported carries both. Where the two are
 * equal, the last timestamped record genuinely is the first.
 *
 * <p>{@code subagents} may be empty, and each entry's labels may be null — see {@link
 * ArchivedSubagentDto}.
 */
public record ArchivedSessionDto(
    String sessionId,
    Instant startedAt,
    Instant endedAt,
    int messageCount,
    List<ArchivedSubagentDto> subagents) {}
