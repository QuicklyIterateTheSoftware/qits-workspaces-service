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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The attribution rule, against real files in a temp directory.
 *
 * <p><b>Plain JUnit, and that is the point of {@link ClaudeTranscriptArchive} being framework-free.
 * </b> Every case here is about which bytes on a volume belong to which workspace; none of it needs
 * a web stack, a database or a CDI container, and this module cannot afford the {@code @TestProfile}
 * a {@code @QuarkusTest} would tempt someone into adding — see {@code TestProfileBudgetTest} for the
 * four release requests that died at exit 137 learning that. The adapter over this class does
 * nothing but read one config key, so there is nothing left for a booted test to prove.
 *
 * <p>The fixtures are deliberately hand-built JSONL rather than captured transcripts: what is under
 * test is a rule about {@code gitBranch} and timestamps, and a captured file would bring several
 * megabytes of irrelevant envelope shapes along with the two fields that matter.
 */
class ClaudeTranscriptArchiveTest {

  private static final String BRANCH = "ticket/a-resolved-workspace";

  /** The workspace's life, and the window every case below is placed relative to. */
  private static final Instant CREATED = Instant.parse("2026-09-20T10:00:00Z");

  private static final Instant RESOLVED = Instant.parse("2026-09-20T18:00:00Z");

  @TempDir Path root;

  private ClaudeTranscriptArchive archive() {
    return new ClaudeTranscriptArchive(root);
  }

  private static ClaudeTranscriptArchive.Attribution workspace() {
    return new ClaudeTranscriptArchive.Attribution(BRANCH, CREATED, RESOLVED);
  }

  // --- the rule ----------------------------------------------------------------------------------

  /** The ordinary case: the branch matches and the session started inside the workspace's life. */
  @Test
  void aSessionOnTheBranchAndInsideTheWindowIsAttributed() throws IOException {
    writeSession(
        "11111111-1111-1111-1111-111111111111",
        line("user", "2026-09-20T11:00:00Z", BRANCH, "do the thing"),
        line("assistant", "2026-09-20T11:00:05Z", BRANCH, "done"));

    List<ArchivedSessionDto> sessions = archive().sessionsFor(workspace());

    assertEquals(1, sessions.size());
    ArchivedSessionDto session = sessions.get(0);
    assertEquals("11111111-1111-1111-1111-111111111111", session.sessionId());
    assertEquals(Instant.parse("2026-09-20T11:00:00Z"), session.startedAt());
    assertEquals(Instant.parse("2026-09-20T11:00:05Z"), session.endedAt());
    assertEquals(2, session.messageCount());
  }

  /**
   * <b>The branch-reuse case, and the subtlest rule in the class.</b> A branch is reusable once its
   * workspace resolves, so two workspace rows can legitimately carry the same branch name at
   * different times. Branch equality alone would hand the second workspace the first one's
   * conversation — somebody else's work, under this workspace's heading — so the window is what
   * makes the branch mean anything. Both files below are on the right branch; only the one that
   * started inside the window is this workspace's.
   */
  @Test
  void aSameBranchSessionOutsideTheWindowIsExcluded() throws IOException {
    // The previous workspace on this branch: finished before this one was created.
    writeSession(
        "22222222-2222-2222-2222-222222222222",
        line("user", "2026-09-19T09:00:00Z", BRANCH, "an earlier workspace's turn"));
    // The next workspace on this branch: started after this one resolved.
    writeSession(
        "33333333-3333-3333-3333-333333333333",
        line("user", "2026-09-21T09:00:00Z", BRANCH, "a later workspace's turn"));
    // Ours.
    writeSession(
        "44444444-4444-4444-4444-444444444444",
        line("user", "2026-09-20T12:00:00Z", BRANCH, "ours"));

    List<ArchivedSessionDto> sessions = archive().sessionsFor(workspace());

    assertEquals(
        List.of("44444444-4444-4444-4444-444444444444"),
        sessions.stream().map(ArchivedSessionDto::sessionId).toList(),
        "a session on the same branch from another workspace's lifetime was attributed here");
  }

  /**
   * A project agent container's checkout is detached, so its sessions record {@code
   * "gitBranch":"HEAD"}. They share this volume with every workspace and belong to none of them.
   *
   * <p>The second half is what makes this a test of the {@code HEAD} rule rather than of branch
   * equality: against an ordinary workspace the first assertion would hold with the rule deleted,
   * because {@code "HEAD"} is not the branch. So the same file is asked for by a workspace whose
   * branch <em>is</em> the string {@code HEAD}, which is the only arrangement that can tell the two
   * conditions apart. Such a row cannot exist today — branch names are slug-validated — and that is
   * the point: the guard is stated so it survives a validator changing shape, and a test that could
   * not fail would not keep it.
   */
  @Test
  void aDetachedHeadSessionIsExcluded() throws IOException {
    writeSession(
        "55555555-5555-5555-5555-555555555555",
        line("user", "2026-09-20T12:00:00Z", "HEAD", "a project agent's turn"));

    assertTrue(
        archive().sessionsFor(workspace()).isEmpty(),
        "a detached-HEAD session from a project agent container was attributed to a workspace");
    assertTrue(
        archive()
            .sessionsFor(new ClaudeTranscriptArchive.Attribution("HEAD", CREATED, RESOLVED))
            .isEmpty(),
        "HEAD was matched as an ordinary branch name rather than refused outright");
  }

  /**
   * Transcripts commonly open with harness bookkeeping that names no branch at all. Taking the first
   * line's {@code gitBranch} would attribute nothing; the lookahead is what finds the first record
   * that does carry one.
   */
  @Test
  void aSessionWhoseFirstRecordsCarryNoBranchStillAttributesViaTheLookahead() throws IOException {
    writeSession(
        "66666666-6666-6666-6666-666666666666",
        "{\"type\":\"queue-operation\"}",
        "{\"type\":\"queue-operation\"}",
        "{\"type\":\"summary\",\"summary\":\"a title\"}",
        line("user", "2026-09-20T12:00:00Z", BRANCH, "the first real turn"));

    assertEquals(
        List.of("66666666-6666-6666-6666-666666666666"),
        archive().sessionsFor(workspace()).stream().map(ArchivedSessionDto::sessionId).toList());
  }

  /**
   * The other side of that bound: the lookahead is bounded so a foreign session cannot cost a full
   * read, and a branch that only appears past the bound is not found. Stated as a test because the
   * number is a cost/behaviour trade and a reader should see that it has a behavioural edge at all.
   */
  @Test
  void aBranchNamedOnlyBeyondTheLookaheadIsNotAttributed() throws IOException {
    String[] lines = new String[ClaudeTranscriptArchive.BRANCH_LOOKAHEAD_LINES + 1];
    for (int i = 0; i < lines.length - 1; i++) {
      lines[i] = "{\"type\":\"queue-operation\"}";
    }
    lines[lines.length - 1] = line("user", "2026-09-20T12:00:00Z", BRANCH, "too late");
    writeSession("77777777-7777-7777-7777-777777777777", lines);

    assertTrue(archive().sessionsFor(workspace()).isEmpty());
  }

  /**
   * The mtime pre-filter. A file untouched since before the workspace existed is skipped unopened —
   * and because the filter is one-sided it can only skip what the window would reject anyway, which
   * is what this asserts from the outside: the file's content is on the right branch and inside the
   * window, and it is still excluded, because its mtime says the content cannot be what it claims.
   */
  @Test
  void aFileNotWrittenSinceBeforeTheWorkspaceExistedIsSkipped() throws IOException {
    Path file =
        writeSession(
            "88888888-8888-8888-8888-888888888888",
            line("user", "2026-09-20T12:00:00Z", BRANCH, "inside the window"));
    Files.setLastModifiedTime(file, FileTime.from(CREATED.minusSeconds(3600)));

    assertTrue(archive().sessionsFor(workspace()).isEmpty());
  }

  /** A live workspace has no {@code resolvedAt}, and the window runs to now. */
  @Test
  void aLiveWorkspaceWindowRunsToNow() throws IOException {
    Instant justNow = Instant.now().minusSeconds(30);
    writeSession(
        "99999999-9999-9999-9999-999999999999",
        line("user", justNow.toString(), BRANCH, "still going"));

    List<ArchivedSessionDto> sessions =
        archive()
            .sessionsFor(
                new ClaudeTranscriptArchive.Attribution(
                    BRANCH, Instant.now().minusSeconds(3600), null));

    assertEquals(1, sessions.size());
  }

  /** Another workspace's branch, inside our window. The window is not enough on its own either. */
  @Test
  void aDifferentBranchInsideTheWindowIsExcluded() throws IOException {
    writeSession(
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        line("user", "2026-09-20T12:00:00Z", "ticket/something-else", "not ours"));

    assertTrue(archive().sessionsFor(workspace()).isEmpty());
  }

  // --- the production-normal absence -------------------------------------------------------------

  /**
   * <b>Not an edge case: this is what production does today.</b> The shared volume is not mounted
   * into this service yet — applying {@code mounts[1]} needs an operator — so every read takes this
   * path, and a throw here would turn the whole history page into a 500 for every workspace on the
   * estate. An empty list is also indistinguishable from a workspace where no agent ever ran, which
   * is why the callers are told not to read it as a fault.
   */
  @Test
  void aMissingArchiveRootIsEmptyAndDoesNotThrow() {
    ClaudeTranscriptArchive absent =
        new ClaudeTranscriptArchive(root.resolve("nothing-is-mounted-here"));

    assertTrue(absent.sessionsFor(workspace()).isEmpty());
  }

  /** The same absence on the transcript door, where it is a 404 rather than an empty answer. */
  @Test
  void aMissingArchiveRootRefusesATranscriptRatherThanThrowingSomethingElse() {
    ClaudeTranscriptArchive absent =
        new ClaudeTranscriptArchive(root.resolve("nothing-is-mounted-here"));

    assertThrows(
        NotFoundException.class, () -> absent.transcriptOf(workspace(), "any-session-at-all"));
  }

  // --- the session id never reaches a path -------------------------------------------------------

  /**
   * <b>A session id is caller-supplied and is matched against the attributed set, never joined onto
   * a path.</b> This volume is the estate's shared harness home: every other project's transcripts
   * are on it, and so is the harness credential. The three ids below are the same refusal — a real
   * session belonging to another workspace, a traversal, and a name that is not a session at all —
   * because the door must not tell a caller which of those it was.
   */
  @Test
  void aSessionIdThatDoesNotAttributeIsRefused() throws IOException {
    writeSession(
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        line("user", "2026-09-20T12:00:00Z", "ticket/another-workspace", "somebody else's"));

    ClaudeTranscriptArchive archive = archive();
    assertThrows(
        NotFoundException.class,
        () -> archive.transcriptOf(workspace(), "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        "another workspace's session was served");
    assertThrows(
        NotFoundException.class,
        () -> archive.transcriptOf(workspace(), "../../../.credentials"),
        "a traversal was not refused");
    assertThrows(NotFoundException.class, () -> archive.transcriptOf(workspace(), "no-such-thing"));
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
    writeSession(sessionId, line("user", "2026-09-20T12:00:00Z", BRANCH, "spawn some agents"));
    writeSidechain(
        sessionId, "a1", "Explore", "find the thing", line("assistant", "2026-09-20T12:00:01Z", null, "found it"));
    writeSidechain(
        sessionId, "a2", "claude", "do the other thing", line("assistant", "2026-09-20T12:00:02Z", null, "did it"));

    List<String> lines = archive().transcriptOf(workspace(), sessionId);

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
    writeSession(sessionId, line("user", "2026-09-20T12:00:00Z", BRANCH, "go"));
    writeSidechainWithAnchor(
        sessionId,
        "a1",
        "Explore",
        "sweep the repo",
        "toolu_01abcdef",
        line("assistant", "2026-09-20T12:00:01Z", null, "swept"));

    List<String> lines = archive().transcriptOf(workspace(), sessionId);
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
   */
  @Test
  void anAbsentAnchorIsOmittedRatherThanEmittedAsNull() throws IOException {
    String sessionId = "2b2b2b2b-2b2b-2b2b-2b2b-2b2b2b2b2b2b";
    writeSession(sessionId, line("user", "2026-09-20T12:00:00Z", BRANCH, "go"));
    writeSidechainWithoutMeta(
        sessionId, "a1", line("assistant", "2026-09-20T12:00:01Z", null, "no sidecar at all"));

    List<String> lines = archive().transcriptOf(workspace(), sessionId);
    String marker = lines.get(indexOfMarker(lines, "a1"));

    assertFalse(marker.contains("toolUseId"), "an absent anchor was emitted as a key: " + marker);
    assertTrue(marker.contains("\"qits_agent_meta\""));
  }

  /** The labels ride the listing too, so a session can be described before it is opened. */
  @Test
  void aSubagentsLabelsRideTheListing() throws IOException {
    String sessionId = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    writeSession(sessionId, line("user", "2026-09-20T12:00:00Z", BRANCH, "go"));
    writeSidechain(
        sessionId, "a1", "Explore", "sweep the repo", line("assistant", "2026-09-20T12:00:01Z", null, "swept"));

    List<ArchivedSubagentDto> subagents = archive().sessionsFor(workspace()).get(0).subagents();

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
    writeSession(sessionId, line("user", "2026-09-20T12:00:00Z", BRANCH, "go"));
    // No meta file at all.
    writeSidechainWithoutMeta(sessionId, "a1", line("assistant", "2026-09-20T12:00:01Z", null, "one"));
    // A meta file present but carrying JSON nulls.
    writeSidechainWithoutMeta(sessionId, "a2", line("assistant", "2026-09-20T12:00:02Z", null, "two"));
    Files.writeString(
        subagentsDir(sessionId).resolve("agent-a2.meta.json"),
        "{\"agentType\":null,\"description\":null}",
        StandardCharsets.UTF_8);

    List<ArchivedSubagentDto> subagents = archive().sessionsFor(workspace()).get(0).subagents();

    assertEquals(2, subagents.size());
    for (ArchivedSubagentDto subagent : subagents) {
      assertNull(subagent.agentType(), "a missing label came back as a non-null placeholder");
      assertNull(subagent.description(), "a missing label came back as a non-null placeholder");
    }
    // And the sidechain is still carried: an unlabelled subagent is still a subagent, and dropping
    // it would lose its lines from the transcript.
    List<String> lines = archive().transcriptOf(workspace(), sessionId);
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
        line("user", "2026-09-20T12:00:00Z", BRANCH, "a real turn"),
        // A tool-result carrier: type user, content blocks that are not text.
        "{\"type\":\"user\",\"timestamp\":\"2026-09-20T12:00:01Z\",\"message\":{\"content\":"
            + "[{\"type\":\"tool_result\",\"content\":\"…\"}]}}",
        // A meta line.
        "{\"type\":\"user\",\"isMeta\":true,\"timestamp\":\"2026-09-20T12:00:02Z\",\"message\":"
            + "{\"content\":[{\"type\":\"text\",\"text\":\"system reminder\"}]}}",
        // Not a conversation envelope at all.
        "{\"type\":\"summary\",\"timestamp\":\"2026-09-20T12:00:03Z\",\"summary\":\"a title\"}",
        line("assistant", "2026-09-20T12:00:04Z", null, "the answer"));

    ArchivedSessionDto session = archive().sessionsFor(workspace()).get(0);

    assertEquals(2, session.messageCount(), "tool traffic or meta lines were counted as turns");
    // The span is still every timestamped record, turn or not — it is when the session ran.
    assertEquals(Instant.parse("2026-09-20T12:00:04Z"), session.endedAt());
  }

  /**
   * A line that is not JSON is skipped rather than failing the read. A transcript truncated mid-write
   * by a destroyed container ends in exactly that, and losing the whole session over its last
   * partial line would lose the part that was written.
   */
  @Test
  void aTruncatedFinalLineDoesNotLoseTheSession() throws IOException {
    writeSession(
        "0a0a0a0a-0a0a-0a0a-0a0a-0a0a0a0a0a0a",
        line("user", "2026-09-20T12:00:00Z", BRANCH, "the turn that landed"),
        "{\"type\":\"assistant\",\"timestamp\":\"2026-09-20T12:00:0");

    List<ArchivedSessionDto> sessions = archive().sessionsFor(workspace());

    assertEquals(1, sessions.size());
    assertEquals(1, sessions.get(0).messageCount());
    assertEquals(Instant.parse("2026-09-20T12:00:00Z"), sessions.get(0).endedAt());
  }

  /** Several sessions come back oldest first, so a page renders them in the order they happened. */
  @Test
  void sessionsAreOldestFirst() throws IOException {
    writeSession("0b0b0b0b-0b0b-0b0b-0b0b-0b0b0b0b0b0b", line("user", "2026-09-20T15:00:00Z", BRANCH, "later"));
    writeSession("0c0c0c0c-0c0c-0c0c-0c0c-0c0c0c0c0c0c", line("user", "2026-09-20T11:00:00Z", BRANCH, "earlier"));

    assertEquals(
        List.of("0c0c0c0c-0c0c-0c0c-0c0c-0c0c0c0c0c0c", "0b0b0b0b-0b0b-0b0b-0b0b-0b0b0b0b0b0b"),
        archive().sessionsFor(workspace()).stream().map(ArchivedSessionDto::sessionId).toList());
  }

  /** An empty directory is the "no agent ever ran here" answer, and is not an error either. */
  @Test
  void anEmptyProjectsDirectoryIsEmpty() throws IOException {
    Files.createDirectories(projectsDir());

    assertTrue(archive().sessionsFor(workspace()).isEmpty());
    assertFalse(archive().sessionsFor(workspace()) == null);
  }

  // --- fixtures ----------------------------------------------------------------------------------

  /**
   * The directory every session lands in: {@code <root>/.claude/projects/-workspace}. Spelled here
   * as the literal the library's escaping rule produces for {@code /workspace}, on purpose — the
   * production code derives it from {@code ClaudeCodeAgent}, so a test that derived it the same way
   * would agree with the code by construction and prove nothing about the layout on disk.
   */
  private Path projectsDir() {
    return root.resolve(".claude").resolve("projects").resolve("-workspace");
  }

  private Path subagentsDir(String sessionId) {
    return projectsDir().resolve(sessionId).resolve("subagents");
  }

  private Path writeSession(String sessionId, String... lines) throws IOException {
    Files.createDirectories(projectsDir());
    Path file = projectsDir().resolve(sessionId + ".jsonl");
    Files.write(file, List.of(lines), StandardCharsets.UTF_8);
    // The mtime pre-filter reads this; a freshly written fixture is "now", which is inside every
    // window here except the one case that sets it back deliberately.
    Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
    return file;
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

  /** One conversation envelope. A null branch omits {@code gitBranch}, as later records do. */
  private static String line(String type, String timestamp, String gitBranch, String text) {
    return "{\"type\":\""
        + type
        + "\",\"timestamp\":\""
        + timestamp
        + "\""
        + (gitBranch == null ? "" : ",\"gitBranch\":\"" + gitBranch + "\"")
        + ",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\""
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
