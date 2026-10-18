package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.PactSpecVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenFiles;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.PactFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
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
                    GoldenMasters.PROJECTS,
                    ProjectsContract.REPOSITORY_EXISTS,
                    ProjectsContract.GET_REPOSITORY,
                    null,
                    ProjectsContract.READS_REPOSITORY));
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
    return PactFiles.written(ProjectsContract.pact());
  }

  /** Sorted, version-stripped, 2-space indented, one trailing newline: {@link PactFiles#normalise}. */
  static String normalise(String raw) throws IOException {
    return PactFiles.normalise(raw);
  }
}
