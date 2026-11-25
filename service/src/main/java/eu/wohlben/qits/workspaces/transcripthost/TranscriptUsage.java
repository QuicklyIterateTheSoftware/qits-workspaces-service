package eu.wohlben.qits.workspaces.transcripthost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The Claude usage one transcript line records (qits-2675, epic qits-2521) — the transcript usage
 * rule, which qits-agents-daemon applies to its own agent's files too.
 *
 * <p>Claude Code writes one JSONL line per assistant content block, and every line of one message
 * carries the same {@code message.id} and the same {@code message.usage}. So only a line of {@code
 * "type":"assistant"} with a {@code message.usage} object counts, and a message counts once per
 * session: the first line seen wins ({@link #firstPerMessage}; across chunks and restarts, {@code
 * transcript_usage_message}). A missing number is 0. A sidechain's lines ({@code
 * subagents/agent-*.jsonl}) read the same way as the main file's.
 *
 * <p>Read into a tree, never bound to a type: nothing here needs registering for native-image.
 */
public final class TranscriptUsage {

  private static final Logger LOG = Logger.getLogger(TranscriptUsage.class);

  private static final ObjectMapper JSON = new ObjectMapper();

  private TranscriptUsage() {}

  /**
   * One assistant message's usage.
   *
   * @param at the line's {@code timestamp}; null when absent or unreadable
   * @param cwd the line's working directory; null when absent
   */
  public record Usage(
      String messageId,
      String model,
      long inputTokens,
      long outputTokens,
      long cacheCreationTokens,
      long cacheReadTokens,
      Instant at,
      String cwd) {}

  /** The usage {@code jsonLine} records; empty for any other line, and for one that does not parse. */
  public static Optional<Usage> of(String jsonLine) {
    if (jsonLine == null || jsonLine.isBlank()) {
      return Optional.empty();
    }
    JsonNode line;
    try {
      line = JSON.readTree(jsonLine);
    } catch (Exception notJson) {
      LOG.debugf("Skipped a transcript line that does not parse: %s", notJson.getMessage());
      return Optional.empty();
    }
    if (line == null || !"assistant".equals(text(line, "type"))) {
      return Optional.empty();
    }
    JsonNode message = line.get("message");
    if (message == null || !message.isObject()) {
      return Optional.empty();
    }
    JsonNode usage = message.get("usage");
    String id = text(message, "id");
    if (usage == null || !usage.isObject() || id == null || id.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        new Usage(
            id,
            text(message, "model"),
            number(usage, "input_tokens"),
            number(usage, "output_tokens"),
            number(usage, "cache_creation_input_tokens"),
            number(usage, "cache_read_input_tokens"),
            instant(text(line, "timestamp")),
            text(line, "cwd")));
  }

  /** The usages of {@code lines}, each message once — its first line wins — in first-seen order. */
  public static List<Usage> firstPerMessage(Iterable<String> lines) {
    Map<String, Usage> seen = new LinkedHashMap<>();
    for (String line : lines) {
      of(line).ifPresent(usage -> seen.putIfAbsent(usage.messageId(), usage));
    }
    return List.copyOf(seen.values());
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || !value.isTextual() ? null : value.asText();
  }

  private static long number(JsonNode usage, String field) {
    JsonNode value = usage.get(field);
    return value == null || !value.isNumber() ? 0L : value.asLong();
  }

  private static Instant instant(String timestamp) {
    if (timestamp == null) {
      return null;
    }
    try {
      return Instant.parse(timestamp);
    } catch (DateTimeParseException unreadable) {
      return null;
    }
  }
}
