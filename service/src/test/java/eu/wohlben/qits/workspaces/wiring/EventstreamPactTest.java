package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.eventstream.contracts.EventstreamPact;
import eu.wohlben.qits.workspaces.bus.AgentWaitListener;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>What qits-eventstream asks qits-events on this service's behalf</b> (qits-1163): the outbox's
 * publish ({@code bus/EventstreamLifecycleAnnouncer}) and {@code AgentWaitListener}'s catch-up
 * read, pacted by this service against the library version it pins and committed as {@code
 * pacts/qits-workspaces-service_qits-eventstream-javalib_qits-events-service.json}. The guard fails
 * naming any durable or raw listener, or {@code QitsEventBus} user, that this class leaves out.
 */
class EventstreamPactTest {

  static final EventstreamPact PACT =
      EventstreamPact.forConsumer("qits-workspaces-service")
          .listeners(new AgentWaitListener())
          .publishes(true)
          .guard(Path.of("target/classes"));

  @TestFactory
  Stream<DynamicTest> rows() {
    return PACT.tests();
  }

  @Test
  void pactFile() {
    PACT.compareOrWritePactFile();
  }
}
