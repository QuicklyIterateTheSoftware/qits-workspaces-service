package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.transcripthost.TranscriptStore;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The host's half of restoring a lost session (qits-2088): what makes a start restorable, and the
 * chunks the transcript store's copy goes back to the daemon in — plain JUnit against a temp store,
 * the daemon call a recording seam.
 */
class SessionRestoreTest {

  private static final String AGENT = "0f0f0f0f-0000-4000-8000-000000000001";

  private static final String SESSION = "11111111-1111-1111-1111-111111111111";

  private static final String MAIN = "{\"type\":\"user\",\"text\":\"héllo\"}\n{\"type\":\"assistant\"}\n";

  private static final String SIDECHAIN = "{\"isSidechain\":true}\n";

  @TempDir Path root;

  private TranscriptStore held() throws IOException {
    TranscriptStore store = new TranscriptStore(root);
    store.append(UUID.fromString(AGENT), SESSION, "main", 0, MAIN);
    store.append(UUID.fromString(AGENT), SESSION, "subagents/agent-a1.jsonl", 0, SIDECHAIN);
    return store;
  }

  @Test
  void aSessionTheStoreHoldsIsRestorableAndNoOtherIs() throws IOException {
    TranscriptStore store = held();

    assertTrue(SessionRestore.holds(store, AGENT, SESSION));
    assertFalse(SessionRestore.holds(store, AGENT, "22222222-2222-2222-2222-222222222222"));
    assertFalse(SessionRestore.holds(store, "0f0f0f0f-0000-4000-8000-000000000002", SESSION));
    assertFalse(SessionRestore.holds(store, AGENT, null), "a fresh agent has no session");
    assertFalse(SessionRestore.holds(store, "not-a-uuid", SESSION));
    assertFalse(SessionRestore.holds(store, AGENT, "../escape"));
  }

  @Test
  void everyHeldFileGoesBackInContiguousChunksTheMainFileLast() throws IOException {
    List<JsonObject> sent = new ArrayList<>();

    SessionRestore.Outcome outcome =
        SessionRestore.restore(
            held(),
            AGENT,
            SESSION,
            chunk -> {
              sent.add(chunk);
              return 200;
            },
            8);

    assertEquals(SessionRestore.Result.RESTORED, outcome.result(), outcome.detail());
    Map<String, StringBuilder> files = new LinkedHashMap<>();
    Map<String, Long> next = new LinkedHashMap<>();
    for (JsonObject chunk : sent) {
      assertEquals(SESSION, chunk.getString("sessionId"));
      String file = chunk.getString("file");
      assertEquals(next.getOrDefault(file, 0L), chunk.getLong("offset"), "contiguous: " + chunk);
      files.computeIfAbsent(file, f -> new StringBuilder()).append(chunk.getString("data"));
      next.put(
          file,
          chunk.getLong("offset")
              + chunk.getString("data").getBytes(StandardCharsets.UTF_8).length);
    }
    assertEquals(List.of("subagents/agent-a1.jsonl", "main"), List.copyOf(files.keySet()));
    assertEquals(SIDECHAIN, files.get("subagents/agent-a1.jsonl").toString());
    assertEquals(MAIN, files.get("main").toString());
    // Exactly one last chunk per file, and it is that file's final one.
    for (String file : files.keySet()) {
      List<JsonObject> ofFile = sent.stream().filter(c -> file.equals(c.getString("file"))).toList();
      for (int i = 0; i < ofFile.size(); i++) {
        assertEquals(i == ofFile.size() - 1, ofFile.get(i).getBoolean("last"), file);
      }
    }
    assertEquals("main", sent.get(sent.size() - 1).getString("file"));
  }

  @Test
  void aChunkTheDaemonRefusesStopsTheRestoreBeforeTheMainFile() throws IOException {
    List<JsonObject> sent = new ArrayList<>();

    SessionRestore.Outcome outcome =
        SessionRestore.restore(
            held(),
            AGENT,
            SESSION,
            chunk -> {
              sent.add(chunk);
              return 500;
            },
            SessionRestore.CHUNK_BYTES);

    assertEquals(SessionRestore.Result.FAILED, outcome.result());
    assertTrue(outcome.detail().contains("500"), outcome.detail());
    assertEquals(1, sent.size(), "nothing after the refusal");
    assertEquals("subagents/agent-a1.jsonl", sent.get(0).getString("file"));
  }

  @Test
  void aDaemonThatDoesNotAnswerFailsTheRestore() throws IOException {
    SessionRestore.Outcome outcome =
        SessionRestore.restore(held(), AGENT, SESSION, chunk -> -1, SessionRestore.CHUNK_BYTES);

    assertEquals(SessionRestore.Result.FAILED, outcome.result());
    assertTrue(outcome.detail().contains("nothing"), outcome.detail());
  }

  @Test
  void aSessionTheStoreDoesNotHoldSendsNothing() throws IOException {
    List<JsonObject> sent = new ArrayList<>();

    SessionRestore.Outcome outcome =
        SessionRestore.restore(
            held(),
            AGENT,
            "22222222-2222-2222-2222-222222222222",
            chunk -> {
              sent.add(chunk);
              return 200;
            },
            SessionRestore.CHUNK_BYTES);

    assertEquals(SessionRestore.Result.NO_COPY, outcome.result());
    assertTrue(sent.isEmpty());
  }
}
