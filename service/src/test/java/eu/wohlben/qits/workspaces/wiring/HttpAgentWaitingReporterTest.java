package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpAgentWaitingReporter} against a real HTTP server and a real REST client, for {@link
 * HttpRepositoryLookupTest}'s reason: which answers are tolerated depends on the exception types the
 * generated client actually throws, and a mocked client would only assert assumptions about them.
 *
 * <p>Pinned: the request qits-projects' door reads (path, bearer, body), a 404 and a 405 from a
 * qits-projects older than the door are swallowed, and any other refusal throws for the relay to
 * log.
 */
@QuarkusTest
public class HttpAgentWaitingReporterTest {

  private HttpServer server;

  private final List<String> paths = new CopyOnWriteArrayList<>();
  private final List<String> bearers = new CopyOnWriteArrayList<>();
  private final List<String> bodies = new CopyOnWriteArrayList<>();

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  /** A stub answering every request with {@code status} and no body. */
  private String serve(int status) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
          bearers.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static HttpAgentWaitingReporter reporterAgainst(String baseUrl) {
    HttpAgentWaitingReporter reporter = new HttpAgentWaitingReporter();
    reporter.projectsBearer =
        new IdpProjectsBearer() {
          @Override
          public Optional<String> authorization() {
            return Optional.of("Bearer machine");
          }
        };
    reporter.door =
        QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(baseUrl))
            .build(ProjectsAgentWaiting.class);
    return reporter;
  }

  @Test
  public void theReportIsPostedToTheWorkItemsDoorWithTheServiceBearer() throws Exception {
    String base = serve(204);

    reporterAgainst(base).report("8d1c-work", true, "Stop", "s-1", 1234L);

    assertEquals(List.of("POST /projects/api/work/8d1c-work/agent-waiting"), paths);
    assertEquals(List.of("Bearer machine"), bearers);
    JsonNode body = new ObjectMapper().readTree(bodies.get(0));
    assertEquals(true, body.path("waiting").asBoolean());
    assertEquals("Stop", body.path("cause").asText());
    assertEquals("s-1", body.path("sessionId").asText());
    assertEquals(1234L, body.path("at").asLong());
  }

  @Test
  public void aQitsProjectsWithoutTheDoorIsTolerated() throws Exception {
    reporterAgainst(serve(404)).report("w", false, "UserPromptSubmit", "s-1", 1L);
    stopServer();
    HttpAgentWaitingReporter reporter = reporterAgainst(serve(405));
    reporter.report("w", true, "Stop", "s-1", 2L);
    reporter.report("w", false, "UserPromptSubmit", "s-1", 3L);
  }

  @Test
  public void anyOtherRefusalThrowsForTheRelayToLog() throws Exception {
    HttpAgentWaitingReporter reporter = reporterAgainst(serve(500));

    assertThrows(IllegalStateException.class, () -> reporter.report("w", true, "Stop", null, 1L));
  }
}
