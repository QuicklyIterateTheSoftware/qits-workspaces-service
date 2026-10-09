package eu.wohlben.qits.workspaces.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.workspaces.control.WorkspaceChangeHint;
import eu.wohlben.qits.workspaces.control.WorkspaceChangeHint.Topic;
import eu.wohlben.qits.workspaces.control.WorkspaceChangePublisher;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code domain} → SSE hint bus end to end through real CDI async delivery: the
 * publisher's {@code fireAsync} reaches an {@code @ObservesAsync} observer ({@link HintCollector}),
 * and a real producer fires the right topic at its
 * choke-point.
 */
@QuarkusTest
class WorkspaceChangeHintBusTest {

  @Inject WorkspaceChangePublisher publisher;

  @Inject HintCollector collector;

  @Inject eu.wohlben.qits.workspaces.control.FakeRepositoryLookup repositories;

  @Inject eu.wohlben.qits.workspaces.control.WorkspaceService workspaceService;

  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /** The collection root; a workspace's channel hangs off its id, which only exists at runtime. */
  @TestHTTPResource("/workspaces/api/workspaces/")
  URL workspacesUrl;

  @TestHTTPResource("/workspaces/api/events")
  URL globalSseUrl;

  @BeforeEach
  void reset() {
    collector.clear();
  }

  /** Drain hints until one for {@code repoId} arrives (ignoring unrelated ones), or time out. */
  private WorkspaceChangeHint awaitHint(String repoId, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    long remaining;
    while ((remaining = deadline - System.currentTimeMillis()) > 0) {
      WorkspaceChangeHint hint = collector.poll(remaining);
      if (hint == null) {
        return null;
      }
      if (java.util.Objects.equals(hint.repoId(), repoId)) {
        return hint;
      }
    }
    return null;
  }

  @Test
  void firedHintsAreDeliveredToAsyncObservers() throws InterruptedException {
    publisher.fire("repo-bus", 42L, Topic.COMMANDS);

    WorkspaceChangeHint hint = awaitHint("repo-bus", 2000);
    assertNotNull(hint, "expected the fired hint to reach the async observer");
    assertEquals(Topic.COMMANDS, hint.topic());
    assertEquals(42L, hint.workspaceRowId());
  }


  /**
   * The workspace channel is addressed by the workspace's id, so this needs a real one — the route
   * resolves it and 404s otherwise — and the hint that has to reach it names the same id.
   */
  @Test
  void theSseEndpointStreamsAHintFrameOverHttp() throws Exception {
    String repoId = eu.wohlben.qits.workspaces.control.TestOrigin.create(dataDir);
    repositories.register(repoId);
    var workspace = workspaceService.createWorkspace(repoId, "wt-sse", "master", "wt-sse");

    URL sseUrl = new URL(workspacesUrl, workspace.id + "/events");
    assertSseDataFrame(
        sseUrl, () -> publisher.fire(repoId, workspace.id, Topic.FILES), "files");
  }

  @Test
  void theGlobalSseEndpointStreamsAHintFrameOverHttp() throws Exception {
    // The global channel (key (null, null)) carries the agent-activity mirror the project detail
    // route subscribes to.
    assertSseDataFrame(
        globalSseUrl, () -> publisher.fire(null, null, Topic.AGENT_ACTIVITY), "agent-activity");
  }

  /** Open {@code url} as an SSE stream, run {@code fire}, and expect a {@code data: expected}. */
  private void assertSseDataFrame(URL url, Runnable fire, String expected) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(url.toURI())
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

    // Open the stream; when send() returns, the server has begun the response and subscribed.
    HttpResponse<InputStream> response =
        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());

    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader in =
                  new BufferedReader(
                      new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                  lines.add(line);
                }
              } catch (Exception ignored) {
                // stream closed at test teardown — expected
              }
            });
    reader.setDaemon(true);
    reader.start();

    Thread.sleep(400); // let the subscription settle before firing
    fire.run();

    // Read frames until the expected data line arrives (ignoring heartbeat/blank/comment lines).
    long deadline = System.currentTimeMillis() + 3000;
    boolean seen = false;
    long remaining;
    while (!seen && (remaining = deadline - System.currentTimeMillis()) > 0) {
      String line = lines.poll(remaining, TimeUnit.MILLISECONDS);
      if (line != null && line.startsWith("data:") && line.substring(5).trim().equals(expected)) {
        seen = true;
      }
    }
    Assertions.assertTrue(seen, "expected a 'data: " + expected + "' SSE frame over HTTP");
  }
}
