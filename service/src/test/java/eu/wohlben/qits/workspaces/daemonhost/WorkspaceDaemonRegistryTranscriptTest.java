package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.transcripthost.ShippedClaudeTranscripts;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.TranscriptChunk;
import eu.wohlben.qits.workspacedaemon.protocol.TranscriptResync;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * qits-1752's ingest, over a real control socket: a {@link TranscriptChunk} from a workspace's
 * daemon lands in the transcript store for that workspace's own agent, a chunk that does not start
 * where the store ends is answered with a {@link TranscriptResync} on the same socket, and a chunk
 * for another workspace's agent is never written.
 *
 * <p>The workspace rows are resolved slots, so no scheduler pass ever offers them to another test's
 * agent; the agents are STOPPED, so none is ever driven. Neither matters to the ingest, which reads
 * only whom an agent row is placed with.
 */
@QuarkusTest
class WorkspaceDaemonRegistryTranscriptTest {

  private static final String SESSION = "aaaaaaaa-1111-4111-8111-000000000001";

  @Inject Vertx vertx;

  @Inject DaemonMessageCodec codec;

  @Inject WorkspaceRepository workspaces;

  @Inject AgentWorktreeRepository agents;

  @Inject ShippedClaudeTranscripts transcripts;

  @TestHTTPResource("/")
  URI base;

  @Test
  void aChunkForTheWorkspacesOwnAgentIsStoredAndAMismatchIsAnsweredWithAResync() throws Exception {
    Long row = slot();
    UUID agent = agent(row, null);
    String line = "{\"type\":\"user\",\"message\":{\"content\":\"go\"}}\n";
    long size = line.getBytes(StandardCharsets.UTF_8).length;

    try (Peer peer = connect(row)) {
      peer.send(new TranscriptChunk(agent.toString(), "cmd-1", SESSION, "main", 0, line));
      await(() -> line.equals(read(mainFile(agent))));

      // The same chunk again (a duplicate, or a restarted daemon at 0): resync to what is held.
      peer.send(new TranscriptChunk(agent.toString(), "cmd-1", SESSION, "main", 0, line));
      TranscriptResync duplicate = assertInstanceOf(TranscriptResync.class, peer.take());
      assertEquals(new TranscriptResync(SESSION, "main", size), duplicate);

      // A gap (a frame lost to a reconnect): nothing written, resync to the same held size.
      peer.send(new TranscriptChunk(agent.toString(), "cmd-1", SESSION, "main", size + 100, "x\n"));
      assertEquals(new TranscriptResync(SESSION, "main", size), peer.take());
      assertEquals(line, read(mainFile(agent)));
    }
  }

  /** A frame naming no agent is resolved by its command, among the workspace's own agents. */
  @Test
  void aChunkNamingNoAgentIsResolvedByItsCommand() throws Exception {
    Long row = slot();
    UUID agent = agent(row, "cmd-by-command-" + row);
    String line = "{\"type\":\"user\",\"message\":{\"content\":\"by command\"}}\n";

    try (Peer peer = connect(row)) {
      peer.send(new TranscriptChunk(null, "cmd-by-command-" + row, SESSION, "main", 0, line));
      await(() -> line.equals(read(mainFile(agent))));
    }
  }

  /**
   * A daemon may ship only its own agents' sessions. A chunk naming an agent placed in another
   * workspace — by id, or by a command only that agent runs — is dropped: nothing is written and
   * nothing is answered. The own agent's chunk sent after it is the witness that it was processed
   * rather than still queued: the ingest is one ordered thread.
   */
  @Test
  void aChunkForAnotherWorkspacesAgentIsDropped() throws Exception {
    Long row = slot();
    Long elsewhere = slot();
    UUID own = agent(row, null);
    UUID foreign = agent(elsewhere, "cmd-foreign-" + elsewhere);
    String line = "{\"type\":\"user\",\"message\":{\"content\":\"not yours\"}}\n";

    try (Peer peer = connect(row)) {
      peer.send(new TranscriptChunk(foreign.toString(), "cmd-x", SESSION, "main", 0, line));
      peer.send(new TranscriptChunk(null, "cmd-foreign-" + elsewhere, SESSION, "main", 0, line));
      peer.send(new TranscriptChunk(own.toString(), "cmd-own", SESSION, "main", 0, line));
      await(() -> line.equals(read(mainFile(own))));

      assertFalse(Files.exists(mainFile(foreign)), "another workspace's agent was written");
      assertNull(peer.inbound.poll(200, TimeUnit.MILLISECONDS), "a dropped chunk was answered");
    }
  }

  // --- fixtures ----------------------------------------------------------------------------------

  private Path mainFile(UUID agent) {
    return transcripts.store().agentDirectory(agent).resolve(SESSION + ".jsonl");
  }

  private static String read(Path file) {
    try {
      return Files.isRegularFile(file) ? Files.readString(file) : null;
    } catch (java.io.IOException e) {
      return null;
    }
  }

  /** A resolved TARGET slot (no branch): a real row for the agents' foreign key. */
  private Long slot() {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Workspace workspace = new Workspace();
              String label = "t" + UUID.randomUUID().toString().substring(0, 8);
              workspace.workspaceId = label;
              workspace.repositoryId = "repo-" + label;
              workspace.status = WorkspaceStatus.ABANDONED;
              workspace.resolvedAt = Instant.now();
              workspace.placement = WorkspacePlacement.TARGET;
              workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
              workspaces.persist(workspace);
              workspaces.flush();
              return workspace.id;
            });
  }

  private UUID agent(Long workspaceId, String commandId) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = "repo-transcripts";
              row.workId = "w-" + id;
              row.wrapperBranch = "ticket/w-" + id;
              row.workspaceId = workspaceId;
              row.runState = AgentRunState.STOPPED;
              row.commandId = commandId;
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              agents.persist(row);
            });
    return id;
  }

  private Peer connect(Long row) throws Exception {
    WebSocketClient client = vertx.createWebSocketClient();
    BlockingQueue<DaemonMessage> inbound = new LinkedBlockingQueue<>();
    WebSocketConnectOptions options =
        new WebSocketConnectOptions()
            .setHost(base.getHost())
            .setPort(base.getPort())
            .setURI("/workspaces/daemon/" + row);
    WebSocket ws =
        client.connect(options).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    ws.textMessageHandler(text -> inbound.add(codec.decode(text)));
    return new Peer(client, ws, inbound, codec);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within 5s");
      }
      TimeUnit.MILLISECONDS.sleep(25);
    }
  }

  private record Peer(
      WebSocketClient client,
      WebSocket ws,
      BlockingQueue<DaemonMessage> inbound,
      DaemonMessageCodec codec)
      implements AutoCloseable {

    void send(DaemonMessage message) {
      ws.writeTextMessage(codec.encode(message));
    }

    DaemonMessage take() throws InterruptedException {
      DaemonMessage message = inbound.poll(5, TimeUnit.SECONDS);
      if (message == null) {
        throw new AssertionError("expected a frame from the backend but none arrived");
      }
      return message;
    }

    @Override
    public void close() {
      ws.close();
      client.close();
    }
  }
}
