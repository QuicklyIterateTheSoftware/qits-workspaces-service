package eu.wohlben.qits.workspaces.contracts;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenFiles;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Records qits-workspaces' provider golden masters</b> — {@code golden-masters/} at the
 * repository root, published as {@code @qits/workspaces-golden-masters} (and its maven twin) for
 * consumers to write their pacts against. The format is qits-projects': one {@code
 * golden-masters/<state-slug>/<operationId>.json} per (state, operation), and {@code index.json}
 * describing them. One addition: {@code frozen.numbers} lists the paths of frozen row ids.
 *
 * <p>It compares by default and fails with a diff per differing file, including a committed file
 * no interaction records any more. {@code -Dgolden.update=true} (or {@code QITS_GOLDEN_UPDATE=true})
 * rewrites instead.
 */
@QuarkusTest
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;
  static final String PROVIDER = "qits-workspaces";

  /**
   * One recorded call.
   *
   * @param path the route, with {@code {param}} placeholders, and the query after a {@code ?}. The
   *     index records the route as {@code path} and the query as its own {@code query} object
   * @param status the status the call must answer
   * @param listFilteredTo the array (a {@code $.a} path, or {@code $} for a root array) reduced to
   *     the entries that mention one of the state's params, or null. Other tests share the store, so
   *     an unfiltered list would hold their rows too
   * @param requestBody the JSON a write sends, recorded into the index as the operation's {@code
   *     body}; null for a read and for a write whose operation takes none (the served openapi says
   *     which, and the recording fails on a mismatch). A string value that is exactly {@code
   *     {param}} is expanded from the state's params for the call, and recorded unexpanded
   * @param fixedStrings {@code $.a[*].b} paths, each with the value it is replaced by before
   *     freezing: values a dependency bump moves (an image version), which would otherwise change
   *     the recording on every bump, and names a test fake makes up. Listed in {@code
   *     frozen.strings}
   */
  record Interaction(
      String state,
      String operationId,
      String method,
      String path,
      int status,
      String listFilteredTo,
      String requestBody,
      Map<String, String> fixedStrings) {

    static Interaction read(String state, String operationId, String path, String listFilteredTo) {
      return new Interaction(state, operationId, "GET", path, 200, listFilteredTo, null, Map.of());
    }

    static Interaction write(
        String state, String operationId, String path, int status, String requestBody) {
      return new Interaction(state, operationId, "POST", path, status, null, requestBody, Map.of());
    }

    /** This interaction, with the version at {@code path} recorded as {@link #FIXED_VERSION}. */
    Interaction fixing(String path) {
      return fixing(path, FIXED_VERSION);
    }

    /** This interaction, with the string at {@code path} recorded as {@code value}. */
    Interaction fixing(String path, String value) {
      Map<String, String> fixed = new TreeMap<>(fixedStrings);
      fixed.put(path, value);
      return new Interaction(
          state, operationId, method, this.path, status, listFilteredTo, requestBody, fixed);
    }
  }

  /** What a version a bump moves is recorded as. */
  static final String FIXED_VERSION = "2026.101.120000";

  private static final String WORKSPACES = "/workspaces/api";

  static final List<Interaction> INTERACTIONS =
      List.of(
          Interaction.read(
              ProviderStates.A_PROJECT_WITH_WORKSPACES_BOUND_TO_WORK_ITEMS,
              "listOpenWorkspaces",
              WORKSPACES + "/work/workspaces",
              "$.entries"),
          Interaction.read(
              ProviderStates.A_PROJECT_WITH_WORKSPACES_BOUND_TO_WORK_ITEMS,
              "listWorkItemWorkspaces",
              WORKSPACES + "/work/{bugTicketId}/workspaces",
              null),
          Interaction.read(
              ProviderStates.A_PROJECT_WITH_WORKSPACES_BOUND_TO_WORK_ITEMS,
              "listAgentDispatchReferences",
              WORKSPACES + "/agent-dispatches/references?ticketId={bugTicketId}",
              null),
          Interaction.read(
              ProviderStates.A_WORK_ITEM_WITH_NO_WORKSPACES,
              "listWorkItemWorkspaces",
              WORKSPACES + "/work/{improvementTicketId}/workspaces",
              null),
          Interaction.read(
              ProviderStates.NO_WORK_ITEM_HAS_AN_OPEN_WORKSPACE,
              "listOpenWorkspaces",
              WORKSPACES + "/work/workspaces",
              null),
          Interaction.write(
              ProviderStates.A_REPOSITORY_WITH_A_BRANCH_FOR_A_TICKET,
              "dispatchAgent",
              WORKSPACES + "/agent-dispatches",
              200,
              "{\"repositoryId\":\"{repositoryId}\",\"branch\":\"{branch}\",\"branchTree\":false,"
                  + "\"ticketId\":\"{ticketId}\",\"workId\":\"{ticketId}\","
                  + "\"entityId\":\"{ticketQualifiedId}\","
                  + "\"entityTitle\":\"Invoice totals are off by one cent\","
                  + "\"entityStatus\":\"REFINED\",\"entityBlocked\":false,"
                  + "\"instruction\":\"Fix contract-00000001-10.\"}")
              .fixing("$.agentIdentity", "contract-workspace-token-subject"),
          Interaction.write(
              ProviderStates.A_WORKSPACE_STANDING_ON_A_TICKETS_BRANCH,
              "markAgentDispatchEntity",
              WORKSPACES + "/agent-dispatches/entity",
              200,
              "{\"repositoryId\":\"{repositoryId}\",\"branch\":\"{branch}\","
                  + "\"title\":\"Invoice totals are off by one cent\",\"status\":\"IMPLEMENTING\","
                  + "\"blocked\":false,\"workId\":\"{ticketId}\"}"),
          Interaction.write(
              ProviderStates.A_WORKSPACE_STANDING_ON_A_TICKETS_BRANCH,
              "markAgentDispatchBlocked",
              WORKSPACES + "/agent-dispatches/blocked",
              200,
              "{\"repositoryId\":\"{repositoryId}\",\"branch\":\"{branch}\",\"blocked\":true,"
                  + "\"workId\":\"{ticketId}\"}"),
          Interaction.write(
              ProviderStates.A_WORKSPACE_STANDING_ON_A_TICKETS_BRANCH,
              "deliverAgentTurn",
              WORKSPACES + "/agent-dispatches/delivery",
              200,
              "{\"repositoryId\":\"{repositoryId}\",\"branch\":\"{branch}\","
                  + "\"text\":\"The ticket moved to REVIEW.\",\"workId\":\"{ticketId}\","
                  + "\"compactFirst\":false}"),
          Interaction.write(
              ProviderStates.A_WORKSPACE_STANDING_ON_A_RELEASED_BRANCH,
              "resolveReleasedBranch",
              WORKSPACES + "/branches/resolution?repositoryId={repositoryId}",
              200,
              "{\"branch\":\"{branch}\",\"target\":\"{version}\",\"commit\":\"{sha}\","
                  + "\"result\":\"released as " + ProviderStates.RELEASED_VERSION + "\"}"),
          Interaction.write(
              ProviderStates.REPOSITORIES_WITH_MERGED_BRANCHES,
              "sweepBranches",
              WORKSPACES + "/gc/branches",
              200,
              "{\"dryRun\":false,\"repositories\":[{\"id\":\"{repositoryId}\","
                  + "\"name\":\"{repositoryName}\",\"mainBranch\":\"master\"}],"
                  + "\"keepPrefixes\":[]}"),
          new Interaction(
              ProviderStates.A_CONFIGURED_WORKSPACE_IMAGE,
              "listLaunchPins",
              "GET",
              WORKSPACES + "/pins",
              200,
              null,
              null,
              Map.of("$.pins[*].version", FIXED_VERSION)),
          Interaction.write(
              ProviderStates.NO_RUNNERS,
              "createRunner",
              WORKSPACES + "/runners",
              201,
              "{\"name\":\"{runnerName}\",\"slots\":1}")
              .fixing("$.runner.pinnedVersion"),
          Interaction.read(
                  ProviderStates.AN_UNREGISTERED_RUNNER, "listRunners", WORKSPACES + "/runners", "$")
              .fixing("$[*].pinnedVersion"),
          Interaction.write(
              ProviderStates.AN_UNREGISTERED_RUNNER,
              "rotateRunnerRegistrationToken",
              WORKSPACES + "/runners/{runnerId}/registration-token",
              200,
              null)
              .fixing("$.runner.pinnedVersion"),
          Interaction.read(
                  ProviderStates.A_CONNECTED_RUNNER, "listRunners", WORKSPACES + "/runners", "$")
              .fixing("$[*].pinnedVersion"),
          Interaction.write(
              ProviderStates.A_QUARANTINED_RUNNER,
              "greenlightRunner",
              WORKSPACES + "/runners/{runnerId}/greenlight",
              200,
              null)
              .fixing("$.pinnedVersion"));

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\{([^}]+)}");

  @Inject ProviderStates states;

  /** The state's name as a directory: lower case, runs of anything else as one dash. */
  static String slug(String state) {
    return state.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-|-$", "");
  }

  @Test
  void goldenMastersMatchTheProvider() throws IOException {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();
    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();

    Set<String> takesBody = operationsTakingABody();
    for (Interaction interaction : INTERACTIONS) {
      if ((interaction.requestBody() != null) != takesBody.contains(interaction.operationId())) {
        failures.add(
            interaction.operationId()
                + (interaction.requestBody() != null
                    ? " takes no request body, but the recording sends one: record null."
                    : " takes a request body, but the recording sends none."));
      }
      Recorded recorded = record(interaction);
      String slug = slug(interaction.state());
      String file = slug + "/" + interaction.operationId() + ".json";

      ObjectNode state = indexStates.get(slug);
      if (state == null) {
        state = JsonNodeFactory.instance.objectNode();
        state.put("name", interaction.state());
        state.put("slug", slug);
        state.set("params", recorded.params());
        state.set("dependsOn", JsonNodeFactory.instance.arrayNode());
        indexStates.put(slug, state);
      } else if (!state.get("params").equals(recorded.params())) {
        failures.add("State '" + interaction.state() + "' froze to different params per operation");
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", interaction.method());
      // The path is the route alone and the query its own object, as the consumers' pacts send it.
      int at = interaction.path().indexOf('?');
      operation.put("path", at < 0 ? interaction.path() : interaction.path().substring(0, at));
      if (at >= 0) {
        ObjectNode query = operation.putObject("query");
        for (String pair : interaction.path().substring(at + 1).split("&")) {
          int eq = pair.indexOf('=');
          query.put(
              URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
              eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
      }
      if (interaction.requestBody() != null) {
        operation.set("body", JSON.readTree(interaction.requestBody()));
      }
      operation.put("status", interaction.status());
      operation.put("file", file);
      ObjectNode frozen = operation.putObject("frozen");
      frozen.set("ids", strings(recorded.freezer().idPaths()));
      frozen.set("instants", strings(recorded.freezer().instantPaths()));
      frozen.set("strings", strings(recorded.freezer().stringPaths()));
      frozen.set("numbers", strings(recorded.freezer().numberPaths()));
      if (interaction.listFilteredTo() == null) {
        frozen.putNull("listFilteredTo");
      } else {
        frozen.put("listFilteredTo", interaction.listFilteredTo());
      }
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }

      written.add(file);
      check(dir.resolve(file), GoldenJson.render(recorded.body()), update, failures);
    }

    ObjectNode index = JsonNodeFactory.instance.objectNode();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    ArrayNode stateArray = index.putArray("states");
    indexStates.forEach(
        (slug, state) -> {
          ArrayNode operations = state.putArray("operations");
          indexOperations.get(slug).values().forEach(operations::add);
          stateArray.add(state);
        });
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenJson.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  record Recorded(JsonNode body, ObjectNode params, Freezer freezer) {}

  private Recorded record(Interaction interaction) throws IOException {
    ProviderStates.Setup setup = states.setUp(interaction.state());
    Map<String, String> params = setup.params();
    Response response;
    try {
      var request = given();
      if (interaction.requestBody() != null) {
        request =
            request
                .contentType("application/json")
                .body(JSON.writeValueAsString(expandBody(JSON.readTree(interaction.requestBody()), params)));
      } else {
        // As a browser sends a body-less call: RestAssured would otherwise add a form content type.
        request = request.noContentType();
      }
      response = request.when().request(interaction.method(), expand(interaction.path(), params));
    } finally {
      states.cleanUp();
    }
    String raw = response.asString();
    if (response.statusCode() != interaction.status()) {
      throw new AssertionError(
          interaction.method()
              + " "
              + interaction.path()
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ", expected "
              + interaction.status()
              + ": "
              + raw);
    }
    JsonNode body = JSON.readTree(raw);
    if (interaction.listFilteredTo() != null) {
      body = filtered(body, interaction.listFilteredTo(), params.values());
    }
    Freezer freezer = new Freezer().rowIds(setup.rowIds());
    for (Map.Entry<String, String> fixed : interaction.fixedStrings().entrySet()) {
      fix(body, fixed.getKey().substring(1), fixed.getValue());
      freezer.markString(fixed.getKey());
    }
    ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
    params.forEach((k, v) -> frozenParams.put(k, freezer.freezeParam(v)));
    return new Recorded(freezer.freeze(body), frozenParams, freezer);
  }

  /** Sets every string at {@code rest} (a {@code .a[*].b} path below {@code node}) to {@code value}. */
  private static void fix(JsonNode node, String rest, String value) {
    if (rest.isEmpty() || node == null) {
      return;
    }
    if (rest.startsWith("[*]")) {
      node.forEach(element -> fix(element, rest.substring(3), value));
      return;
    }
    String tail = rest.substring(1);
    int next = tail.length();
    for (char stop : new char[] {'.', '['}) {
      int at = tail.indexOf(stop);
      if (at >= 0 && at < next) {
        next = at;
      }
    }
    String field = tail.substring(0, next);
    String after = tail.substring(next);
    if (!(node instanceof ObjectNode object) || !object.has(field)) {
      return;
    }
    if (after.isEmpty()) {
      if (object.get(field).isTextual()) {
        object.put(field, value);
      }
    } else {
      fix(object.get(field), after, value);
    }
  }

  /** The body with every string that is exactly {@code {param}} replaced by that param's value. */
  static JsonNode expandBody(JsonNode node, Map<String, String> params) {
    if (node.isTextual()) {
      String text = node.asText();
      if (text.startsWith("{") && text.endsWith("}")) {
        String value = params.get(text.substring(1, text.length() - 1));
        if (value != null) {
          return TextNode.valueOf(value);
        }
      }
      return node;
    }
    if (node instanceof ObjectNode object) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      object.fields().forEachRemaining(e -> out.set(e.getKey(), expandBody(e.getValue(), params)));
      return out;
    }
    if (node instanceof ArrayNode array) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      array.forEach(element -> out.add(expandBody(element, params)));
      return out;
    }
    return node;
  }

  /** The operationIds whose operation declares a request body, read off the served openapi. */
  private static Set<String> operationsTakingABody() throws IOException {
    JsonNode paths =
        JSON.readTree(
                given()
                    .when()
                    .get("/workspaces/q/openapi?format=json")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString())
            .path("paths");
    Set<String> ids = new TreeSet<>();
    paths.forEach(
        path ->
            path.forEach(
                operation -> {
                  if (operation.has("operationId") && operation.has("requestBody")) {
                    ids.add(operation.get("operationId").asText());
                  }
                }));
    return ids;
  }

  /** The answer with the array at {@code path} reduced to entries mentioning a param value. */
  static JsonNode filtered(JsonNode body, String path, Collection<String> paramValues) {
    JsonNode out = body.deepCopy();
    JsonNode node = out;
    if (!path.equals("$")) {
      for (String segment : path.substring(2).split("\\.")) {
        node = node.path(segment);
      }
    }
    if (!(node instanceof ArrayNode list)) {
      throw new IllegalStateException(path + " is not an array in " + body);
    }
    List<JsonNode> kept = new ArrayList<>();
    for (JsonNode entry : list) {
      String text = entry.toString();
      if (paramValues.stream().anyMatch(text::contains)) {
        kept.add(entry);
      }
    }
    list.removeAll();
    list.addAll(kept);
    return out;
  }

  private static String expand(String template, Map<String, String> params) {
    Matcher m = TEMPLATE_PARAM.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = params.get(m.group(1));
      if (value == null) {
        throw new IllegalStateException(
            "Path " + template + " names {" + m.group(1) + "}, which the state does not return");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    values.forEach(out::add);
    return out;
  }

  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}
