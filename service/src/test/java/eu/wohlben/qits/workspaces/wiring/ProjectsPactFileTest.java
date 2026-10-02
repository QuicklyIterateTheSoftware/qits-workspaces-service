package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenFiles;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * <b>The committed consumer pact, {@code
 * pacts/qits-workspaces-service_qits-projects-service.json}</b>, and the references every
 * interaction in it must carry. Plain JUnit: building and writing a pact needs no application.
 *
 * <p><b>Compare by default.</b> pact-jvm writes the pact {@link ProjectsContract} describes (to
 * {@code service/target/pacts/}, raw, for inspection); the test normalises it — interactions sorted
 * by description then provider state, pact-jvm's own version stripped from {@code metadata} (a
 * library bump is not a contract change, and a changed file is what republishes the pacts jar),
 * 2-space indentation and a trailing newline — and compares it byte for byte to the committed file.
 * A difference fails with a diff; {@code -Dgolden.update=true} (or {@code QITS_GOLDEN_UPDATE=true})
 * rewrites it instead, the provider's switch.
 */
public class ProjectsPactFileTest {

  static final String FILE = "qits-workspaces-service_qits-projects-service.json";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  public void theCommittedPactIsWhatTheContractWrites() throws IOException {
    String raw = written();
    Path scratch = Path.of("target", "pacts", FILE);
    Files.createDirectories(scratch.getParent());
    Files.writeString(scratch, raw);

    GoldenFiles.compareOrWrite(
        GoldenFiles.repositoryRoot().resolve("pacts").resolve(FILE), normalise(raw));
  }

  /**
   * Both references on every interaction, as the platform reads them: {@code qits-call} naming the
   * provider operation, {@code qits-trigger} naming the kind and the kind's own key, every value a
   * string. Asserted on what pact-jvm's own writer produces (normalised), which the test above
   * holds equal to the committed file — so this also proves pact-jvm 4.6 serialises a comment group
   * it has no DSL for.
   */
  @Test
  public void everyInteractionCarriesBothReferences() throws IOException {
    JsonNode pact = MAPPER.readTree(normalise(written()));
    assertEquals(GoldenMasters.CONSUMER, pact.path("consumer").path("name").asText());
    assertEquals(GoldenMasters.PROVIDER, pact.path("provider").path("name").asText());
    assertEquals("4.0", pact.path("metadata").path("pactSpecification").path("version").asText());

    JsonNode interactions = pact.path("interactions");
    assertEquals(ProjectsContract.CASES.size(), interactions.size(), "one interaction per row");
    Map<String, String> keyOfKind =
        Map.of("operation", "operationId", "event", "event", "schedule", "schedule", "ui", "interaction");
    Set<String> unique = new HashSet<>();
    for (JsonNode interaction : interactions) {
      String description = interaction.path("description").asText();
      JsonNode references = interaction.path("comments").path("references");

      JsonNode call = references.path("qits-call");
      assertStrings(description, call, "app", "operationId");
      assertEquals(GoldenMasters.PROVIDER, call.path("app").asText(), description);

      JsonNode trigger = references.path("qits-trigger");
      String kind = trigger.path("kind").asText();
      assertTrue(keyOfKind.containsKey(kind), description + ": unknown trigger kind '" + kind + "'");
      assertStrings(description, trigger, "kind", "app", keyOfKind.get(kind));
      if (!"ui".equals(kind)) {
        assertEquals(GoldenMasters.CONSUMER, trigger.path("app").asText(), description);
      }
      assertTrue(
          description.startsWith(trigger.path(keyOfKind.get(kind)).asText() + ": "),
          description + ": the description leads with the trigger");

      String state = interaction.path("providerStates").path(0).path("name").asText();
      assertTrue(unique.add(description + "\u0000" + state), "(description, state) repeats: " + description);
    }
  }

  @Test
  public void aNullTriggerIsRefused() {
    NullPointerException refused =
        assertThrows(
            NullPointerException.class,
            () ->
                GoldenMasters.interaction(
                    new PactBuilder(GoldenMasters.CONSUMER, GoldenMasters.PROVIDER, PactSpecVersion.V4),
                    ProjectsContract.REPOSITORY_EXISTS,
                    ProjectsContract.GET_REPOSITORY,
                    null));
    assertTrue(refused.getMessage().startsWith("trigger"), refused.getMessage());
  }

  private static void assertStrings(String description, JsonNode group, String... keys) {
    assertTrue(group.isObject(), description + ": reference group missing");
    assertEquals(keys.length, group.size(), description + ": " + group + " holds other keys");
    for (String key : keys) {
      JsonNode value = group.path(key);
      assertTrue(
          value.isTextual() && !value.asText().isBlank(),
          description + ": " + key + " must be a non-blank string, got " + value);
    }
  }

  /** The pact as pact-jvm's own writer serialises it. */
  static String written() {
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      DefaultPactWriter.INSTANCE.writePact(ProjectsContract.pact(), writer, PactSpecVersion.V4);
    }
    return out.toString();
  }

  /** Sorted, version-stripped, 2-space indented, one trailing newline. */
  static String normalise(String raw) throws IOException {
    ObjectNode pact = (ObjectNode) MAPPER.readTree(raw);
    JsonNode metadata = pact.path("metadata");
    if (metadata instanceof ObjectNode meta) {
      meta.remove("pact-jvm");
    }
    if (pact.path("interactions") instanceof ArrayNode interactions) {
      List<JsonNode> sorted = new ArrayList<>();
      interactions.forEach(sorted::add);
      sorted.sort(
          Comparator.comparing((JsonNode i) -> i.path("description").asText())
              .thenComparing(i -> i.path("providerStates").path(0).path("name").asText()));
      interactions.removeAll();
      sorted.forEach(interactions::add);
    }
    StringBuilder out = new StringBuilder();
    print(pact, "", out);
    return out.append('\n').toString();
  }

  /** {@code JSON.stringify(value, null, 2)}: no space before a colon, empty containers as {} / []. */
  private static void print(JsonNode node, String indent, StringBuilder out) throws IOException {
    String inner = indent + "  ";
    if (node.isObject()) {
      if (node.isEmpty()) {
        out.append("{}");
        return;
      }
      out.append("{\n");
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        out.append(inner).append(MAPPER.writeValueAsString(field.getKey())).append(": ");
        print(field.getValue(), inner, out);
        out.append(fields.hasNext() ? ",\n" : "\n");
      }
      out.append(indent).append('}');
    } else if (node.isArray()) {
      if (node.isEmpty()) {
        out.append("[]");
        return;
      }
      out.append("[\n");
      for (int i = 0; i < node.size(); i++) {
        out.append(inner);
        print(node.get(i), inner, out);
        out.append(i < node.size() - 1 ? ",\n" : "\n");
      }
      out.append(indent).append(']');
    } else {
      out.append(MAPPER.writeValueAsString(node));
    }
  }
}
