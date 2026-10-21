package eu.wohlben.qits.workspaces.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.core.model.DefaultPactReader;
import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.UrlSource;
import au.com.dius.pact.core.model.v4.V4InteractionType;
import au.com.dius.pact.core.support.json.JsonParser;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junitsupport.JUnitProviderTestSupport;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>{@link ConsumerPactVerificationTest} runs every HTTP interaction and no message one</b>
 * (qits-950). Plain JUnit: pact-jvm's own {@link JUnitProviderTestSupport#filterPactsByAnnotations}
 * — what its JUnit 5 provider applies before it makes an invocation — is handed the verifier's
 * class, so the {@code @PactFilter} on it is what is tested, not a copy of it.
 *
 * <p>The fixture ({@code contracts/mixed-v4-pact-fixture.json}, outside {@code pacts/} so no
 * verifier loads it) is a V4 pact holding one HTTP interaction and one {@code
 * Asynchronous/Messages} one, as qits-workspaces-runner-daemon's pinned jar will once it carries
 * its pact of this service's frames.
 */
class ConsumerPactVerificationFilterTest {

  private static final String FIXTURE = "contracts/mixed-v4-pact-fixture.json";

  /** Why the filter is needed at all: the verifier's target cannot run a message. */
  @Test
  void anHttpTargetCannotRunAMessageInteraction() throws IOException {
    Pact fixture = fixture();
    Interaction message = only(fixture, V4InteractionType.AsynchronousMessages);
    HttpTestTarget target = new HttpTestTarget("localhost", 1);
    assertFalse(target.supportsInteraction(message));
    UnsupportedOperationException refused =
        assertThrows(
            UnsupportedOperationException.class,
            () -> target.prepareRequest(fixture, message, Map.of()));
    assertTrue(refused.getMessage().contains("Only request/response interactions"));
  }

  @Test
  void theMessageInteractionIsDroppedAndTheHttpOneKept() throws IOException {
    List<Pact> filtered = filter(List.of(fixture()));
    assertEquals(1, filtered.size(), "the fixture's pact is kept for its HTTP interaction");
    List<String> kept =
        filtered.get(0).getInteractions().stream().map(Interaction::getDescription).toList();
    assertEquals(List.of("registerRunner: registerRunner"), kept);
  }

  /** Every pinned consumer pact keeps every HTTP interaction it has, V3 and V4 alike. */
  @Test
  void noHttpInteractionOfAPinnedPactIsDropped() throws IOException {
    List<Pact> pinned = new ClasspathPactLoader().load(ConsumerPactVerificationTest.PROVIDER);
    List<Pact> all = new ArrayList<>(pinned);
    all.add(fixture());
    List<String> before = new ArrayList<>();
    for (Pact pact : all) {
      for (Interaction interaction : pact.getInteractions()) {
        if (interaction.asV4Interaction().isInteractionType(V4InteractionType.SynchronousHTTP)) {
          before.add(key(pact, interaction));
        }
      }
    }
    List<String> after = new ArrayList<>();
    for (Pact pact : filter(all)) {
      for (Interaction interaction : pact.getInteractions()) {
        assertTrue(
            interaction.asV4Interaction().isInteractionType(V4InteractionType.SynchronousHTTP),
            key(pact, interaction) + " is no HTTP interaction and reached the verifier");
        after.add(key(pact, interaction));
      }
    }
    assertTrue(before.size() > 50, "only " + before.size() + " HTTP interactions pinned");
    assertEquals(before, after);
  }

  private static List<Pact> filter(List<Pact> pacts) {
    return JUnitProviderTestSupport.INSTANCE.filterPactsByAnnotations(
        pacts, ConsumerPactVerificationTest.class);
  }

  private static String key(Pact pact, Interaction interaction) {
    return pact.getConsumer().getName() + " / " + interaction.getDescription();
  }

  private static Interaction only(Pact pact, V4InteractionType type) {
    List<Interaction> of =
        pact.getInteractions().stream()
            .filter(i -> i.asV4Interaction().isInteractionType(type))
            .map(Interaction.class::cast)
            .toList();
    assertEquals(1, of.size(), "the fixture holds one " + type + " interaction");
    return of.get(0);
  }

  private static Pact fixture() throws IOException {
    URL url = ConsumerPactVerificationFilterTest.class.getClassLoader().getResource(FIXTURE);
    try (InputStream in = url.openStream()) {
      return DefaultPactReader.INSTANCE.pactFromJson(
          JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).asObject(),
          new UrlSource(url.toString()));
    }
  }
}
