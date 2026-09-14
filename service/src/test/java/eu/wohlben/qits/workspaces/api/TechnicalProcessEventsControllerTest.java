package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.SharedTuningProfile;
import eu.wohlben.qits.workspaces.control.TechnicalProcess;
import eu.wohlben.qits.workspaces.control.TechnicalProcessRegistry;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The technical-process SSE endpoint over real HTTP: a terminal process replays everything and
 * completes; a live process streams replay-then-live-then-done; the heartbeat pings; an unknown id
 * is a 404 (fatal to {@code EventSource}, so no retry loop).
 */
@QuarkusTest
@TestProfile(SharedTuningProfile.class)
public class TechnicalProcessEventsControllerTest {

  @Inject TechnicalProcessRegistry registry;

  @TestHTTPResource("/workspaces/api/technical-processes")
  URL baseUrl;

  private HttpResponse<InputStream> open(String processId) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(java.net.URI.create(baseUrl + "/" + processId + "/events"))
            .header("Accept", "text/event-stream")
            .GET()
            .build();
    HttpResponse<InputStream> response =
        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());
    return response;
  }

  /** Reads SSE {@code data:} payloads off the response on a daemon thread. */
  private static BlockingQueue<String> dataLines(HttpResponse<InputStream> response) {
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader in =
                  new BufferedReader(
                      new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                  if (line.startsWith("data:")) {
                    lines.add(line.substring("data:".length()).trim());
                  }
                }
                lines.add("<eof>");
              } catch (Exception ignored) {
                // stream closed at teardown
              }
            });
    reader.setDaemon(true);
    reader.start();
    return lines;
  }

  private static String await(BlockingQueue<String> lines, String needle) throws Exception {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      String line = lines.poll(200, TimeUnit.MILLISECONDS);
      if (line != null && line.contains(needle)) {
        return line;
      }
    }
    return null;
  }

  @Test
  public void aTerminalProcessReplaysAllFramesAndCompletesTheStream() throws Exception {
    TechnicalProcess process = registry.begin("repo-sse", "ws-terminal", 1L);
    process.openSegment("container");
    process.appendLine("container", "created abc");
    process.settleSegment("container", true);
    process.completeNoOp("container-start", "already running");

    // The stream completes server-side after done, so a plain full-body read terminates.
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        java.net.URI.create(baseUrl + "/" + process.id() + "/events"))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    String body = response.body();
    assertTrue(body.contains("\"segment-open\""), body);
    assertTrue(body.contains("created abc"), body);
    assertTrue(body.contains("\"done\""), body);
    int openIdx = body.indexOf("segment-open");
    int doneIdx = body.indexOf("\"done\"");
    assertTrue(openIdx < doneIdx, "replay precedes the terminal done frame");
  }

  @Test
  public void aLiveProcessStreamsReplayThenLiveThenDone() throws Exception {
    TechnicalProcess process = registry.begin("repo-sse", "ws-live", 1L);
    process.openSegment("clone");
    process.appendLine("clone", "replayed-line");

    BlockingQueue<String> lines = dataLines(open(process.id()));
    assertNotNull(await(lines, "replayed-line"), "buffered lines replay on connect");

    process.appendLine("clone", "live-line");
    assertNotNull(await(lines, "live-line"), "live lines keep streaming");

    process.settleSegment("clone", true);
    process.completeNoOp("container-start", "noop");
    assertNotNull(await(lines, "\"done\""), "the terminal done frame arrives");
    assertNotNull(await(lines, "<eof>"), "the stream completes after done");
  }

  @Test
  public void theHeartbeatPingsALiveStream() throws Exception {
    TechnicalProcess process = registry.begin("repo-sse", "ws-ping", 1L);
    BlockingQueue<String> lines = dataLines(open(process.id()));

    assertNotNull(await(lines, "\"ping\""), "a ping frame arrives within the heartbeat period");
    process.forceFinish();
  }

  @Test
  public void anUnknownProcessIdIsA404() {
    given()
        .when()
        .get("/workspaces/api/technical-processes/no-such-process/events")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void frameJsonCarriesSegmentLineAndStatusFields() throws Exception {
    // The wire contract: line frames carry segment+line, settled frames carry segment+status.
    TechnicalProcess process = registry.begin("repo-sse", "ws-shape", 1L);
    process.openSegment("clone");
    process.appendLine("clone", "hello");
    process.settleSegment("clone", false);
    process.failProvision(null);

    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        java.net.URI.create(baseUrl + "/" + process.id() + "/events"))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    List<String> data =
        response.body().lines().filter(l -> l.startsWith("data:")).map(String::trim).toList();
    assertTrue(data.stream().anyMatch(l -> l.contains("\"clone\"") && l.contains("\"hello\"")));
    assertTrue(data.stream().anyMatch(l -> l.contains("\"failed\"")));
  }
}
