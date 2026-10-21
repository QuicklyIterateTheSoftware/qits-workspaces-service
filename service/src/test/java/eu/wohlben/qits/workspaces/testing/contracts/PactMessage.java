package eu.wohlben.qits.workspaces.testing.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.matchers.BodyMatchResult;
import au.com.dius.pact.core.matchers.JsonContentMatcher;
import au.com.dius.pact.core.matchers.MatchingContext;
import au.com.dius.pact.core.model.ContentType;
import au.com.dius.pact.core.model.OptionalBody;
import au.com.dius.pact.core.model.V4Interaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * <b>One message interaction of a consumer pact, as the consumer is handed it</b> (qits-950): the
 * frame the provider sends, ready to feed to this service's real handling.
 *
 * <p><b>The text is the pact's own example</b> — the recorded frame — <b>with the state's frozen
 * values swapped for the consumer's</b>, the message counterpart of an HTTP row's state params: the
 * runner recorded {@code rowId} 5, and the service handles a row its test seeded. {@link #text(Map)}
 * replaces every leaf holding a state param's frozen example ({@code params.rowId} "5" stands for
 * the number 5 and the string "5" alike, as a frame spells a row id both ways); {@link
 * #text(Map, Consumer)} edits a field no param names (a {@code hello}'s {@code runnerVersion},
 * which the provider freezes as a string).
 *
 * <p><b>Every text handed out is first matched against the interaction's own rules</b> by pact-jvm's
 * JSON matcher, exactly as a provider's message is at verification: a substitution the contract
 * does not admit — a row id as a string where the frame carries a number, a request id that is no
 * UUID — fails here, so what the consumer test feeds is always a message the pact allows.
 */
public final class PactMessage {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final V4Interaction.AsynchronousMessage interaction;
  private final Map<String, String> params;

  public PactMessage(V4Interaction.AsynchronousMessage interaction, Map<String, String> params) {
    this.interaction = Objects.requireNonNull(interaction, "interaction");
    this.params = Map.copyOf(params);
  }

  public String description() {
    return interaction.getDescription();
  }

  /** The provider state's frozen example params ({@code rowId}, {@code requestId}). */
  public Map<String, String> params() {
    return params;
  }

  /** The pact's example of the frame, a fresh tree each call. */
  public ObjectNode example() {
    try {
      return (ObjectNode) MAPPER.readTree(interaction.contentsAsString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The example as it stands, matched. */
  public String text() {
    return text(Map.of());
  }

  /** The example with each named state param's frozen value replaced by {@code values}, matched. */
  public String text(Map<String, ?> values) {
    return text(values, frame -> {});
  }

  /** As {@link #text(Map)}, then {@code edit} applied to the tree, matched. */
  public String text(Map<String, ?> values, Consumer<ObjectNode> edit) {
    ObjectNode frame = example();
    Map<String, String> swaps = new LinkedHashMap<>();
    values.forEach(
        (name, value) -> {
          String frozen = params.get(name);
          if (frozen == null) {
            throw new IllegalArgumentException(
                description() + ": the state names no param '" + name + "' (it has " + params + ")");
          }
          swaps.put(frozen, String.valueOf(value));
        });
    JsonNode swapped = swap(frame, swaps);
    edit.accept((ObjectNode) swapped);
    String text;
    try {
      text = MAPPER.writeValueAsString(swapped);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    assertAdmitted(text);
    return text;
  }

  /** Fails, naming each mismatch, unless the interaction's rules admit {@code text}. */
  public void assertAdmitted(String text) {
    BodyMatchResult result =
        JsonContentMatcher.INSTANCE.matchBody(
            interaction.getContents().getContents(),
            OptionalBody.body(text.getBytes(StandardCharsets.UTF_8), ContentType.Companion.getJSON()),
            new MatchingContext(
                interaction.getContents().getMatchingRules().rulesForCategory("body"),
                true,
                Map.of(),
                false));
    if (!result.matchedOk()) {
      fail(
          description()
              + ": the frame fed to the service is not one the pact admits — "
              + result.getMismatches()
              + "\n  "
              + text);
    }
  }

  /** {@code node} with every leaf equal to a key of {@code swaps} replaced, keeping its kind. */
  private static JsonNode swap(JsonNode node, Map<String, String> swaps) {
    if (swaps.isEmpty()) {
      return node;
    }
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        field.setValue(swap(field.getValue(), swaps));
      }
      return object;
    }
    if (node.isArray()) {
      ArrayNode array = (ArrayNode) node;
      for (int i = 0; i < array.size(); i++) {
        array.set(i, swap(array.get(i), swaps));
      }
      return array;
    }
    String replacement = swaps.get(node.asText());
    if (replacement == null || !(node.isTextual() || node.isIntegralNumber())) {
      return node;
    }
    return node.isTextual() ? TextNode.valueOf(replacement) : LongNode.valueOf(Long.parseLong(replacement));
  }
}
