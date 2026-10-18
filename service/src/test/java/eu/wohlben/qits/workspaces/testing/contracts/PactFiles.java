package eu.wohlben.qits.workspaces.testing.contracts;

import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * <b>A committed consumer pact, as this repository writes it</b>: pact-jvm's own writer, then
 * normalised — interactions sorted by description then provider state, pact-jvm's own version
 * stripped from {@code metadata} (a library bump is not a contract change, and a changed file is
 * what republishes the pacts jar), 2-space indentation and a trailing newline.
 */
public final class PactFiles {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private PactFiles() {}

  /** The pact as pact-jvm's own writer serialises it. */
  public static String written(V4Pact pact) {
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      DefaultPactWriter.INSTANCE.writePact(pact, writer, PactSpecVersion.V4);
    }
    return out.toString();
  }

  /**
   * Leaves the raw pact in {@code target/pacts/<file>} for inspection, and compares its normalised
   * form with {@code pacts/<file>} at the repository root — or rewrites that under {@code
   * -Dgolden.update=true}.
   */
  public static void compareOrWrite(String file, V4Pact pact) throws IOException {
    compareOrWrite(file, pact, UnaryOperator.identity());
  }

  /** As {@link #compareOrWrite(String, V4Pact)}, comparing both sides through {@code comparable}. */
  public static void compareOrWrite(String file, V4Pact pact, UnaryOperator<String> comparable)
      throws IOException {
    String raw = written(pact);
    Path scratch = Path.of("target", "pacts", file);
    Files.createDirectories(scratch.getParent());
    Files.writeString(scratch, raw);
    GoldenFiles.compareOrWrite(
        GoldenFiles.repositoryRoot().resolve("pacts").resolve(file), normalise(raw), comparable);
  }

  /**
   * The pact with every {@code qits-via.version} as {@code ?} (qits-1163): the library's version is
   * where the call came from, not what it asks, so a bump of it alone compares equal — the masking
   * qits-pact-consumer's {@code ConsumerPact} applies to its own files.
   */
  public static String maskViaVersions(String pact) {
    try {
      JsonNode root = MAPPER.readTree(pact);
      root.path("interactions")
          .forEach(
              i -> {
                if (i.path("comments").path("references").path("qits-via") instanceof ObjectNode via
                    && via.has("version")) {
                  via.put("version", "?");
                }
              });
      return normalise(MAPPER.writeValueAsString(root));
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** Sorted, version-stripped, 2-space indented, one trailing newline. */
  public static String normalise(String raw) throws IOException {
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
