package eu.wohlben.qits.workspaces.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.QitsRawEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.workspaces.events.AgentCleanupDeferred;
import eu.wohlben.qits.workspaces.events.AgentHarnessExited;
import eu.wohlben.qits.workspaces.events.AgentNudged;
import eu.wohlben.qits.workspaces.events.AgentObsolete;
import eu.wohlben.qits.workspaces.events.AgentPlaced;
import eu.wohlben.qits.workspaces.events.AgentRemoved;
import eu.wohlben.qits.workspaces.events.AgentRequeued;
import eu.wohlben.qits.workspaces.events.AgentResumed;
import eu.wohlben.qits.workspaces.events.AgentStartRefused;
import eu.wohlben.qits.workspaces.events.AgentStarted;
import eu.wohlben.qits.workspaces.events.AgentYielded;
import eu.wohlben.qits.workspaces.events.DeskWaitClosed;
import eu.wohlben.qits.workspaces.events.WorkspaceFailed;
import eu.wohlben.qits.workspaces.events.WorkspaceRemoved;
import eu.wohlben.qits.workspaces.events.WorkspaceStarted;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link EventWireReflection}'s <em>completeness</em> — qits-ci's {@code
 * EventWireReflectionTest}, here. On a JVM every class reflects whether registered or not, so
 * nothing below proves the native binary publishes; only running it does. What is checkable is that
 * the registration still names every type the wire path touches, and that the string entry still
 * resolves.
 */
@QuarkusTest
class EventWireReflectionTest {

  private static final String MIXIN =
      "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin";

  @Inject @Any Instance<QitsDurableEventListener> listeners;

  @Test
  void theRegisteredTargetsAreExactlyTheTypesThatCrossTheWire() {
    RegisterForReflection registration =
        EventWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class; without it this file is a no-op");
    assertEquals(
        Set.of(
            AgentPlaced.class,
            AgentStarted.class,
            AgentStartRefused.class,
            AgentRequeued.class,
            AgentYielded.class,
            AgentNudged.class,
            AgentResumed.class,
            AgentObsolete.class,
            AgentCleanupDeferred.class,
            AgentRemoved.class,
            AgentHarnessExited.class,
            WorkspaceStarted.class,
            WorkspaceFailed.class,
            WorkspaceRemoved.class,
            DeskWaitClosed.class,
            eu.wohlben.qits.workspaces.targethost.TargetLeaseChanged.class,
            EventEnvelope.class,
            EventFrame.class),
        Set.of(registration.targets()),
        "the fourteen lifecycle events and DeskWaitClosed out, qits-system's lease event in, the PUT body, the frame — a new wire type is added"
            + " here");
  }

  /** A listener for a named event binds it, so the name must be a registered type's; "*" binds none. */
  @Test
  void everyDurableListenersSignatureNamesARegisteredType() {
    Set<String> registered =
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).targets())
            .stream()
            .map(Class::getSimpleName)
            .collect(Collectors.toSet());
    for (QitsDurableEventListener listener : listeners) {
      for (String signature : listener.signatures()) {
        if (QitsRawEventListener.ALL.equals(signature)) {
          continue;
        }
        assertTrue(
            registered.contains(signature),
            listener.getClass().getName() + " listens for " + signature + ", registered nowhere");
      }
    }
  }

  @Test
  void theMixinNamedByStringStillExistsAndStillHidesTheEnvelopesFields() throws Exception {
    Class<?> mixin = Class.forName(MIXIN);
    assertEquals(
        MIXIN,
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).classNames())
            .iterator()
            .next());
    Method eventId = mixin.getDeclaredMethod("eventId");
    assertNotNull(eventId.getAnnotation(JsonIgnore.class));
  }

  /** The flow the registration is for: canonicalized, PUT, framed, and bound back. */
  @Test
  void theWholeWirePathBindsBothWays() {
    AgentRemoved out =
        new AgentRemoved(
            "a-1", "w-1", "385", "repo", AgentRemoved.DISCARDED, false, "discarded by a person",
            Instant.parse("2026-10-14T09:24:09Z"));

    EventEnvelope envelope = EventEnvelope.of(out);
    assertEquals("AgentRemoved", CanonicalJson.parse(CanonicalJson.envelope(envelope)).get("name").asText());

    EventFrame frame =
        CanonicalJson.frame(
            CanonicalJson.canonicalize(
                new EventFrame(
                    UUID.randomUUID().toString(),
                    envelope.name(),
                    envelope.occurredAt(),
                    envelope.payload(),
                    null,
                    envelope.parentId(),
                    null)));
    AgentRemoved back = CanonicalJson.payloadTo(frame.payload(), AgentRemoved.class);

    assertEquals(out.agentId(), back.agentId());
    assertEquals(out.reason(), back.reason());
    assertEquals(out.harnessStopped(), back.harnessStopped());
    assertEquals(out.detail(), back.detail());
  }
}
