package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a fetcher may honestly check about a document it does not own — and what it must not.
 *
 * <p>Plain JUnit: this is a value class with a static factory, and there is nothing to boot.
 */
class AgentConfigurationDocumentTest {

  private static final String DOCUMENT =
      "{\"version\":1,\"generatedAt\":\"2026-09-09T00:00:00Z\",\"surfaces\":["
          + "{\"surface\":\"epic.chat\",\"harness\":\"CLAUDE\"},"
          + "{\"surface\":\"ticket.dispatch\",\"harness\":\"CLAUDE\"}]}";

  @Test
  void keepsTheBytesItWasGivenAndReadsTheTwoThingsItNeeds() {
    AgentConfigurationDocument document = AgentConfigurationDocument.of(DOCUMENT);

    // THE BYTES ARE THE VALUE. What is stored on the row and handed to the container is what
    // qits-projects answered, character for character — a re-render here would be this service
    // holding an opinion about a shape it does not own.
    assertEquals(DOCUMENT, document.json());
    assertEquals(1, document.version());
    assertEquals(List.of("epic.chat", "ticket.dispatch"), document.surfaces());
  }

  @Test
  void takesADocumentWhoseSurfacesCarryFieldsThisServiceHasNeverHeardOf() {
    // The point of the shallow check. qits-projects and the shared harness library release
    // independently of this service, so a surface growing a field — an effort level, an external
    // MCP attachment — must reach the container unchanged rather than being refused by a validator
    // that knows less than the reader does.
    AgentConfigurationDocument document =
        AgentConfigurationDocument.of(
            "{\"version\":7,\"surfaces\":[{\"surface\":\"epic.agent\",\"effort\":\"xhigh\","
                + "\"somethingNobodyHasWrittenYet\":{\"deep\":[1,2,3]}}]}");

    assertEquals(7, document.version(), "an unknown version is carried, not refused");
    assertEquals(List.of("epic.agent"), document.surfaces());
  }

  @Test
  void refusesWhatIsNotADocument() {
    // Each of these is something a fetch can really come back with: an empty body, an error page or
    // a proxy's HTML, and a service that is up but has nothing to say. A container born with any of
    // them would be worse off than one born with nothing, because nothing has a documented
    // meaning — the harness library's shipped defaults — and half a document does not.
    for (String notADocument :
        List.of(
            "",
            "   ",
            "<html><body>502 Bad Gateway</body></html>",
            "[]",
            "{\"version\":1}",
            "{\"version\":1,\"surfaces\":[]}",
            "{\"version\":1,\"surfaces\":{}}")) {
      IllegalArgumentException refused =
          assertThrows(
              IllegalArgumentException.class,
              () -> AgentConfigurationDocument.of(notADocument),
              () -> "accepted as a document: " + notADocument);
      assertTrue(
          refused.getMessage() != null && !refused.getMessage().isBlank(),
          "the refusal says nothing, and this message is what lands on the workspace row");
    }
  }

  @Test
  void refusesANullBody() {
    assertThrows(IllegalArgumentException.class, () -> AgentConfigurationDocument.of(null));
  }
}
