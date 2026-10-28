package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import eu.wohlben.qits.eventstream.contracts.EventsGoldenMasters;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.PactFiles;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * <b>What this service asks qits-events itself</b> (qits-1668): a declared wait's look-back, {@link
 * HttpRecentEvents} over the real generated {@link EventsLog} client, against a pact mock server
 * that answers the recorded state {@code DeploymentActive events to catch up on}. The committed
 * file is {@code pacts/qits-workspaces-service_qits-events-service.json}; {@code
 * -Dgolden.update=true} rewrites it. The state names the look-back's name filter (qits-1727): the
 * look-back filters by name, so the recording has to be made with that same filter.
 *
 * <p>{@code @QuarkusTest} for {@code ProjectsConsumerPactTest}'s reason: the REST client is built
 * only inside a running application.
 */
@QuarkusTest
public class EventsLogPactTest {

  static final String FILE = "qits-workspaces-service_qits-events-service.json";

  /** The provider, for {@code OutboundCallsGuardTest}: its recordings name the route. */
  static final GoldenMasters.Provider EVENTS =
      new GoldenMasters.Provider(EventsGoldenMasters.PROVIDER, EventsGoldenMasters.INDEX_PROVIDER);

  static final String OPERATION = "listEvents";

  static final String STATE = "DeploymentActive events to catch up on";

  static final String NAME = "DeploymentActive";

  static final Instant SINCE = Instant.parse("2026-01-01T00:00:00Z");

  static V4Pact pact() {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("order", "asc");
    query.put("name", NAME);
    query.put("since", SINCE.toString());
    query.put("limit", Integer.toString(HttpRecentEvents.PAGE));
    PactBuilder builder =
        new PactBuilder(GoldenMasters.CONSUMER, EventsGoldenMasters.PROVIDER, PactSpecVersion.V4);
    return EventsGoldenMasters.interaction(
            builder,
            STATE,
            OPERATION,
            EventsGoldenMasters.Trigger.operation(GoldenMasters.CONSUMER, "createAgentWait"),
            null,
            "the look-back of one event name",
            query,
            null,
            List.of("events[*].id", "events[*].name", "events[*].occurredAt", "events[*].payload"))
        .toPact();
  }

  @Test
  public void theLookbackReadsTheRecentEventsOfItsName() {
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            pact(),
            MockProviderConfig.createDefault(PactSpecVersion.V4),
            (mockServer, context) -> {
              List<EventFrame> frames = against(mockServer.getUrl()).since(NAME, SINCE);
              assertTrue(!frames.isEmpty());
              assertEquals(NAME, frames.get(0).name());
              assertEquals(SINCE, frames.get(0).occurredAt());
              return null;
            });
    assertTrue(result instanceof PactVerificationResult.Ok, result::toString);
  }

  @Test
  public void theCommittedPactIsWhatTheContractWrites() throws Exception {
    PactFiles.compareOrWrite(FILE, pact());
  }

  /** The shipped {@link HttpRecentEvents} with the real generated client, and no bearer. */
  static HttpRecentEvents against(String baseUrl) {
    HttpRecentEvents events = new HttpRecentEvents();
    events.bearer =
        new IdpProjectsBearer() {
          @Override
          public Optional<String> authorization() {
            return Optional.empty();
          }
        };
    events.log = QuarkusRestClientBuilder.newBuilder().baseUri(URI.create(baseUrl)).build(EventsLog.class);
    return events;
  }
}
