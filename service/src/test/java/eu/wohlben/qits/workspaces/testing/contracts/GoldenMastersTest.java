package eu.wohlben.qits.workspaces.testing.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** How {@link GoldenMasters} cuts a recording down to what this service reads. */
class GoldenMastersTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final GoldenMasters.Operation OP =
      new GoldenMasters.Operation(
          GoldenMasters.IDP, "a state", Map.of(), "listClients", "GET", "/idp/api/clients", 200,
          "f.json", Set.of(), Set.of(), Set.of(), null);

  @Test
  void onlyTheConsumedPathsAndTheirParentsAreKept() throws Exception {
    JsonNode recorded =
        MAPPER.readTree(
            "{\"repository\":{\"id\":\"1\",\"name\":\"n\",\"cloneUrl\":\"u\"},\"other\":true}");

    JsonNode cut = GoldenMasters.prune(recorded, List.of("$.repository.id"), OP);

    assertEquals(MAPPER.readTree("{\"repository\":{\"id\":\"1\"}}"), cut);
  }

  @Test
  void aStarKeepsEveryElementCutTheSameWay() throws Exception {
    JsonNode recorded =
        MAPPER.readTree(
            "[{\"clientId\":\"a\",\"secret\":\"x\",\"contextKind\":\"workspace\"},"
                + "{\"clientId\":\"b\",\"secret\":\"y\",\"contextKind\":\"workspace\"}]");

    JsonNode cut = GoldenMasters.prune(recorded, List.of("$[*].clientId", "$[*].contextKind"), OP);

    assertEquals(
        MAPPER.readTree(
            "[{\"clientId\":\"a\",\"contextKind\":\"workspace\"},"
                + "{\"clientId\":\"b\",\"contextKind\":\"workspace\"}]"),
        cut);
  }

  @Test
  void aPathNamingAContainerKeepsAllOfIt() throws Exception {
    JsonNode recorded =
        MAPPER.readTree("{\"command\":{\"id\":\"c\",\"agentSessions\":[{\"id\":\"s\"}]}}");

    JsonNode cut = GoldenMasters.prune(recorded, List.of("$.command.agentSessions"), OP);

    assertEquals(MAPPER.readTree("{\"command\":{\"agentSessions\":[{\"id\":\"s\"}]}}"), cut);
  }

  @Test
  void aJsonBodyKeepsTheContainerAndNothingInIt() throws Exception {
    JsonNode cut =
        GoldenMasters.prune(MAPPER.readTree("{\"id\":\"1\"}"), GoldenMasters.A_JSON_BODY, OP);

    assertEquals(MAPPER.createObjectNode(), cut);
  }

  @Test
  void aPathTheRecordingDoesNotHoldFailsNamingIt() throws Exception {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> GoldenMasters.prune(MAPPER.readTree("{\"id\":\"1\"}"), List.of("$.name"), OP));

    assertTrue(refused.getMessage().contains("$.name"), refused.getMessage());
  }

  @Test
  void anotherProvidersIndexIsNotTakenForQitsProjects() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> GoldenMasters.params(GoldenMasters.IDP, "anything"));

    assertTrue(refused.getMessage().contains("qits-idp-golden-masters"), refused.getMessage());
    assertTrue(refused.getMessage().contains("qits-projects"), "it names what it found instead");
  }

  @Test
  void aRequestBodyAndAStatusOnlyAnswerAreWrittenAsGiven() throws Exception {
    JsonNode sent = MAPPER.readTree("{\"a\":[1,\"b\",null],\"c\":{\"d\":true}}");
    au.com.dius.pact.core.model.V4Pact pact =
        ConsumerPacts.pact(
            GoldenMasters.PROJECTS,
            builder ->
                GoldenMasters.interaction(
                    builder,
                    GoldenMasters.PROJECTS,
                    "no repository with the given id",
                    "getRepository",
                    GoldenMasters.Trigger.operation("dispatchAgent"),
                    GoldenMasters.exactBody(sent),
                    List.of()));
    java.io.StringWriter out = new java.io.StringWriter();
    au.com.dius.pact.core.model.DefaultPactWriter.INSTANCE.writePact(
        pact, new java.io.PrintWriter(out), au.com.dius.pact.core.model.PactSpecVersion.V4);
    JsonNode interaction = MAPPER.readTree(out.toString()).path("interactions").path(0);

    assertEquals(sent, interaction.path("request").path("body").path("content"));
    assertEquals(404, interaction.path("response").path("status").asInt());
    assertTrue(interaction.path("response").path("body").isMissingNode(), "status only");
  }
}
