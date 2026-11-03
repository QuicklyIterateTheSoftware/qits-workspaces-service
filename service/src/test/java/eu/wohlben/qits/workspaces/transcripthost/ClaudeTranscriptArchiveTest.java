package eu.wohlben.qits.workspaces.transcripthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.ArchivedSessionDto;
import eu.wohlben.qits.workspaces.dto.ArchivedSubagentDto;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The listing and rendering of one agent's directory in the transcript store, against real files in
 * a temp directory.
 *
 * <p><b>Plain JUnit, and that is the point of {@link ClaudeTranscriptArchive} being framework-free.
 * </b> Every case here is about which bytes in a directory become which sessions and lines; none of
 * it needs a web stack, a database or a CDI container, and this module cannot afford the {@code
 * @TestProfile} a {@code @QuarkusTest} would tempt someone into adding — see {@code
 * TestProfileBudgetTest} for the four release requests that died at exit 137 learning that.
 *
 * <p>The fixtures are deliberately hand-built JSONL rather than captured transcripts: what is under
 * test is a rule about turns, timestamps and sidechains, and a captured file would bring several
 * megabytes of irrelevant envelope shapes along with the fields that matter.
 */
class ClaudeTranscriptArchiveTest {

  /** The store root: one directory per agent, as {@link TranscriptStore} lays it out. */
  @TempDir Path root;

  /** The agent's directory under it, {@code <root>/<agentId>}. */
  private Path dir;

  @BeforeEach
  void anAgentDirectory() throws IOException {
    dir = Files.createDirectories(root.resolve("agent-a"));
  }

  private ClaudeTranscriptArchive archive() {
    return ClaudeTranscriptArchive.ofDirectory(dir);
  }

  /** The ordinary case: one session, its span and its turn count. */
  @Test
  void aSessionInTheDirectoryIsListed() throws IOException {
    writeSession(
        "11111111-1111-1111-1111-111111111111",
        line("user", "2026-09-20T11:00:00Z", "do the thing"),
        line("assistant", "2026-09-20T11:00:05Z", "done"));

    List<ArchivedSessionDto> sessions = archive().sessions();

    assertEquals(1, sessions.size());
    ArchivedSessionDto session = sessions.get(0);
    assertEquals("11111111-1111-1111-1111-111111111111", session.sessionId());
    assertEquals(Instant.parse("2026-09-20T11:00:00Z"), session.startedAt());
    assertEquals(Instant.parse("2026-09-20T11:00:05Z"), session.endedAt());
    assertEquals(2, session.messageCount());
  }

  // --- absence -----------------------------------------------------------------------------------

  /**
   * An agent whose daemon never shipped anything has no directory at all. That is an empty answer,
   * not a throw: it is indistinguishable from an agent that never ran, which is why the callers are
   * told not to read it as a fault.
   */
  @Test
  void aMissingDirectoryIsEmptyAndDoesNotThrow() {
    ClaudeTranscriptArchive absent = ClaudeTranscriptArchive.ofDirectory(dir.resolve("never-shipped"));

    assertTrue(absent.sessions().isEmpty());
  }

  /** The same absence on the transcript door, where it is a 404 rather than an empty answer. */
  @Test
  void aMissingDirectoryRefusesATranscriptRatherThanThrowingSomethingElse() {
    ClaudeTranscriptArchive absent = ClaudeTranscriptArchive.ofDirectory(dir.resolve("never-shipped"));

    assertThrows(NotFoundException.class, () -> absent.transcriptOf("any-session-at-all"));
  }

  /** An empty directory is the "no session yet" answer, and is not an error either. */
  @Test
  void anEmptyDirectoryIsEmpty() {
    assertTrue(archive().sessions().isEmpty());
  }

  // --- the session id never reaches a path -------------------------------------------------------

  /**
   * <b>A session id is caller-supplied and is matched against the listing, never joined onto a
   * path.</b> The store holds every agent's transcripts side by side, so a traversal out of this
   * directory reads another agent's. The three ids below are the same refusal — a real session in a
   * sibling directory reached by traversal, a traversal at large, and a name that is not a session
   * at all — because the door must not tell a caller which of those it was.
   */
  @Test
  void aSessionIdTheDirectoryDoesNotListIsRefused() throws IOException {
    Path sibling = Files.createDirectories(root.resolve("agent-b"));
    Files.write(
        sibling.resolve("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.jsonl"),
        List.of(line("user", "2026-09-20T12:00:00Z", "somebody else's")),
        StandardCharsets.UTF_8);

    ClaudeTranscriptArchive archive = archive();
    assertThrows(
        NotFoundException.class,
        () ->
            archive.transcriptOf("../agent-b/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        "another agent's session was served");
    assertThrows(
        NotFoundException.class,
        () -> archive.transcriptOf("../../../.credentials"),
        "a traversal was not refused");
    assertThrows(NotFoundException.class, () -> archive.transcriptOf("no-such-thing"));
  }

  /** A listed id is served: the harness's own lines, verbatim and in order. */
  @Test
  void aListedSessionIsServedVerbatim() throws IOException {
    String sessionId = "12121212-1212-1212-1212-121212121212";
    String first = line("user", "2026-09-20T12:00:00Z", "read it back");
    String second = line("assistant", "2026-09-20T12:00:01Z", "read");
    writeSession(sessionId, first, second);

    assertEquals(List.of(first, second), archive().transcriptOf(sessionId));
  }

  // --- subagents ---------------------------------------------------------------------------------

  /**
   * The marker line is what lets the frontend's parser tell the main conversation from a subagent's
   * sidechain, so it must precede each sidechain's lines — and it is the harness library's own wire
   * string, which is why the assertion is on the literal rather than on a constant this test could
   * change in step with the code.
   */
  @Test
  void eachSidechainIsIntroducedByItsMarkerLine() throws IOException {
    String sessionId = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    writeSession(sessionId, line("user", "2026-09-20T12:00:00Z", "spawn some agents"));
    writeSidechain(
        sessionId, "a1", "Explore", "find the thing", line("assistant", "2026-09-20T12:00:01Z", "found it"));
    writeSidechain(
        sessionId, "a2", "claude", "do the other thing", line("assistant", "2026-09-20T12:00:02Z", "did it"));

    List<String> lines = archive().transcriptOf(sessionId);

    // The main conversation first, verbatim.
    assertEquals(1, lines.stream().filter(l -> l.contains("spawn some agents")).count());
    int firstMarker = indexOfMarker(lines, "a1");
    int secondMarker = indexOfMarker(lines, "a2");
    assertTrue(firstMarker > 0, "no marker line was emitted for the first sidechain");
    assertTrue(secondMarker > firstMarker, "the second sidechain's marker did not follow the first");
    assertTrue(
        lines.get(firstMarker + 1).contains("found it"),
        "the first sidechain's lines did not follow its marker");
    assertTrue(
        lines.get(secondMarker + 1).contains("did it"),
        "the second sidechain's lines did not follow its marker");
    assertTrue(lines.get(firstMarker).contains("\"qits_agent_meta\""));
    assertTrue(lines.get(firstMarker).contains("\"Explore\""));
    assertTrue(lines.get(firstMarker).contains("find the thing"));
  }

  /**
   * <b>The marker must carry {@code toolUseId}, and its absence is the quietest possible bug.</b> It
   * is not a label anybody reads: it names the {@code tool_use} block in the parent turn that
   * spawned the sidechain, and the frontend's parser splices the subagent in directly after that
   * call. With the key missing the parser reads null, finds no anchor, counts the sidechain an
   * orphan and appends it unanchored at the very end of the transcript instead — for <em>every</em>
   * subagent, with a green build, a 200 on the door and nothing in any log. So the assertion is on
   * the emitted line rather than on a method, because the line is the contract.
   */
  @Test
  void theMarkerLineCarriesTheAnchorThatNestsTheSubagentUnderItsCall() throws IOException {
    String sessionId = "1a1a1a1a-1a1a-1a1a-1a1a-1a1a1a1a1a1a";
    writeSession(sessionId, line("user", "2026-09-20T12:00:00Z", "go"));
    writeSidechainWithAnchor(
        sessionId,
        "a1",
        "Explore",
        "sweep the repo",
        "toolu_01abcdef",
        line("assistant", "2026-09-20T12:00:01Z", "swept"));

    List<String> lines = archive().transcriptOf(sessionId);
    String marker = lines.get(indexOfMarker(lines, "a1"));

    assertTrue(
        marker.contains("\"toolUseId\":\"toolu_01abcdef\""),
        "the marker carried no anchor, so the subagent would render unanchored at the end: "
            + marker);
  }

  /**
   * The other half of the anchor's contract: when the sidecar genuinely has no {@code toolUseId} the
   * key is <em>omitted</em>, not emitted as a JSON null. The parser reads the two the same way, but
   * a present null is a claim that the anchor was looked up and found to be nothing, and that is not
   * what happened — and it is the shape the harness library emits, which is the one that matters.
   * The daemon ships no sidecar at all, so this is every shipped sidechain's marker.
   */
  @Test
  void anAbsentAnchorIsOmittedRatherThanEmittedAsNull() throws IOException {
    String sessionId = "2b2b2b2b-2b2b-2b2b-2b2b-2b2b2b2b2b2b";
    writeSession(sessionId, line("user", "2026-09-20T12:00:00Z", "go"));
    writeSidechainWithoutMeta(
        sessionId, "a1", line("assistant", "2026-09-20T12:00:01Z", "no sidecar at all"));

    List<String> lines = archive().transcriptOf(sessionId);
    String marker = lines.get(indexOfMarker(lines, "a1"));

    assertFalse(marker.contains("toolUseId"), "an absent anchor was emitted as a key: " + marker);
    assertTrue(marker.contains("\"qits_agent_meta\""));
  }

  /** The labels ride the listing too, so a session can be described before it is opened. */
  @Test
  void aSubagentsLabelsRideTheListing() throws IOException {
    String sessionId = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    writeSession(sessionId, line("user", "2026-09-20T12:00:00Z", "go"));
    writeSidechain(
        sessionId, "a1", "Explore", "sweep the repo", line("assistant", "2026-09-20T12:00:01Z", "swept"));

    List<ArchivedSubagentDto> subagents = archive().sessions().get(0).subagents();

    assertEquals(1, subagents.size());
    assertEquals("a1", subagents.get(0).agentId());
    assertEquals("Explore", subagents.get(0).agentType());
    assertEquals("sweep the repo", subagents.get(0).description());
    assertEquals(1, subagents.get(0).messageCount());
  }

  /**
   * <b>An absent or JSON-null label is {@code null}, and never the four-character string {@code
   * "null"}.</b> That is not a hypothetical: the harness library's own {@code Json} helper diverges
   * from Jackson precisely here, because Jackson's {@code asText(default)} renders a present-but-null
   * value as {@code "null"} and that is how a literal null once reached a screen as a subagent's
   * description. An empty string would be the same mistake in a quieter costume — a frontend that
   * checks for null would render it as a label that is simply blank.
   */
  @Test
  void aMissingOrNullSubagentMetaGivesNullLabelsAndNotThePlaceholderStrings() throws IOException {
    String sessionId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    writeSession(sessionId, line("user", "2026-09-20T12:00:00Z", "go"));
    // No meta file at all.
    writeSidechainWithoutMeta(sessionId, "a1", line("assistant", "2026-09-20T12:00:01Z", "one"));
    // A meta file present but carrying JSON nulls.
    writeSidechainWithoutMeta(sessionId, "a2", line("assistant", "2026-09-20T12:00:02Z", "two"));
    Files.writeString(
        subagentsDir(sessionId).resolve("agent-a2.meta.json"),
        "{\"agentType\":null,\"description\":null}",
        StandardCharsets.UTF_8);

    List<ArchivedSubagentDto> subagents = archive().sessions().get(0).subagents();

    assertEquals(2, subagents.size());
    for (ArchivedSubagentDto subagent : subagents) {
      assertNull(subagent.agentType(), "a missing label came back as a non-null placeholder");
      assertNull(subagent.description(), "a missing label came back as a non-null placeholder");
    }
    // And the sidechain is still carried: an unlabelled subagent is still a subagent, and dropping
    // it would lose its lines from the transcript.
    List<String> lines = archive().transcriptOf(sessionId);
    assertTrue(lines.stream().anyMatch(l -> l.contains("one")));
    assertTrue(lines.stream().anyMatch(l -> l.contains("two")));
  }

  // --- the message count -------------------------------------------------------------------------

  /**
   * The count is conversation turns by the harness's own rule, not lines. A tool-result carrier and
   * a meta line are envelopes of type {@code user} carrying no text, and counting them would make
   * the number read several times higher than the same session's count on a live workspace.
   */
  @Test
  void theMessageCountCountsTurnsAndNotLines() throws IOException {
    writeSession(
        "ffffffff-ffff-ffff-ffff-ffffffffffff",
        line("user", "2026-09-20T12:00:00Z", "a real turn"),
        // A tool-result carrier: type user, content blocks that are not text.
        "{\"type\":\"user\",\"timestamp\":\"2026-09-20T12:00:01Z\",\"message\":{\"content\":"
            + "[{\"type\":\"tool_result\",\"content\":\"…\"}]}}",
        // A meta line.
        "{\"type\":\"user\",\"isMeta\":true,\"timestamp\":\"2026-09-20T12:00:02Z\",\"message\":"
            + "{\"content\":[{\"type\":\"text\",\"text\":\"system reminder\"}]}}",
        // Not a conversation envelope at all.
        "{\"type\":\"summary\",\"timestamp\":\"2026-09-20T12:00:03Z\",\"summary\":\"a title\"}",
        line("assistant", "2026-09-20T12:00:04Z", "the answer"));

    ArchivedSessionDto session = archive().sessions().get(0);

    assertEquals(2, session.messageCount(), "tool traffic or meta lines were counted as turns");
    // The span is still every timestamped record, turn or not — it is when the session ran.
    assertEquals(Instant.parse("2026-09-20T12:00:04Z"), session.endedAt());
  }

  /**
   * A line that is not JSON is skipped rather than failing the read. A transcript cut mid-write — or
   * a shipped chunk boundary that has not been followed by the rest of its line yet — ends in exactly
   * that, and losing the whole session over its last partial line would lose the part that was
   * written.
   */
  @Test
  void aTruncatedFinalLineDoesNotLoseTheSession() throws IOException {
    writeSession(
        "0a0a0a0a-0a0a-0a0a-0a0a-0a0a0a0a0a0a",
        line("user", "2026-09-20T12:00:00Z", "the turn that landed"),
        "{\"type\":\"assistant\",\"timestamp\":\"2026-09-20T12:00:0");

    List<ArchivedSessionDto> sessions = archive().sessions();

    assertEquals(1, sessions.size());
    assertEquals(1, sessions.get(0).messageCount());
    assertEquals(Instant.parse("2026-09-20T12:00:00Z"), sessions.get(0).endedAt());
  }

  /** Several sessions come back oldest first, so a page renders them in the order they happened. */
  @Test
  void sessionsAreOldestFirst() throws IOException {
    writeSession("0b0b0b0b-0b0b-0b0b-0b0b-0b0b0b0b0b0b", line("user", "2026-09-20T15:00:00Z", "later"));
    writeSession("0c0c0c0c-0c0c-0c0c-0c0c-0c0c0c0c0c0c", line("user", "2026-09-20T11:00:00Z", "earlier"));

    assertEquals(
        List.of("0c0c0c0c-0c0c-0c0c-0c0c-0c0c0c0c0c0c", "0b0b0b0b-0b0b-0b0b-0b0b-0b0b0b0b0b0b"),
        archive().sessions().stream().map(ArchivedSessionDto::sessionId).toList());
  }

  // --- fixtures ----------------------------------------------------------------------------------

  /**
   * {@code <dir>/<sessionId>/subagents}: spelled here as the literal layout rather than through
   * {@link TranscriptStore#SUBAGENTS}, so the test says what is on disk instead of agreeing with
   * the code by construction.
   */
  private Path subagentsDir(String sessionId) {
    return dir.resolve(sessionId).resolve("subagents");
  }

  private void writeSession(String sessionId, String... lines) throws IOException {
    Files.write(dir.resolve(sessionId + ".jsonl"), List.of(lines), StandardCharsets.UTF_8);
  }

  private void writeSidechain(
      String sessionId, String agentId, String agentType, String description, String... lines)
      throws IOException {
    writeSidechainWithAnchor(sessionId, agentId, agentType, description, "toolu_" + agentId, lines);
  }

  private void writeSidechainWithAnchor(
      String sessionId,
      String agentId,
      String agentType,
      String description,
      String toolUseId,
      String... lines)
      throws IOException {
    writeSidechainWithoutMeta(sessionId, agentId, lines);
    Files.writeString(
        subagentsDir(sessionId).resolve("agent-" + agentId + ".meta.json"),
        "{\"agentType\":\""
            + agentType
            + "\",\"description\":\""
            + description
            + "\",\"toolUseId\":\""
            + toolUseId
            + "\"}",
        StandardCharsets.UTF_8);
  }

  private void writeSidechainWithoutMeta(String sessionId, String agentId, String... lines)
      throws IOException {
    Files.createDirectories(subagentsDir(sessionId));
    Files.write(
        subagentsDir(sessionId).resolve("agent-" + agentId + ".jsonl"),
        List.of(lines),
        StandardCharsets.UTF_8);
  }

  /** One conversation envelope. */
  private static String line(String type, String timestamp, String text) {
    return "{\"type\":\""
        + type
        + "\",\"timestamp\":\""
        + timestamp
        + "\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\""
        + text
        + "\"}]}}";
  }

  private static int indexOfMarker(List<String> lines, String agentId) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains("qits_agent_meta") && lines.get(i).contains("\"" + agentId + "\"")) {
        return i;
      }
    }
    return -1;
  }
}
