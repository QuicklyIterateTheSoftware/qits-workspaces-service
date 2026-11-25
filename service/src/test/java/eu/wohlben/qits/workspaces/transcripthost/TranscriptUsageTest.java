package eu.wohlben.qits.workspaces.transcripthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.transcripthost.TranscriptUsage.Usage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The transcript usage rule (qits-2675): which lines count, a message once by its id, the fields
 * read, a missing number as 0, and a line that does not parse skipped. Plain JUnit over literal
 * lines in Claude Code's own shape.
 */
class TranscriptUsageTest {

  /** One content block of an assistant message, as Claude Code writes it. */
  static String assistant(String id, String model, String usage, String block) {
    return "{\"type\":\"assistant\",\"timestamp\":\"2026-11-25T10:00:00.123Z\","
        + "\"cwd\":\"/workspace/agents/a/qits-qits\",\"sessionId\":\"s\","
        + "\"message\":{\"id\":\"" + id + "\",\"type\":\"message\",\"role\":\"assistant\","
        + "\"model\":\"" + model + "\",\"content\":[" + block + "],"
        + "\"usage\":" + usage + "}}";
  }

  static final String FULL_USAGE =
      "{\"input_tokens\":12,\"output_tokens\":340,\"cache_creation_input_tokens\":7,"
          + "\"cache_read_input_tokens\":51234,\"service_tier\":\"standard\"}";

  @Test
  void anAssistantLineYieldsItsMessagesUsage() {
    Usage usage =
        TranscriptUsage.of(assistant("msg_1", "claude-opus-5-5", FULL_USAGE, "{\"type\":\"text\"}"))
            .orElseThrow();

    assertEquals("msg_1", usage.messageId());
    assertEquals("claude-opus-5-5", usage.model());
    assertEquals(12, usage.inputTokens());
    assertEquals(340, usage.outputTokens());
    assertEquals(7, usage.cacheCreationTokens());
    assertEquals(51234, usage.cacheReadTokens());
    assertEquals(Instant.parse("2026-11-25T10:00:00.123Z"), usage.at());
    assertEquals("/workspace/agents/a/qits-qits", usage.cwd());
  }

  @Test
  void aMissingNumberCountsAsZero() {
    Usage usage =
        TranscriptUsage.of(assistant("msg_2", "claude-opus-5-5", "{\"output_tokens\":5}", "{}"))
            .orElseThrow();

    assertEquals(0, usage.inputTokens());
    assertEquals(5, usage.outputTokens());
    assertEquals(0, usage.cacheCreationTokens());
    assertEquals(0, usage.cacheReadTokens());
  }

  @Test
  void onlyAnAssistantLineWithAUsageObjectCounts() {
    assertTrue(TranscriptUsage.of("{\"type\":\"user\",\"message\":{\"id\":\"msg_3\",\"usage\":{}}}").isEmpty());
    assertTrue(
        TranscriptUsage.of("{\"type\":\"assistant\",\"message\":{\"id\":\"msg_4\",\"model\":\"m\"}}")
            .isEmpty());
    assertTrue(TranscriptUsage.of("{\"type\":\"assistant\",\"message\":{\"usage\":{}}}").isEmpty());
    assertTrue(TranscriptUsage.of("{\"type\":\"summary\",\"summary\":\"x\"}").isEmpty());
  }

  @Test
  void aGarbageLineIsSkipped() {
    assertTrue(TranscriptUsage.of("{\"type\":\"assistant\",\"message\":{\"id\":").isEmpty());
    assertTrue(TranscriptUsage.of("not json at all").isEmpty());
    assertTrue(TranscriptUsage.of("").isEmpty());
    assertTrue(TranscriptUsage.of(null).isEmpty());

    List<Usage> usages =
        TranscriptUsage.firstPerMessage(
            List.of("garbage{", assistant("msg_5", "claude-opus-5-5", FULL_USAGE, "{}"), "[1,2"));
    assertEquals(List.of("msg_5"), usages.stream().map(Usage::messageId).toList());
  }

  /** Every content block of one message carries its id and usage: the message counts once. */
  @Test
  void aMessageCountsOnceByItsIdAndItsFirstLineWins() {
    String text = assistant("msg_6", "claude-opus-5-5", FULL_USAGE, "{\"type\":\"text\"}");
    String toolUse =
        assistant(
            "msg_6",
            "claude-opus-5-5",
            FULL_USAGE.replace("\"output_tokens\":340", "\"output_tokens\":999"),
            "{\"type\":\"tool_use\"}");
    String other = assistant("msg_7", "claude-sonnet-5", "{\"input_tokens\":1}", "{}");

    List<Usage> usages = TranscriptUsage.firstPerMessage(List.of(text, toolUse, other, text));

    assertEquals(List.of("msg_6", "msg_7"), usages.stream().map(Usage::messageId).toList());
    assertEquals(340, usages.get(0).outputTokens());
    assertEquals("claude-sonnet-5", usages.get(1).model());
  }

  /** A sidechain's line ({@code subagents/agent-*.jsonl}) reads exactly as the main file's. */
  @Test
  void aSidechainLineReadsTheSameWay() {
    String sidechain =
        "{\"parentUuid\":\"p\",\"isSidechain\":true,\"agentId\":\"a1b2c3\",\"type\":\"assistant\","
            + "\"timestamp\":\"2026-11-25T11:00:00Z\",\"message\":{\"id\":\"msg_8\","
            + "\"model\":\"claude-haiku-5\",\"usage\":{\"input_tokens\":3,\"output_tokens\":4}}}";

    Usage usage = TranscriptUsage.of(sidechain).orElseThrow();

    assertEquals("msg_8", usage.messageId());
    assertEquals("claude-haiku-5", usage.model());
    assertEquals(3, usage.inputTokens());
    assertEquals(4, usage.outputTokens());
    assertNull(usage.cwd());
  }
}
