package eu.wohlben.qits.workspaces.daemonhost;

import eu.wohlben.qits.workspaces.transcripthost.TranscriptStore;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Putting a session the platform holds back on a daemon whose credential volume lost it
 * (qits-2088).
 *
 * <p>A restart starts an agent again with the session id its row keeps. Claude resumes it only from
 * its files on the credential volume, and a volume swapped for an empty one has none — while this
 * service holds every byte the daemon shipped it ({@link TranscriptStore}). So a start of a session
 * held here carries {@code restorable}; a daemon that finds the files missing answers 409 {@code
 * session-missing} rather than starting fresh, and this class sends each held file back through the
 * daemon's {@code POST agent-worktrees/{agentId}/session-files}, in chunks: every sidechain first
 * and the main file last, since the main file is the one a resume is decided on. The caller then
 * starts the agent again, without {@code restorable}, and the daemon resumes the session or —
 * when this failed — starts a new one.
 *
 * <p>Framework-free: the store and the daemon call arrive as arguments, and the test drives both.
 */
final class SessionRestore {

  /**
   * The most bytes of a file one chunk carries. A chunk is one request through the workspace's
   * tunnel, read whole into the daemon's memory, so it is bounded; a transcript of tens of MB is
   * tens of requests.
   */
  static final int CHUNK_BYTES = 1024 * 1024;

  /** One chunk to the daemon: its HTTP status, or {@code -1} when it did not answer. */
  @FunctionalInterface
  interface Upload {
    int post(JsonObject chunk);
  }

  /** How a restore went. */
  enum Result {
    /** Every held file is in place on the daemon. */
    RESTORED,
    /** This service holds no transcript for the session. */
    NO_COPY,
    /** A read or a chunk failed; the daemon starts a new session. */
    FAILED
  }

  /** What {@link #restore} did, and what went wrong when it did not. */
  record Outcome(Result result, String detail) {}

  private SessionRestore() {}

  /**
   * Whether this service holds a transcript of {@code sessionId} for {@code agentId}: what makes a
   * start {@code restorable}. A malformed id or an unreadable store holds nothing.
   */
  static boolean holds(TranscriptStore store, String agentId, String sessionId) {
    try {
      return store != null
          && sessionId != null
          && !store.files(UUID.fromString(agentId), sessionId).isEmpty();
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /** Send every file held for the session, chunk by chunk, until one fails. */
  static Outcome restore(
      TranscriptStore store, String agentId, String sessionId, Upload upload, int chunkBytes) {
    UUID agent;
    List<String> files;
    try {
      agent = UUID.fromString(agentId);
      files = store.files(agent, sessionId);
    } catch (IOException | RuntimeException e) {
      return new Outcome(Result.FAILED, "could not list the held files: " + e.getMessage());
    }
    if (files.isEmpty()) {
      return new Outcome(Result.NO_COPY, "no transcript of the session is held");
    }
    long bytes = 0;
    for (String file : files) {
      long offset = 0;
      boolean last = false;
      while (!last) {
        TranscriptStore.Chunk chunk;
        try {
          chunk = store.read(agent, sessionId, file, offset, chunkBytes);
        } catch (IOException | RuntimeException e) {
          return new Outcome(Result.FAILED, "could not read " + file + ": " + e.getMessage());
        }
        int status = upload.post(chunkBody(sessionId, file, offset, chunk.data(), chunk.last()));
        if (status < 200 || status >= 300) {
          return new Outcome(
              Result.FAILED,
              "the daemon answered "
                  + (status < 0 ? "nothing" : String.valueOf(status))
                  + " to "
                  + file
                  + " at "
                  + offset);
        }
        bytes += chunk.next() - offset;
        offset = chunk.next();
        last = chunk.last();
      }
    }
    return new Outcome(Result.RESTORED, files.size() + " file(s), " + bytes + " bytes");
  }

  /** The {@code session-files} body: one chunk of one file. Package-private for its contract row. */
  static JsonObject chunkBody(
      String sessionId, String file, long offset, String data, boolean last) {
    return new JsonObject()
        .put("sessionId", sessionId)
        .put("file", file)
        .put("offset", offset)
        .put("data", data)
        .put("last", last);
  }
}
