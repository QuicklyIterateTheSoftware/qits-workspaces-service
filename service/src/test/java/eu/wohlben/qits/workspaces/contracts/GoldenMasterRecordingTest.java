package eu.wohlben.qits.workspaces.contracts;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenFiles;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
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
   * One recorded read.
   *
   * @param listFilteredTo the array (a {@code $.a} path) reduced to the entries that mention one of
   *     the state's params, or null. Other tests share the store, so an unfiltered list would hold
   *     their rows too
   */
  record Interaction(
      String state,
      String operationId,
      String path,
      String listFilteredTo) {}

  static final List<Interaction> INTERACTIONS =
      List.of(
          new Interaction(
              ProviderStates.A_PROJECT_WITH_WORKSPACES_BOUND_TO_WORK_ITEMS,
              "listOpenWorkspaces",
              "/workspaces/api/work/workspaces",
              "$.entries"),
          new Interaction(
              ProviderStates.A_PROJECT_WITH_WORKSPACES_BOUND_TO_WORK_ITEMS,
              "listWorkItemWorkspaces",
              "/workspaces/api/work/{bugTicketId}/workspaces",
              null),
          new Interaction(
              ProviderStates.A_WORK_ITEM_WITH_NO_WORKSPACES,
              "listWorkItemWorkspaces",
              "/workspaces/api/work/{improvementTicketId}/workspaces",
              null),
          new Interaction(
              ProviderStates.NO_WORK_ITEM_HAS_AN_OPEN_WORKSPACE,
              "listOpenWorkspaces",
              "/workspaces/api/work/workspaces",
              null));

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

    for (Interaction interaction : INTERACTIONS) {
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
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", "GET");
      operation.put("path", interaction.path());
      operation.put("status", 200);
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
      response = given().when().get(expand(interaction.path(), params));
    } finally {
      states.cleanUp();
    }
    String raw = response.asString();
    if (response.statusCode() != 200) {
      throw new AssertionError(
          "GET "
              + interaction.path()
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ": "
              + raw);
    }
    JsonNode body = JSON.readTree(raw);
    if (interaction.listFilteredTo() != null) {
      body = filtered(body, interaction.listFilteredTo(), params.values());
    }
    Freezer freezer = new Freezer().rowIds(setup.rowIds());
    ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
    params.forEach((k, v) -> frozenParams.put(k, freezer.freezeParam(v)));
    return new Recorded(freezer.freeze(body), frozenParams, freezer);
  }

  /** The answer with the array at {@code path} reduced to entries mentioning a param value. */
  static JsonNode filtered(JsonNode body, String path, Collection<String> paramValues) {
    JsonNode out = body.deepCopy();
    JsonNode node = out;
    for (String segment : path.substring(2).split("\\.")) {
      node = node.path(segment);
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
