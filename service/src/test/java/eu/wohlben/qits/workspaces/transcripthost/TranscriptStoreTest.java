package eu.wohlben.qits.workspaces.transcripthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.ArchivedSessionDto;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The transcript store (qits-1752): the append rule that is the whole delivery guarantee, the
 * address grammar that keeps a wire string from becoming an arbitrary path, and the read back
 * through {@link ClaudeTranscriptArchive#ofDirectory} — plain JUnit against a temp directory, as
 * {@link ClaudeTranscriptArchiveTest} is, and for its reason.
 */
class TranscriptStoreTest {

  private static final UUID AGENT = UUID.fromString("0f0f0f0f-0000-4000-8000-000000000001");

  private static final String SESSION = "11111111-1111-1111-1111-111111111111";

  @TempDir Path root;

  private TranscriptStore store() {
    return new TranscriptStore(root);
  }

  private static String turn(String type, String at, String text) {
    return "{\"type\":\""
        + type
        + "\",\"timestamp\":\""
        + at
        + "\",\"message\":{\"content\":\""
        + text
        + "\"}}\n";
  }

  private static long bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  // --- the append rule ---------------------------------------------------------------------------

  /** A chunk that starts where the store ends is appended, at the harness's own layout. */
  @Test
  void aChunkAtTheHeldSizeIsAppended() throws IOException {
    String first = turn("user", "2026-10-30T10:00:00Z", "go");
    String second = turn("assistant", "2026-10-30T10:00:05Z", "gone");

    TranscriptStore.Appended a = store().append(AGENT, SESSION, "main", 0, first);
    TranscriptStore.Appended b = store().append(AGENT, SESSION, "main", bytes(first), second);

    assertTrue(a.appended());
    assertTrue(b.appended());
    assertEquals(bytes(first) + bytes(second), b.size());
    Path file = root.resolve(AGENT.toString()).resolve(SESSION + ".jsonl");
    assertEquals(first + second, Files.readString(file));
  }

  /**
   * A duplicate — the same chunk again, or a restarted daemon starting its cursor at 0 — is not
   * appended twice; the answer is the size held, which the caller resyncs the daemon to.
   */
  @Test
  void aChunkBelowTheHeldSizeIsRefusedWithTheSizeToResyncTo() throws IOException {
    String first = turn("user", "2026-10-30T10:00:00Z", "go");
    store().append(AGENT, SESSION, "main", 0, first);

    TranscriptStore.Appended again = store().append(AGENT, SESSION, "main", 0, first);

    assertFalse(again.appended());
    assertEquals(bytes(first), again.size());
    assertEquals(first, Files.readString(root.resolve(AGENT.toString()).resolve(SESSION + ".jsonl")));
  }

  /** A gap — a frame lost to a reconnect — is not papered over: nothing is written, and resync. */
  @Test
  void aChunkBeyondTheHeldSizeIsRefusedAndNothingIsWritten() throws IOException {
    TranscriptStore.Appended gap =
        store().append(AGENT, SESSION, "main", 42, turn("user", "2026-10-30T10:00:00Z", "late"));

    assertFalse(gap.appended());
    assertEquals(0, gap.size());
    assertFalse(Files.exists(root.resolve(AGENT.toString()).resolve(SESSION + ".jsonl")));
  }

  /**
   * One line longer than the daemon's chunk cap arrives as consecutive chunks split at a character
   * boundary, the first ending mid-line. Offsets count UTF-8 bytes, so a multi-byte character before
   * the split must move the second chunk's offset by its byte length, not its char count.
   */
  @Test
  void aLineSplitAcrossChunksIsReassembledByByteOffset() throws IOException {
    String head = "{\"type\":\"user\",\"message\":{\"content\":\"größer ";
    String tail = "als ein chunk\"}}\n";

    assertTrue(store().append(AGENT, SESSION, "main", 0, head).appended());
    assertFalse(
        store().append(AGENT, SESSION, "main", head.length(), tail).appended(),
        "a char offset was taken for a byte offset");
    assertTrue(store().append(AGENT, SESSION, "main", bytes(head), tail).appended());

    assertEquals(
        head + tail,
        Files.readString(root.resolve(AGENT.toString()).resolve(SESSION + ".jsonl")));
  }

  /** A sidechain lands under the session's own directory, held and resynced on its own. */
  @Test
  void aSidechainHasItsOwnFileAndItsOwnSize() throws IOException {
    String line = turn("assistant", "2026-10-30T10:00:01Z", "found it");

    store().append(AGENT, SESSION, "subagents/agent-a1.jsonl", 0, line);

    Path sidechain =
        root.resolve(AGENT.toString()).resolve(SESSION).resolve("subagents").resolve("agent-a1.jsonl");
    assertEquals(line, Files.readString(sidechain));
    // The main file holds nothing, whatever its sidechain holds.
    assertEquals(0, store().append(AGENT, SESSION, "main", 5, "x").size());
  }

  // --- nothing from the wire becomes a path unvalidated ------------------------------------------

  @Test
  void aSessionIdOrFileOutsideTheGrammarIsRefusedBeforeAnythingIsWritten() {
    for (String session : new String[] {"../escape", "a/b", "..", ".hidden", "", null}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> store().append(AGENT, session, "main", 0, "x\n"),
          "session id " + session);
    }
    for (String file :
        new String[] {
          "../main", "subagents/../../x.jsonl", "subagents/agent-a1.meta.json", "agent-a1.jsonl",
          "/etc/passwd", "", null
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> store().append(AGENT, SESSION, file, 0, "x\n"),
          "file " + file);
    }
    assertThrows(
        IllegalArgumentException.class, () -> store().append(null, SESSION, "main", 0, "x\n"));
    assertThrows(
        IllegalArgumentException.class, () -> store().append(AGENT, SESSION, "main", -1, "x\n"));
    assertTrue(isEmpty(root), "a refused chunk left something behind");
  }

  // --- read back ---------------------------------------------------------------------------------

  /**
   * What the store holds reads exactly like a mounted session: listed with its span and its count of
   * conversation turns, oldest first, every session in the agent's directory belonging.
   */
  @Test
  void storedSessionsAreListedWithTheirSpanAndTurnCount() throws IOException {
    String older = "22222222-2222-2222-2222-222222222222";
    write(older, turn("user", "2026-10-29T09:00:00Z", "yesterday"));
    write(
        SESSION,
        "{\"type\":\"queue-operation\",\"timestamp\":\"2026-10-30T10:00:00Z\"}\n"
            + turn("user", "2026-10-30T10:00:01Z", "go")
            + turn("assistant", "2026-10-30T10:00:09Z", "done"));

    List<ArchivedSessionDto> sessions =
        ClaudeTranscriptArchive.ofDirectory(store().agentDirectory(AGENT)).sessions();

    assertEquals(List.of(older, SESSION), sessions.stream().map(ArchivedSessionDto::sessionId).toList());
    ArchivedSessionDto session = sessions.get(1);
    assertEquals(Instant.parse("2026-10-30T10:00:00Z"), session.startedAt());
    assertEquals(Instant.parse("2026-10-30T10:00:09Z"), session.endedAt());
    assertEquals(2, session.messageCount());
  }

  /** The transcript: the main lines, then each sidechain behind its synthetic marker line. */
  @Test
  void aStoredTranscriptIsMainLinesThenEachSidechainBehindItsMarker() throws IOException {
    String main = turn("user", "2026-10-30T10:00:00Z", "spawn");
    write(SESSION, main);
    store().append(AGENT, SESSION, "subagents/agent-a1.jsonl", 0, turn("assistant", "2026-10-30T10:00:01Z", "one"));
    store().append(AGENT, SESSION, "subagents/agent-a2.jsonl", 0, turn("assistant", "2026-10-30T10:00:02Z", "two"));

    ClaudeTranscriptArchive archive = ClaudeTranscriptArchive.ofDirectory(store().agentDirectory(AGENT));
    List<String> lines = archive.transcriptOf(SESSION);

    assertEquals(5, lines.size(), lines.toString());
    assertEquals(main.strip(), lines.get(0));
    assertEquals("{\"type\":\"qits_agent_meta\",\"agentId\":\"a1\"}", lines.get(1));
    assertTrue(lines.get(2).contains("\"one\""));
    assertEquals("{\"type\":\"qits_agent_meta\",\"agentId\":\"a2\"}", lines.get(3));
    assertTrue(lines.get(4).contains("\"two\""));
    assertEquals(2, archive.sessions().get(0).subagents().size());
  }

  /** A requested id is resolved against the listing, never joined onto a path. */
  @Test
  void anUnlistedSessionIdIsNotFound() throws IOException {
    write(SESSION, turn("user", "2026-10-30T10:00:00Z", "go"));
    ClaudeTranscriptArchive archive = ClaudeTranscriptArchive.ofDirectory(store().agentDirectory(AGENT));

    assertThrows(NotFoundException.class, () -> archive.transcriptOf("never-shipped"));
    assertThrows(NotFoundException.class, () -> archive.transcriptOf("../" + AGENT + "/" + SESSION));
    // Another agent's directory is another archive: nothing of this one is in it.
    assertTrue(
        ClaudeTranscriptArchive.ofDirectory(store().agentDirectory(UUID.randomUUID()))
            .sessions()
            .isEmpty());
  }

  /** The mounted volume holds everybody's sessions, so it is never listed whole. */
  @Test
  void theMountedArchiveRefusesToBeListedWhole() {
    assertThrows(IllegalStateException.class, () -> new ClaudeTranscriptArchive(root).sessions());
  }

  private void write(String sessionId, String text) throws IOException {
    assertTrue(store().append(AGENT, sessionId, "main", 0, text).appended());
  }

  private static boolean isEmpty(Path dir) {
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.findAny().isEmpty();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }
}
