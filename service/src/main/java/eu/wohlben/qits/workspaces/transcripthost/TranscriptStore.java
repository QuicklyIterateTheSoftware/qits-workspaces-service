package eu.wohlben.qits.workspaces.transcripthost;

import eu.wohlben.qits.workspacedaemon.protocol.TranscriptFiles;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The agent transcripts workspace-daemons ship over the control socket (qits-1752), kept on this
 * service's own volume.
 *
 * <p><b>Why it exists.</b> A TARGET workspace's harness writes its transcripts to its execution
 * target's {@code /claude-home}, which this service cannot mount, so every agent-session door
 * answered empty for it. The daemon sends each of its own agents' files as {@code
 * TranscriptChunk}s, and this class is where they land: since qits-368 the only source the doors
 * read.
 *
 * <p><b>Framework-free</b>, like {@link ClaudeTranscriptArchive}: given a root, it appends and says
 * where an agent's sessions are. {@link ShippedClaudeTranscripts} supplies the root from config,
 * and the tests run against a temp directory.
 *
 * <h2>Layout</h2>
 *
 * <p>The harness's own, relative to one directory per agent: {@code <root>/<agentId>/<sessionId>.jsonl}
 * and {@code <root>/<agentId>/<sessionId>/subagents/agent-<id>.jsonl}. That is what {@link
 * ClaudeTranscriptArchive#ofDirectory} reads.
 *
 * <h2>The append rule is the delivery guarantee</h2>
 *
 * <p>A chunk is appended only when its {@code offset} equals the bytes held for that file; the
 * answer to anything else is the held size, which the caller sends back as a {@code
 * TranscriptResync} so the daemon moves its cursor there. Lower is a duplicate or a daemon restart
 * (its cursors start at 0), higher is a frame lost to a reconnect; both are corrected by the
 * resync, and the held size never goes down. Offsets count UTF-8 bytes on disk, and {@code data} is
 * appended as its UTF-8 bytes, so the two agree. A chunk need not end in a newline: a single line
 * longer than the daemon's cap arrives as consecutive chunks, and contiguous offsets make that
 * indistinguishable from any other append.
 *
 * <p>Appends to one file are serialized by a lock striped on its path, because the size check and
 * the write are one decision. No fsync beyond the close: a write lost to a crash is a size the next
 * chunk disagrees with, and the resync re-sends it.
 *
 * <h2>Nothing from the wire becomes a path unvalidated</h2>
 *
 * <p>The agent is a {@link UUID}, so its segment is the UUID's own rendering; the session id and the
 * file are checked against the protocol's own grammar ({@link TranscriptFiles}) again here, even
 * though the records refuse a bad one on decode — this class is the one that turns them into a
 * path, so it does not trust that a caller built them through the record. The resolved path is
 * then required to sit under the agent's directory.
 */
public final class TranscriptStore {

  /** A session's sidechain directory, beside its {@code <sessionId>.jsonl}. */
  static final String SUBAGENTS = TranscriptFiles.SUBAGENTS;

  private static final String JSONL = ".jsonl";

  /** Lock stripes: bounded, unlike a lock per file, and appends rarely contend. */
  private static final int STRIPES = 64;

  private final Path root;

  private final Object[] stripes = new Object[STRIPES];

  public TranscriptStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
    for (int i = 0; i < STRIPES; i++) {
      stripes[i] = new Object();
    }
  }

  /**
   * What an {@link #append} did.
   *
   * @param appended whether the chunk was written
   * @param size the bytes held for the file afterwards; when nothing was appended, the size to
   *     resync the daemon to
   */
  public record Appended(boolean appended, long size) {}

  /**
   * Append {@code data} at {@code offset} to one of an agent session's files, or say what is held.
   *
   * @param file {@code main} or {@code subagents/agent-<id>.jsonl}
   * @throws IllegalArgumentException a session id or file outside the protocol's grammar, or a
   *     negative offset — refused before anything is written
   * @throws IOException the file could not be read or written; nothing is reported held
   */
  public Appended append(UUID agentId, String sessionId, String file, long offset, String data)
      throws IOException {
    if (data == null) {
      throw new IllegalArgumentException("transcript chunk has no data");
    }
    if (offset < 0) {
      throw new IllegalArgumentException("transcript chunk offset is negative: " + offset);
    }
    Path target = fileOf(agentId, sessionId, file);
    synchronized (stripes[Math.floorMod(target.hashCode(), STRIPES)]) {
      long held = Files.isRegularFile(target) ? Files.size(target) : 0L;
      if (offset != held) {
        return new Appended(false, held);
      }
      byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
      Files.createDirectories(target.getParent());
      try (OutputStream out =
          Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
        out.write(bytes);
      }
      return new Appended(true, held + bytes.length);
    }
  }

  /**
   * The files held for one of an agent's sessions, as {@link TranscriptFiles} addresses them (the
   * restore of qits-2088): every sidechain, by name, then {@code main} last — so a restore that
   * stops part way never leaves the main file, the one a resume is decided on, without them. Empty
   * when the main file is not held: a session with no transcript is nothing to restore.
   *
   * @throws IllegalArgumentException a session id outside the protocol's grammar
   */
  public List<String> files(UUID agentId, String sessionId) throws IOException {
    Path main = fileOf(agentId, sessionId, TranscriptFiles.MAIN);
    if (!Files.isRegularFile(main, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    List<String> files = new ArrayList<>();
    Path subagents = main.resolveSibling(sessionId).resolve(SUBAGENTS);
    if (Files.isDirectory(subagents, LinkOption.NOFOLLOW_LINKS)) {
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(subagents, "agent-*.jsonl")) {
        for (Path entry : entries) {
          String file = TranscriptFiles.sidechain(entry.getFileName().toString());
          if (file != null && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            files.add(file);
          }
        }
      }
    }
    files.sort(null);
    files.add(TranscriptFiles.MAIN);
    return files;
  }

  /**
   * What a {@link #read} found.
   *
   * @param data the bytes from {@code offset}, as text; never a split UTF-8 character
   * @param next the offset after them, to read from next
   * @param last whether they reach the end of the file
   */
  public record Chunk(String data, long next, boolean last) {}

  /**
   * Up to {@code maxBytes} of one held file from {@code offset}, cut back to a UTF-8 character
   * boundary, for a restore (qits-2088). Offsets count bytes on disk, as {@link #append}'s do.
   *
   * @throws IllegalArgumentException an address outside the grammar, a negative offset, or a cap
   *     too small to hold one character
   * @throws IOException the file is not held, or could not be read
   */
  public Chunk read(UUID agentId, String sessionId, String file, long offset, int maxBytes)
      throws IOException {
    if (offset < 0) {
      throw new IllegalArgumentException("transcript read offset is negative: " + offset);
    }
    if (maxBytes < 4) {
      throw new IllegalArgumentException("a chunk must hold at least one character");
    }
    Path target = fileOf(agentId, sessionId, file);
    try (FileChannel channel =
        FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      long size = channel.size();
      long from = Math.min(offset, size);
      // One byte past the cap, to see whether the cap cuts through a character.
      ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(maxBytes + 1L, size - from));
      while (buffer.hasRemaining() && channel.read(buffer, from + buffer.position()) >= 0) {
        // Fill it: a short read is not the end of the file.
      }
      int read = buffer.position();
      int length = Math.min(read, maxBytes);
      boolean last = from + length >= size;
      if (!last && read > length) {
        // Back off to the start of the character the cap cut through: the next chunk never
        // starts on a continuation byte. Only bytes that are not UTF-8 could exhaust the cap.
        int cut = length;
        while (cut > 0 && (buffer.get(cut) & 0xC0) == 0x80) {
          cut--;
        }
        length = cut == 0 ? length : cut;
      }
      String data = new String(buffer.array(), 0, length, StandardCharsets.UTF_8);
      return new Chunk(data, from + length, last);
    }
  }

  /** {@code <root>/<agentId>}: every session the agent's daemon shipped, in the harness's layout. */
  public Path agentDirectory(UUID agentId) {
    if (agentId == null) {
      throw new IllegalArgumentException("a transcript belongs to an agent");
    }
    return root.resolve(agentId.toString());
  }

  /** The file a validated {@code (agent, session, file)} address names; refuses anything else. */
  Path fileOf(UUID agentId, String sessionId, String file) {
    if (!TranscriptFiles.validSessionId(sessionId)) {
      throw new IllegalArgumentException("not a transcript session id: " + sessionId);
    }
    if (!TranscriptFiles.validFile(file)) {
      throw new IllegalArgumentException("not a transcript file: " + file);
    }
    Path agentDir = agentDirectory(agentId);
    Path target =
        TranscriptFiles.MAIN.equals(file)
            ? agentDir.resolve(sessionId + JSONL)
            : agentDir
                .resolve(sessionId)
                .resolve(SUBAGENTS)
                .resolve(file.substring(SUBAGENTS.length() + 1));
    Path normalized = target.normalize();
    if (!normalized.startsWith(agentDir) || normalized.equals(agentDir)) {
      throw new IllegalArgumentException("transcript path escapes its agent: " + file);
    }
    return normalized;
  }
}
