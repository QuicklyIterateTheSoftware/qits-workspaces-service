package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpRepositoryLookup} against a real HTTP server and a real REST client, because what is
 * worth pinning here is not the happy path — it is <b>which failures are an answer and which are an
 * error</b>, and that mapping depends entirely on the exception types the generated client actually
 * throws. A test with a mocked client would assert my assumptions about those types rather than the
 * types themselves, and would keep passing if the extension changed them.
 *
 * <p>So: a {@code com.sun.net.httpserver} stub (already in the JDK, no dependency, no container) on
 * an ephemeral port, and a client built by {@link QuarkusRestClientBuilder} against it. The bean is
 * constructed directly rather than injected, so each case can set the address it is testing —
 * including the unwired one, which no injected configuration could express.
 *
 * <p><b>The ordinary answers are qits-projects' own recordings</b> ({@link GoldenMasters}, epic
 * qits-546), not hand-written documents: a repository that exists, one that does not, and a
 * project's listing. The expectations read their values out of the same recording, so a newer
 * golden master moves fixture and assertion together. The deliberately malformed answers — no
 * name, an unknown field, a 500, not JSON — stay hand-written: they are what the provider does NOT
 * say, which no recording can supply.
 */
@QuarkusTest
public class HttpRepositoryLookupTest {

  private HttpServer server;
  private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  /** Starts a stub answering every request with the given status and body. */
  private String serve(int status, String body) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requestedPaths.add(exchange.getRequestURI().getPath());
          byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** The bean wired to {@code baseUrl}, with a real generated client pointed at the same place. */
  static HttpRepositoryLookup lookupAgainst(String baseUrl) {
    HttpRepositoryLookup lookup = new HttpRepositoryLookup();
    lookup.baseUrl = Optional.ofNullable(baseUrl);
    lookup.projectsBearer =
        new IdpProjectsBearer() {
          @Override
          public Optional<String> authorization() {
            return Optional.empty();
          }
        };
    if (baseUrl != null) {
      lookup.repositories =
          QuarkusRestClientBuilder.newBuilder()
              .baseUri(URI.create(baseUrl))
              .build(ProjectsRepositories.class);
      lookup.projectRepositories =
          QuarkusRestClientBuilder.newBuilder()
              .baseUri(URI.create(baseUrl))
              .build(ProjectsProjectRepositories.class);
    }
    return lookup;
  }

  /**
   * The row id and the name are read as two separate answers, and the recording has them differ:
   * a repository the projects self-seed registered carries a UUID id, and a view that quietly
   * reported the id as the name would name a repository nothing committed can address.
   */
  @Test
  public void aKnownRepositoryYieldsItsIdNameProjectAndMainBranch() throws Exception {
    String base = serve(200, GoldenMasters.body("a repository exists", "getRepository"));
    JsonNode recorded =
        GoldenMasters.json("a repository exists", "getRepository").path("repository");
    String id = recorded.path("id").asText();
    assertTrue(
        !id.equals(recorded.path("name").asText()), "the recording keeps id and name apart");

    Optional<RepositoryLookup.RepositoryView> found = lookupAgainst(base).find(id);

    assertTrue(found.isPresent());
    assertEquals(id, found.get().id());
    assertEquals(
        recorded.path("name").asText(), found.get().name(), "the view names the repository");
    assertEquals(recorded.path("mainBranch").asText(), found.get().mainBranch());
    assertEquals(
        recorded.path("projectId").asText(), found.get().projectId(), "the view names the project");
  }

  /** A registry answering with no name resolves anyway: no flow may depend on the field. */
  @Test
  public void aRepositoryWithNoNameStillResolves() throws Exception {
    String base = serve(200, "{\"repository\":{\"id\":\"repo-1\",\"mainBranch\":\"main\"}}");

    Optional<RepositoryLookup.RepositoryView> found = lookupAgainst(base).find("repo-1");

    assertTrue(found.isPresent());
    assertEquals(null, found.get().name());
  }

  /**
   * Fields qits-projects sends that this context does not bind are ignored rather than fatal. That
   * is what lets that service evolve its RepositoryDto without breaking this one, so it is asserted
   * rather than assumed — the case above already carries url and archetype; this one adds a field
   * that does not exist yet.
   */
  @Test
  public void anUnknownFieldFromProjectsIsNotFatal() throws Exception {
    String base =
        serve(200, "{\"repository\":{\"id\":\"repo-1\",\"mainBranch\":\"main\",\"futureField\":7}}");

    assertTrue(lookupAgainst(base).find("repo-1").isPresent());
  }

  /**
   * The path is qits-projects' own gateway segment, spelled in {@link ProjectsRepositories} as a
   * cross-repo contract. If that service moves its segment, this is what notices.
   */
  @Test
  public void theRequestGoesToTheProjectsSegment() throws Exception {
    String base = serve(200, GoldenMasters.body("a repository exists", "getRepository"));

    lookupAgainst(base).find("repo-1");

    assertEquals(List.of("/projects/api/repositories/repo-1"), requestedPaths);
  }

  /**
   * The second cross-repo path, read by aggregate workspace creation: the project's repository
   * listing. Its wrapper field is deliberately not bound, and the recording carries one.
   */
  @Test
  public void theProjectRepositoryListingGoesToTheProjectsSegment() throws Exception {
    String state = "a project with 3 repositories";
    String base = serve(200, GoldenMasters.body(state, "listProjectRepositories"));
    JsonNode entries = GoldenMasters.json(state, "listProjectRepositories").path("entries");
    String projectId = GoldenMasters.params(state).get("projectId");

    List<RepositoryLookup.RepositoryView> found = lookupAgainst(base).listByProject(projectId);

    assertEquals(List.of("/projects/api/projects/" + projectId + "/repositories"), requestedPaths);
    assertEquals(entries.size(), found.size());
    assertEquals(entries.path(0).path("repository").path("name").asText(), found.get(0).name());
    assertEquals(entries.path(1).path("repository").path("id").asText(), found.get(1).id());
  }

  /**
   * An empty list means "this project has no repositories", which for a branch tree means "branch
   * the wrapper alone". An outage must not be able to say that.
   */
  @Test
  public void anUnreachableRegistryThrowsRatherThanReadingAsAnEmptyProject() {
    HttpRepositoryLookup lookup = lookupAgainst("http://127.0.0.1:1");

    assertThrows(IllegalStateException.class, () -> lookup.listByProject("p-1"));
  }

  // --- the public identity: (projectId, repoName) --------------------------------------------

  @Test
  public void anUnknownRepositoryIsEmptyRatherThanAnError() throws Exception {
    String state = "no repository with the given id";
    String base = serve(404, GoldenMasters.body(state, "getRepository"));

    assertTrue(lookupAgainst(base).find(GoldenMasters.params(state).get("repositoryId")).isEmpty());
  }

  /**
   * The distinction this class exists to get right. {@code require()} turns empty into a 404, so
   * reporting an unreachable registry as empty would make a whole-service outage indistinguishable
   * from a user typing a bad id.
   */
  @Test
  public void anUnreachableRegistryThrowsInsteadOfReadingAsNotFound() {
    // Port 1 on loopback: nothing listens, and connecting fails fast.
    HttpRepositoryLookup lookup = lookupAgainst("http://127.0.0.1:1");

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> lookup.find("repo-1"));
    assertTrue(
        failure.getMessage().contains("unreachable"),
        "expected the message to name the outage, got: " + failure.getMessage());
  }

  @Test
  public void aServerErrorThrowsInsteadOfReadingAsNotFound() throws Exception {
    String base = serve(500, "boom");

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> lookupAgainst(base).find("repo-1"));
    assertTrue(
        failure.getMessage().contains("500"),
        "expected the message to carry the status, got: " + failure.getMessage());
  }

  @Test
  public void anUnreadableAnswerThrowsRatherThanLookingLikeAnEmptyRegistry() throws Exception {
    String base = serve(200, "not json at all");

    assertThrows(RuntimeException.class, () -> lookupAgainst(base).find("repo-1"));
  }

  /** Dev and test tolerate no address; a production build never reaches this (see the observer). */
  @Test
  public void withNoAddressConfiguredEveryLookupIsEmpty() {
    assertTrue(lookupAgainst(null).find("repo-1").isEmpty());
  }

  @Test
  public void aBlankRepositoryIdIsNotWorthACall() throws Exception {
    String base = serve(200, GoldenMasters.body("a repository exists", "getRepository"));

    assertTrue(lookupAgainst(base).find("  ").isEmpty());
    assertTrue(requestedPaths.isEmpty(), "a blank id should not reach the network");
  }
}
