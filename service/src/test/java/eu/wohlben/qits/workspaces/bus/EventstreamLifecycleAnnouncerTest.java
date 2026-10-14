package eu.wohlben.qits.workspaces.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.workspaces.control.LifecycleAnnouncer;
import eu.wohlben.qits.workspaces.events.AgentObsolete;
import eu.wohlben.qits.workspaces.events.AgentRemoved;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link EventstreamLifecycleAnnouncer} (qits-1236) against a recording {@link QitsEventBus}: the
 * bus is dark in {@code %test}, so what is asserted is the hop — that the port {@code control}
 * injects is this bean, that the cause is read on the caller's thread and handed across, that the
 * publish runs on the one publishing thread in call order, and with a request context active.
 */
@QuarkusTest
class EventstreamLifecycleAnnouncerTest {

  @Inject Instance<LifecycleAnnouncer> port;

  /** One publish as the bus saw it. */
  record Published(QitsEvent event, UUID parent, String thread, boolean requestContext) {}

  private final List<Published> published = new CopyOnWriteArrayList<>();

  @BeforeEach
  void recordTheBus() {
    QuarkusMock.installMockForType(
        new QitsEventBus() {
          @Override
          public void publish(QitsEvent event, UUID parentEventId) {
            published.add(
                new Published(
                    event,
                    parentEventId,
                    Thread.currentThread().getName(),
                    Arc.container().requestContext().isActive()));
          }
        },
        QitsEventBus.class);
  }

  @Test
  void theBusSeesEachEventOnThePublishingThreadInOrderWithTheCallersCause() throws Exception {
    assertTrue(port.isResolvable(), "control's port resolves to exactly one bean here");
    assertInstanceOf(EventstreamLifecycleAnnouncer.class, unwrap(port.get()));

    UUID cause = UUID.randomUUID();
    Instant at = Instant.parse("2026-10-14T09:24:09Z");
    AgentObsolete obsolete =
        new AgentObsolete("a-1", "w-1", "385", "repo", "ticket/w-1", "2026.1014.1", "abc", at);
    AgentRemoved removed =
        new AgentRemoved(
            "a-1", "w-1", "385", "repo", AgentRemoved.RELEASED_IDLE_CLEAN, true, "released", at);

    CausationScope.with(cause, () -> port.get().announce(obsolete));
    port.get().announce(removed);

    long deadline = System.currentTimeMillis() + 10_000;
    while (published.size() < 2 && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(2, published.size(), published.toString());
    assertEquals(obsolete, published.get(0).event(), "in the order the calls were made");
    assertEquals(cause, published.get(0).parent(), "read on the caller's thread, carried across");
    assertEquals(removed, published.get(1).event());
    assertNull(published.get(1).parent(), "no scope: a root");
    for (Published one : published) {
      assertEquals("workspaces-lifecycle-events", one.thread());
      assertNotEquals(Thread.currentThread().getName(), one.thread());
      assertTrue(one.requestContext(), "the outbox's fallback gets a request context");
    }
  }

  /** The client proxy's target, so the assertion names the implementation and not the proxy. */
  private static Object unwrap(LifecycleAnnouncer bean) {
    return bean instanceof io.quarkus.arc.ClientProxy proxy ? proxy.arc_contextualInstance() : bean;
  }
}
