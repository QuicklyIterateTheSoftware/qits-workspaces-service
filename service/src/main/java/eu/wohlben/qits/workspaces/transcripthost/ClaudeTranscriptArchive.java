package eu.wohlben.qits.workspaces.transcripthost;

import eu.wohlben.qits.agents.ClaudeCodeAgent;
import eu.wohlben.qits.agents.json.Json;
import eu.wohlben.qits.workspaces.dto.ArchivedSessionDto;
import eu.wohlben.qits.workspaces.dto.ArchivedSubagentDto;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import io.vertx.core.json.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads Claude Code's own JSONL transcripts back off the shared harness volume, and decides which of
 * them belong to a given workspace.
 *
 * <p><b>Framework-free on purpose.</b> Nothing here is injected, scheduled or configured: it is
 * given a root directory and answers questions about what is under it. {@link
 * MountedClaudeTranscripts} is the thin CDI adapter that supplies the root and adapts a {@code
 * Workspace} row into an {@link Attribution}. The whole attribution rule — the part that is actually
 * hard, and the part a mistake in returns another workspace's conversation — therefore runs in a
 * plain JUnit test against a temp directory, with no Quarkus application behind it. That matters
 * twice over in this module, where every {@code @TestProfile} is a separate Quarkus boot and the
 * suite has been OOM-killed for having too many.
 *
 * <h2>Why attribution is needed at all</h2>
 *
 * <p>The volume carries no workspace identity. Every workspace container runs the agent with cwd
 * {@code /workspace}, and the harness keys its transcript directory on the <em>escaped cwd</em>, so
 * every session the estate has ever run — across every workspace, every repository and every
 * project agent container that shares the volume — lands in one flat directory named {@code
 * projects/-workspace}. A file name is a session UUID and says nothing about who produced it.
 *
 * <h2>The rule</h2>
 *
 * <p>Four conditions, and all four are load-bearing:
 *
 * <ol>
 *   <li><b>An mtime pre-filter.</b> A file whose last write predates the workspace cannot be the
 *       workspace's, so it is skipped without being opened. The filter is deliberately one-sided: a
 *       session's mtime is its <em>last</em> write and therefore never earlier than its first
 *       record, so {@code mtime < createdAt} implies the window test below would reject it anyway —
 *       the pre-filter can only ever skip files the real rule also excludes. There is no upper mtime
 *       bound, because a session may legitimately keep writing after the workspace resolves and its
 *       mtime then sits outside the window while its first record does not.
 *   <li><b>Branch equality.</b> The first record carrying {@code gitBranch} names the branch the
 *       agent was working on; it must equal the workspace's branch exactly.
 *   <li><b>{@code HEAD} is never a match.</b> A project agent container's checkout is detached, so
 *       its sessions record {@code "gitBranch":"HEAD"}. Those sessions share this volume and are not
 *       any workspace's. A workspace branch can never be the literal string {@code HEAD} — branch
 *       names are slug-validated — so this is stated rather than inferred, because it is the one
 *       exclusion a reader of this file would otherwise have to reconstruct.
 *   <li><b>The {@code [createdAt, resolvedAt ?: now]} window.</b> Branch equality alone is not
 *       enough, and this is the subtle one: a branch is <em>reusable</em> once its workspace
 *       resolves. Two workspace rows can legitimately carry the same branch name at different times,
 *       and without the window the second one would serve the first one's transcripts as its own.
 *       The session's first timestamp is what is tested — where a session <em>started</em> is what
 *       places it in a workspace's life, and a long session running past resolution still belongs to
 *       the workspace that began it.
 * </ol>
 *
 * <h2>Why {@code gitBranch} is looked for over a bounded lookahead</h2>
 *
 * <p>Not every record carries it. A transcript commonly opens with harness bookkeeping — {@code
 * {"type":"queue-operation"}} and friends — that has no {@code gitBranch} at all, so taking the
 * first line's value would attribute nothing. Equally, scanning the whole file for one would make
 * every megabyte of every foreign session the cost of answering. So the first {@link
 * #BRANCH_LOOKAHEAD_LINES} lines are searched and the first {@code gitBranch} among them wins; a
 * file that has not named a branch by then is treated as unattributable rather than guessed at.
 *
 * <h2>The path maths is the library's, not ours</h2>
 *
 * <p>Where a session's JSONL and its subagents' sidechains sit under the harness config directory is
 * {@link ClaudeCodeAgent#transcriptPath} and {@link ClaudeCodeAgent#subagentsDir}' to say — the same
 * methods the workspace daemon uses to find the files it wrote. Spelling the cwd-escaping rule a
 * second time here is how the reader and the writer drift apart over a CLI upgrade, so the directory
 * below is <em>derived</em> from those methods rather than composed from a literal.
 *
 * <h2>Failure is always an empty answer</h2>
 *
 * <p>A missing archive root, an unreadable directory, an unreadable file and a line that is not JSON
 * all yield "nothing here", never an exception. That is not defensive padding: a deployment with no
 * mount at all is the production-normal case today, and a caller cannot distinguish it from a
 * workspace where no agent ever ran anyway — so there is nothing an exception would let anyone do.
 * The single exception is {@link #transcriptOf} being asked for a session that does not attribute,
 * which is a 404 about the request rather than about the volume.
 */
public final class ClaudeTranscriptArchive {

  /**
   * The cwd every workspace container runs the agent with, and therefore the only escaped-cwd
   * directory this reader looks in. Mirrors {@code AgentTranscriptService.CONTAINER_CWD}, which is
   * package-private in the library.
   */
  static final String CONTAINER_CWD = "/workspace";

  /**
   * Claude's config directory under the shared volume — {@code $CLAUDE_CONFIG_DIR} as every
   * container sets it. Mirrors the library's {@code dotDir(AgentType.CLAUDE)}, which is private.
   */
  static final String HARNESS_DOT_DIR = ".claude";

  /**
   * The synthetic line type introducing a sidechain, mirroring {@code
   * AgentTranscriptService.AGENT_META_TYPE}. It is a <em>wire</em> string: the frontend's transcript
   * parser splits on it, so the two producers must agree character for character, and it is repeated
   * here only because the library's constant is package-private.
   */
  static final String AGENT_META_TYPE = "qits_agent_meta";

  /** How far into a transcript a {@code gitBranch} is looked for. See the class javadoc. */
  static final int BRANCH_LOOKAHEAD_LINES = 20;

  /** The clamp the harness library applies to the same labels, repeated so the shapes match. */
  private static final int AGENT_TYPE_MAX = 255;

  private static final int DESCRIPTION_MAX = 1024;

  private static final String JSONL = ".jsonl";

  private final Path archiveRoot;

  public ClaudeTranscriptArchive(Path archiveRoot) {
    this.archiveRoot = archiveRoot;
  }

  /**
   * What a workspace is, as far as attribution is concerned: a branch and a life. Everything else
   * about the row — its id, its repository, its narrative — is irrelevant here, and taking the whole
   * entity would make this class need one.
   *
   * @param branch the workspace's branch, matched exactly against the transcript's {@code gitBranch}
   * @param createdAt when the workspace came into being; the window's inclusive lower bound
   * @param resolvedAt when it stopped being live, or {@code null} while it still is — in which case
   *     the window runs to now, so an agent working in a live workspace is reported as it writes
   */
  public record Attribution(String branch, Instant createdAt, Instant resolvedAt) {}

  /** The workspace's sessions, oldest first. Empty when nothing attributes; never throws. */
  public List<ArchivedSessionDto> sessionsFor(Attribution attribution) {
    if (attribution == null || attribution.branch() == null || attribution.createdAt() == null) {
      return List.of();
    }
    Path projects = projectsDir();
    if (!Files.isDirectory(projects)) {
      // The production-normal case today: the volume is not mounted into this service yet.
      return List.of();
    }
    Instant from = attribution.createdAt();
    Instant to = attribution.resolvedAt() == null ? Instant.now() : attribution.resolvedAt();

    List<ArchivedSessionDto> sessions = new ArrayList<>();
    try (Stream<Path> entries = Files.list(projects)) {
      List<Path> candidates =
          entries
              .filter(path -> path.getFileName().toString().endsWith(JSONL))
              .filter(Files::isRegularFile)
              .filter(path -> writtenNoEarlierThan(path, from))
              .toList();
      for (Path candidate : candidates) {
        Scan scan = scan(candidate);
        if (scan == null || !attributes(scan, attribution.branch(), from, to)) {
          continue;
        }
        String sessionId = stripSuffix(candidate.getFileName().toString());
        sessions.add(
            new ArchivedSessionDto(
                sessionId, scan.firstAt(), scan.lastAt(), scan.messageCount(), subagents(sessionId)));
      }
    } catch (IOException e) {
      return List.of();
    }
    sessions.sort(
        Comparator.comparing(
                ArchivedSessionDto::startedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ArchivedSessionDto::sessionId));
    return List.copyOf(sessions);
  }

  /**
   * The session's raw JSONL: the harness's own lines verbatim, then each sidechain introduced by a
   * synthetic {@link #AGENT_META_TYPE} marker line.
   *
   * <p><b>{@code sessionId} never reaches a path.</b> It is resolved against what {@link
   * #sessionsFor} returned for this workspace, and the path that is then opened is rebuilt from the
   * attributed session's own id. That is not belt-and-braces: this volume is the estate's shared
   * harness home, holding every other project's transcripts and the harness credential beside them,
   * so a string concatenated into a path here is a read of all of it. Anything that does not
   * attribute is a 404 — the same answer a session that never existed gets, so the door tells a
   * caller nothing about what else is on the volume.
   */
  public List<String> transcriptOf(Attribution attribution, String sessionId) {
    ArchivedSessionDto session =
        sessionsFor(attribution).stream()
            .filter(candidate -> candidate.sessionId().equals(sessionId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Agent session not found: " + sessionId));

    List<String> lines = new ArrayList<>(readLines(transcriptFile(session.sessionId())));
    for (ArchivedSubagentDto subagent : session.subagents()) {
      lines.add(metaLine(subagentsDir(session.sessionId()), subagent));
      lines.addAll(readLines(sidechainFile(session.sessionId(), subagent.agentId())));
    }
    return List.copyOf(lines);
  }

  // --- the rule ----------------------------------------------------------------------------------

  private static boolean attributes(Scan scan, String branch, Instant from, Instant to) {
    if (scan.branch() == null || "HEAD".equals(scan.branch()) || !branch.equals(scan.branch())) {
      return false;
    }
    Instant startedAt = scan.firstAt();
    return startedAt != null && !startedAt.isBefore(from) && !startedAt.isAfter(to);
  }

  /**
   * The mtime pre-filter. A file that has not been written since the workspace was created cannot
   * carry a record from inside the window, so it is skipped unopened. An unreadable mtime keeps the
   * file — the filter exists to save work, and must never be the reason a real session is lost.
   */
  private static boolean writtenNoEarlierThan(Path file, Instant from) {
    try {
      return !Files.getLastModifiedTime(file).toInstant().isBefore(from);
    } catch (IOException e) {
      return true;
    }
  }

  /** One pass over a transcript: its branch, its span, and how many turns it holds. */
  private record Scan(String branch, Instant firstAt, Instant lastAt, int messageCount) {}

  private Scan scan(Path file) {
    String branch = null;
    Instant firstAt = null;
    Instant lastAt = null;
    int messageCount = 0;
    int lineNumber = 0;
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        Json node = Json.parse(line);
        if (node.isMissing()) {
          continue;
        }
        if (branch == null && lineNumber <= BRANCH_LOOKAHEAD_LINES) {
          String candidate = node.path("gitBranch").asText(null);
          if (candidate != null && !candidate.isBlank()) {
            branch = candidate;
          }
        }
        Instant timestamp = timestampOf(node);
        if (timestamp != null) {
          if (firstAt == null || timestamp.isBefore(firstAt)) {
            firstAt = timestamp;
          }
          if (lastAt == null || timestamp.isAfter(lastAt)) {
            lastAt = timestamp;
          }
        }
        if (isConversationTurn(node)) {
          messageCount++;
        }
      }
    } catch (IOException e) {
      return null;
    }
    return new Scan(branch, firstAt, lastAt, messageCount);
  }

  /**
   * A conversation turn by the harness's own rule — a {@code user}/{@code assistant} envelope
   * actually carrying text, not a tool carrier, a thinking-only line or a meta line. Kept identical
   * to {@code AgentTranscriptService.isEnvelopeConversationTurn} so that a session's count reads the
   * same here as it does on a live workspace's agent surface; counting lines instead would count
   * tool traffic and report several times higher for the same conversation.
   */
  private static boolean isConversationTurn(Json node) {
    String type = node.path("type").asText("");
    if (!"user".equals(type) && !"assistant".equals(type)) {
      return false;
    }
    if (node.path("isMeta").asBoolean(false)) {
      return false;
    }
    Json content = node.path("message").path("content");
    if (content.isTextual()) {
      return !content.asText().isBlank();
    }
    for (Json block : content) {
      if ("text".equals(block.path("type").asText(""))) {
        return true;
      }
    }
    return false;
  }

  private static Instant timestampOf(Json node) {
    String timestamp = node.path("timestamp").asText(null);
    if (timestamp == null) {
      return null;
    }
    try {
      return Instant.parse(timestamp);
    } catch (RuntimeException e) {
      return null;
    }
  }

  // --- sidechains --------------------------------------------------------------------------------

  private List<ArchivedSubagentDto> subagents(String sessionId) {
    Path dir = subagentsDir(sessionId);
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    List<ArchivedSubagentDto> subagents = new ArrayList<>();
    try (Stream<Path> entries = Files.list(dir)) {
      List<Path> sidechains =
          entries
              .filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().startsWith("agent-"))
              .filter(path -> path.getFileName().toString().endsWith(JSONL))
              // By name, which is the order the library imports them in.
              .sorted(Comparator.comparing(path -> path.getFileName().toString()))
              .toList();
      for (Path sidechain : sidechains) {
        String agentId = stripSuffix(sidechain.getFileName().toString()).substring("agent-".length());
        Scan scan = scan(sidechain);
        subagents.add(meta(dir, agentId, scan == null ? 0 : scan.messageCount()));
      }
    } catch (IOException e) {
      return List.copyOf(subagents);
    }
    return List.copyOf(subagents);
  }

  /**
   * The labels beside a sidechain, from its {@code agent-<id>.meta.json}. Agent-written free text, so
   * clamped to the same lengths the harness library clamps them to: they are rendered, not
   * interpreted, and an absent or unreadable meta file simply leaves them null.
   */
  private static ArchivedSubagentDto meta(Path dir, String agentId, int messageCount) {
    Path metaFile = dir.resolve("agent-" + agentId + ".meta.json");
    if (Files.isRegularFile(metaFile)) {
      try {
        Json parsed = Json.parse(Files.readString(metaFile));
        return new ArchivedSubagentDto(
            agentId,
            truncate(parsed.path("agentType").asText(null), AGENT_TYPE_MAX),
            truncate(parsed.path("description").asText(null), DESCRIPTION_MAX),
            messageCount);
      } catch (IOException e) {
        // Fall through to the unlabelled shape: a sidechain with no readable labels is still a
        // sidechain, and dropping it would lose its lines from the transcript.
      }
    }
    return new ArchivedSubagentDto(agentId, null, null, messageCount);
  }

  private static String truncate(String value, int maxLength) {
    return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
  }

  /**
   * The marker the frontend's parser splits a transcript on. Built like the library builds it, in
   * the library's key order: {@code type} and {@code agentId} always, then whichever of {@code
   * agentType}, {@code description} and {@code toolUseId} the sidecar carried.
   *
   * <p><b>{@code toolUseId} is read from the sidecar rather than from {@link ArchivedSubagentDto}</b>,
   * and the asymmetry is deliberate. That field is not a label a reader sees — it anchors the
   * sidechain to the {@code tool_use} block in the parent turn that spawned it, which is how the
   * frontend nests a subagent under the call that made it. So it belongs on the wire and not in the
   * listing DTO, which exists to draw a row. Dropping it here would not fail anything loudly; it
   * would quietly flatten every subagent to the top level of a rendered transcript.
   */
  private static String metaLine(Path subagentsDir, ArchivedSubagentDto subagent) {
    JsonObject node =
        new JsonObject().put("type", AGENT_META_TYPE).put("agentId", subagent.agentId());
    if (subagent.agentType() != null) {
      node.put("agentType", subagent.agentType());
    }
    if (subagent.description() != null) {
      node.put("description", subagent.description());
    }
    String toolUseId = toolUseId(subagentsDir, subagent.agentId());
    if (toolUseId != null) {
      node.put("toolUseId", toolUseId);
    }
    return node.encode();
  }

  /** The sidechain's {@code toolUseId}, or null when it has no readable sidecar. */
  private static String toolUseId(Path subagentsDir, String agentId) {
    Path metaFile = subagentsDir.resolve("agent-" + agentId + ".meta.json");
    if (!Files.isRegularFile(metaFile)) {
      return null;
    }
    try {
      return Json.parse(Files.readString(metaFile)).path("toolUseId").asText(null);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  // --- paths, all derived from the library's own maths -------------------------------------------

  private Path configDir() {
    return archiveRoot.resolve(HARNESS_DOT_DIR);
  }

  /**
   * {@code <root>/.claude/projects/<escaped-cwd>} — taken as the parent of a transcript path rather
   * than composed, so the escaping rule stays the library's alone.
   */
  private Path projectsDir() {
    return configDir().resolve(new ClaudeCodeAgent().transcriptPath(CONTAINER_CWD, "probe").getParent());
  }

  private Path transcriptFile(String sessionId) {
    return configDir().resolve(new ClaudeCodeAgent().transcriptPath(CONTAINER_CWD, sessionId));
  }

  private Path subagentsDir(String sessionId) {
    return configDir().resolve(new ClaudeCodeAgent().subagentsDir(CONTAINER_CWD, sessionId));
  }

  private Path sidechainFile(String sessionId, String agentId) {
    return subagentsDir(sessionId).resolve("agent-" + agentId + JSONL);
  }

  private static String stripSuffix(String fileName) {
    return fileName.substring(0, fileName.length() - JSONL.length());
  }

  /** Every non-blank line of a file, or nothing at all when it cannot be read. */
  private static List<String> readLines(Path file) {
    try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
      return lines.filter(line -> !line.isBlank()).toList();
    } catch (IOException e) {
      return List.of();
    }
  }
}
