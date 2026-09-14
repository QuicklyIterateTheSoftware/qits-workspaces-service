package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspaces.control.CaptureService;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.SharedTuningProfile;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.dto.CaptureContent;
import eu.wohlben.qits.workspaces.entity.Workspace;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(SharedTuningProfile.class)
public class CaptureResourceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject CaptureService captureService;

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /**
   * A resolvable repository with a bare origin on disk, replacing the monorepo's create-project +
   * clone-fixture pair of REST calls into the projects context.
   *
   * <p>{@code withFeatureBranch} is what the two fixture bares used to encode: {@code
   * testing-repo.git} had a branch literally named {@code feature} (which blocks {@code feature/*}
   * and forces capture's dash fallback), {@code testing-repo-quarkus-angular.git} did not.
   */
  private String repository(boolean withFeatureBranch) throws Exception {
    String repoId = TestOrigin.create(dataDir, withFeatureBranch);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    return repoId;
  }

  private String repository() throws Exception {
    return repository(true);
  }

  private static byte[] payload(String repoId) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("capturedAt", "2026-07-14T14:32:11Z");
    root.put("identity", identity(repoId, "master"));
    root.put(
        "page",
        Map.of(
            "url", "http://app.example:4200/greeting/anna",
            "appPath", "greeting/anna",
            "routePattern", "greeting/:name",
            "title", "Greeting"));
    root.put(
        "environment",
        Map.of(
            "viewport", Map.of("width", 1440, "height", 900, "devicePixelRatio", 2),
            "userAgent", "Mozilla/5.0 (Test)",
            "prefersColorScheme", "dark"));
    String html = "<html style=\"color: black\"><body>hello anna</body></html>";
    root.put("dom", Map.of("html", html, "truncated", false, "bytes", html.length()));
    String selHtml = "<app-greeting style=\"color: black\"><button>Go</button></app-greeting>";
    root.put(
        "selection",
        Map.of(
            "html",
            selHtml,
            "truncated",
            false,
            "bytes",
            selHtml.length(),
            "selector",
            "#go",
            "tag",
            "button",
            "component",
            "app-greeting"));
    root.put("state", Map.of("cart", Map.of("items", 2)));
    return toJson(root);
  }

  private static Map<String, Object> identity(String repoId, String workspaceId) {
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("qits.repository.id", repoId);
    identity.put("qits.workspace.id", workspaceId);
    return identity;
  }

  private static byte[] toJson(Map<String, Object> root) {
    try {
      return MAPPER.writeValueAsBytes(root);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] gzip(byte[] data) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
        gz.write(data);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  public void testCaptureCreatesBranchWorkspaceAndGoal() throws Exception {
    String repoId = repository();

    var response =
        given()
            .contentType(ContentType.JSON)
            .header("Origin", "http://app.example:4200")
            .body(payload(repoId))
            .when()
            .post("/workspaces/api/capture")
            .then()
            .statusCode(Response.Status.CREATED.getStatusCode())
            .body(
                "workspace.workspaceId",
                matchesPattern("feature-\\d{4}-\\d{2}-\\d{2}-\\d{4}(-\\d+)?"))
            .body("workspace.id", notNullValue())
            .extract();

    String workspaceId = response.path("workspace.workspaceId");
    String branch = response.path("workspace.branch");
    String url = response.path("url");

    // testing-repo's bare `feature` branch blocks feature/* refs, so this repo gets the dash
    // fallback shape — branch name and workspace id coincide.
    assertEquals(workspaceId, branch);
    // Host-first: the URL points at the address the request reached qits on — NOT the app's
    // Origin (http://app.example:4200), where /repositories/… doesn't exist.
    String expectedBase = "http://localhost:" + io.restassured.RestAssured.port;
    assertEquals(
        expectedBase + "/repositories/" + repoId + "/workspaces/" + workspaceId + "/wip", url);

    given()
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.workspace.workspaceId", hasItem(workspaceId))
        .body(
            "entries.find { it.workspace.workspaceId == '" + workspaceId + "' }.workspace.parent",
            equalTo("master"))
        .body(
            "entries.find { it.workspace.workspaceId == '" + workspaceId + "' }.workspace.branch",
            equalTo(branch))
        .body(
            "entries.find { it.workspace.workspaceId == '"
                + workspaceId
                + "' }.workspace.runtimeStatus",
            equalTo("STOPPED"))
        .body(
            "entries.find { it.workspace.workspaceId == '" + workspaceId + "' }.workspace.preamble",
            allOf(
                containsString("greeting/:name"),
                containsString("http://app.example:4200/greeting/anna"),
                containsString("**Source workspace**: `master`"),
                containsString("## App state at capture"),
                containsString("\"items\" : 2"),
                containsString("## Selected component (style-frozen)"),
                containsString("**Picked**: `button` in `app-greeting` — `#go`"),
                containsString("<app-greeting"),
                containsString("<details><summary>"),
                containsString("hello anna")));
  }

  @Test
  public void testSameInstantCapturesGetSuffixedNames() throws Exception {
    // The slash-safe fixture (no bare `feature` branch) exercises the normal feature/<ts> shape.
    String repoId = repository(false);
    // Straight through the service seam with one fixed instant — a REST-level double post would
    // flake at minute boundaries.
    Instant fixed = Instant.parse("2026-07-14T12:00:11Z");
    CaptureContent content = new CaptureContent(null, null, null, null, null, null, null);

    Workspace first = captureService.capture(repoId, content, fixed);
    Workspace second = captureService.capture(repoId, content, fixed);
    Workspace third = captureService.capture(repoId, content, fixed);

    assertEquals("feature/2026-07-14-1200", first.branch);
    assertEquals("feature-2026-07-14-1200", first.workspaceId);
    assertEquals("feature/2026-07-14-1200-2", second.branch);
    assertEquals("feature-2026-07-14-1200-2", second.workspaceId);
    assertEquals("feature/2026-07-14-1200-3", third.branch);
    // TestOrigin's main branch, where the monorepo fixture's was `main`.
    assertEquals("master", first.parent);

    given()
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .body("entries.workspace.workspaceId", hasItems(first.workspaceId, second.workspaceId));
  }

  @Test
  public void testUnknownOrMissingRepositoryIs404AndCreatesNothing() throws Exception {
    String repoId = repository();

    Map<String, Object> unknownRepo = new LinkedHashMap<>();
    unknownRepo.put("identity", identity(UUID.randomUUID().toString(), null));
    given()
        .contentType(ContentType.JSON)
        .body(toJson(unknownRepo))
        .when()
        .post("/workspaces/api/capture")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode())
        .body("message", containsString("Repository not found"));

    given()
        .contentType(ContentType.JSON)
        .body("{}".getBytes(StandardCharsets.UTF_8))
        .when()
        .post("/workspaces/api/capture")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());

    // Nothing leaked: only the auto-created main workspace exists.
    given()
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .body("entries", hasSize(1));
  }

  @Test
  public void testOversizedPayloadIs413AndCreatesNothing() throws Exception {
    String repoId = repository();

    // (a) identity-encoded body over the 8192-byte cap
    Map<String, Object> big = new LinkedHashMap<>();
    big.put("identity", identity(repoId, null));
    big.put("dom", Map.of("html", "x".repeat(10_000), "truncated", false, "bytes", 10_000));
    given()
        .contentType(ContentType.JSON)
        .body(toJson(big))
        .when()
        .post("/workspaces/api/capture")
        .then()
        .statusCode(413);

    // (b) gzip bomb: ~1 MB decompressed, a few hundred bytes on the wire — the cap must trip
    // DURING inflation.
    Map<String, Object> bomb = new LinkedHashMap<>();
    bomb.put("identity", identity(repoId, null));
    bomb.put("dom", Map.of("html", "a".repeat(1_000_000), "truncated", false, "bytes", 1_000_000));
    given()
        .contentType(ContentType.JSON)
        .header("Content-Encoding", "gzip")
        .body(gzip(toJson(bomb)))
        .when()
        .post("/workspaces/api/capture")
        .then()
        .statusCode(413);

    given()
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .body("entries", hasSize(1));
  }

  @Test
  public void testGzipAndIdentityEncodingsBothAccepted() throws Exception {
    String repoId = repository();

    given()
        .contentType(ContentType.JSON)
        .body(payload(repoId))
        .when()
        .post("/workspaces/api/capture")
        .then()
        .statusCode(Response.Status.CREATED.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .header("Content-Encoding", "gzip")
        .body(gzip(payload(repoId)))
        .when()
        .post("/workspaces/api/capture")
        .then()
        .statusCode(Response.Status.CREATED.getStatusCode());
  }

  @Test
  public void testCorsIsOpenOnCapturePathOnly() throws Exception {
    String repoId = repository();

    // Preflight answers permissively.
    given()
        .header("Origin", "http://foreign.example")
        .header("Access-Control-Request-Method", "POST")
        .when()
        .options("/workspaces/api/capture")
        .then()
        .statusCode(204)
        .header("Access-Control-Allow-Origin", equalTo("*"))
        .header("Access-Control-Allow-Methods", containsString("POST"))
        .header("Access-Control-Allow-Headers", containsString("Content-Type"))
        .header("Access-Control-Allow-Headers", containsString("Content-Encoding"));

    // The POST response echoes the header — success and error alike (a browser can only read a
    // cross-origin error status if the header is present).
    given()
        .contentType(ContentType.JSON)
        .header("Origin", "http://foreign.example")
        .body(payload(repoId))
        .when()
        .post("/workspaces/api/capture")
        .then()
        .statusCode(Response.Status.CREATED.getStatusCode())
        .header("Access-Control-Allow-Origin", equalTo("*"));

    Map<String, Object> unknownRepo = new LinkedHashMap<>();
    unknownRepo.put("identity", identity(UUID.randomUUID().toString(), null));
    given()
        .contentType(ContentType.JSON)
        .header("Origin", "http://foreign.example")
        .body(toJson(unknownRepo))
        .when()
        .post("/workspaces/api/capture")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode())
        .header("Access-Control-Allow-Origin", equalTo("*"));

    // Provably scoped: a sibling ingest endpoint gets none of it.
    given()
        .header("Origin", "http://foreign.example")
        .contentType("application/x-protobuf")
        .body(new byte[0])
        .when()
        .post("/workspaces/api/otel/v1/traces")
        .then()
        .header("Access-Control-Allow-Origin", nullValue());
    given()
        .header("Origin", "http://foreign.example")
        .header("Access-Control-Request-Method", "POST")
        .when()
        .options("/workspaces/api/otel/v1/traces")
        .then()
        .header("Access-Control-Allow-Origin", nullValue());
  }

  @Test
  public void testStateLessCaptureOmitsStateSection() throws Exception {
    String repoId = repository();

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("identity", identity(repoId, null));
    String html = "<html><body>bare</body></html>";
    root.put("dom", Map.of("html", html, "truncated", false, "bytes", html.length()));

    String workspaceId =
        given()
            .contentType(ContentType.JSON)
            .body(toJson(root))
            .when()
            .post("/workspaces/api/capture")
            .then()
            .statusCode(Response.Status.CREATED.getStatusCode())
            .extract()
            .path("workspace.workspaceId");

    given()
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .body(
            "entries.find { it.workspace.workspaceId == '" + workspaceId + "' }.workspace.preamble",
            allOf(
                not(containsString("## App state at capture")),
                containsString("**Source workspace**: —"),
                containsString("bare")));
  }
}
