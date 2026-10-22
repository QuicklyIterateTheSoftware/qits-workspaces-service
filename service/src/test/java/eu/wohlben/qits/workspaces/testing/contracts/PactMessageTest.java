package eu.wohlben.qits.workspaces.testing.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import au.com.dius.pact.core.model.DefaultPactReader;
import au.com.dius.pact.core.model.UnknownPactSource;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.support.json.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link PactMessage#text(Map)} swaps a state param's frozen value at the frozen paths only
 * (qits-950): a leaf that merely equals it keeps its value.
 */
class PactMessageTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** A frame whose row id 1 is also its slots and its capability version. */
  private static final String PACT =
      """
      {"consumer": {"name": "c"}, "provider": {"name": "p"},
       "metadata": {"pactSpecification": {"version": "4.0"}},
       "interactions": [{
         "type": "Asynchronous/Messages", "description": "ack", "key": "k",
         "contents": {"contentType": "application/json", "encoded": false,
           "content": {"type": "ack", "rowId": 1, "slots": 1, "capabilityVersion": 1,
                       "adopted": ["1"], "note": "1"}},
         "matchingRules": {"body": {
           "$.rowId": {"combine": "AND", "matchers": [{"match": "integer"}]},
           "$.slots": {"combine": "AND", "matchers": [{"match": "integer"}]},
           "$.capabilityVersion": {"combine": "AND", "matchers": [{"match": "integer"}]},
           "$.adopted": {"combine": "AND", "matchers": [{"match": "type", "min": 1, "max": 1}]},
           "$.adopted[*]": {"combine": "AND", "matchers": [{"match": "type"}]},
           "$.note": {"combine": "AND", "matchers": [{"match": "type"}]}}}}]}
      """;

  private static PactMessage message(Set<String> substitutable) {
    var pact =
        DefaultPactReader.INSTANCE.pactFromJson(
            JsonParser.parseString(PACT).asObject(), UnknownPactSource.INSTANCE);
    var interaction = (V4Interaction.AsynchronousMessage) pact.getInteractions().get(0);
    return new PactMessage(interaction, Map.of("rowId", "1"), substitutable);
  }

  @Test
  void onlyTheFrozenPathsTakeTheRowsValue() throws Exception {
    JsonNode sent =
        JSON.readTree(
            message(Set.of("$.rowId", "$.adopted[*]")).text(Map.of("rowId", 42L)));

    assertEquals(42, sent.path("rowId").asLong());
    assertEquals("42", sent.path("adopted").path(0).asText());
    assertEquals(1, sent.path("slots").asInt(), "an equal number elsewhere is not a row id");
    assertEquals(1, sent.path("capabilityVersion").asInt());
    assertEquals("1", sent.path("note").asText(), "an equal string elsewhere is not a row id");
  }

  @Test
  void aParamAtNoFrozenPathFailsRatherThanSwappingNothing() {
    PactMessage message = message(Set.of("$.elsewhere"));

    assertThrows(IllegalArgumentException.class, () -> message.text(Map.of("rowId", 42L)));
  }
}
