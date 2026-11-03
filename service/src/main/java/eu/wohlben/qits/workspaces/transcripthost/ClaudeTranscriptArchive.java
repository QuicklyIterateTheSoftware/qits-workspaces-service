package eu.wohlben.qits.workspaces.transcripthost;

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
 * Reads Claude Code's own JSONL transcripts back out of one agent's directory in the transcript
 * store, and renders them as the agent-session doors answer.
 *
 * <p><b>Framework-free on purpose.</b> Nothing here is injected, scheduled or configured: it is
 * given a directory and answers questions about what is under it. {@link ShippedClaudeTranscripts}
 * is the thin CDI adapter that supplies the directory. The listing and rendering therefore run in a
 * plain JUnit test against a temp directory, with no Quarkus application behind it. That matters in
 * this module, where every {@code @TestProfile} is a separate Quarkus boot and the suite has been
 * OOM-killed for having too many.
 *
 * <h2>A directory that is already one agent's (qits-1752)</h2>
 *
 * <p>{@link #ofDirectory} reads a directory in which <em>every</em> session belongs — the
 * transcript store's {@code <root>/<agentId>/}, which {@link TranscriptStore} fills from what an
 * agent's own workspace-daemon shipped. The daemon attributed those sessions when it chose to ship
 * them, so there is nothing left to decide: {@link #sessions()} lists them all, and {@link
 * #transcriptOf(String)} still resolves the requested id against that listing rather than joining
 * it onto a path.
 *
 * <h2>Failure is always an empty answer</h2>
 *
 * <p>A missing directory, an unreadable file and a line that is not JSON all yield "nothing here",
 * never an exception. That is not defensive padding: an agent whose daemon never shipped anything
 * has no directory at all, and a caller cannot distinguish that from an agent that never ran — so
 * there is nothing an exception would let anyone do. The single exception is {@link #transcriptOf}
 * being asked for a session that is not listed, which is a 404 about the request rather than about
 * the store.
 */
public final class ClaudeTranscriptArchive {

  /**
   * The synthetic line type introducing a sidechain, mirroring {@code
   * AgentTranscriptService.AGENT_META_TYPE}. It is a <em>wire</em> string: the frontend's transcript
   * parser splits on it, so the two producers must agree character for character, and it is repeated
   * here only because the library's constant is package-private.
   */
  static final String AGENT_META_TYPE = "qits_agent_meta";

  /** The clamp the harness library applies to the same labels, repeated so the shapes match. */
  private static final int AGENT_TYPE_MAX = 255;

  private static final int DESCRIPTION_MAX = 1024;

  private static final String JSONL = ".jsonl";

  /** The agent's directory: {@code <sessionId>.jsonl} files and their sidechain directories. */
  private final Path sessionsDirectory;

  private ClaudeTranscriptArchive(Path sessionsDirectory) {
    this.sessionsDirectory = sessionsDirectory;
  }

  /**
   * An archive over one directory whose every session belongs to the asker: {@code
   * <sessionId>.jsonl} files, each with its sidechains under {@code <sessionId>/subagents/}. Read
   * with {@link #sessions()} and {@link #transcriptOf(String)}; see the class javadoc.
   */
  public static ClaudeTranscriptArchive ofDirectory(Path directory) {
    return new ClaudeTranscriptArchive(directory);
  }

  /**
   * Every session in the directory, oldest first. Empty when the directory does not exist; never
   * throws.
   */
  public List<ArchivedSessionDto> sessions() {
    if (!Files.isDirectory(sessionsDirectory)) {
      return List.of();
    }
    List<ArchivedSessionDto> sessions = new ArrayList<>();
    try (Stream<Path> entries = Files.list(sessionsDirectory)) {
      List<Path> candidates =
          entries
              .filter(path -> path.getFileName().toString().endsWith(JSONL))
              .filter(Files::isRegularFile)
              .toList();
      for (Path candidate : candidates) {
        Scan scan = scan(candidate);
        if (scan == null) {
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
   * #sessions()} listed, and the path that is then opened is rebuilt from the listed session's own
   * id. Anything unlisted is a 404 — the same answer a session that never existed gets, so the door
   * tells a caller nothing about what else is on disk.
   */
  public List<String> transcriptOf(String sessionId) {
    return linesOf(find(sessions(), sessionId));
  }

  private static ArchivedSessionDto find(List<ArchivedSessionDto> sessions, String sessionId) {
    return sessions.stream()
        .filter(candidate -> candidate.sessionId().equals(sessionId))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Agent session not found: " + sessionId));
  }

  private List<String> linesOf(ArchivedSessionDto session) {
    List<String> lines = new ArrayList<>(readLines(transcriptFile(session.sessionId())));
    for (ArchivedSubagentDto subagent : session.subagents()) {
      lines.add(metaLine(subagentsDir(session.sessionId()), subagent));
      lines.addAll(readLines(sidechainFile(session.sessionId(), subagent.agentId())));
    }
    return List.copyOf(lines);
  }

  /** One pass over a transcript: its span, and how many turns it holds. */
  private record Scan(Instant firstAt, Instant lastAt, int messageCount) {}

  private Scan scan(Path file) {
    Instant firstAt = null;
    Instant lastAt = null;
    int messageCount = 0;
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        Json node = Json.parse(line);
        if (node.isMissing()) {
          continue;
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
    return new Scan(firstAt, lastAt, messageCount);
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

  // --- paths: the store's layout, which is the harness's own relative one ------------------------

  private Path transcriptFile(String sessionId) {
    return sessionsDirectory.resolve(sessionId + JSONL);
  }

  /** {@code <dir>/<sessionId>/subagents} ({@link TranscriptStore} says why this layout). */
  private Path subagentsDir(String sessionId) {
    return sessionsDirectory.resolve(sessionId).resolve(TranscriptStore.SUBAGENTS);
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
