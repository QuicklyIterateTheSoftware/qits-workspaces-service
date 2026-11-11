package eu.wohlben.qits.workspaces.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.eventstream.control.CanonicalJson;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The wire contract {@link TargetLoginChanged}'s javadoc names (qits-2088): the record is spelled
 * exactly as qits-system's {@code eu.wohlben.qits.system.targets.events.TargetLoginChanged}, and
 * this class holds the two together. Plain JUnit, as {@link TargetLeaseChangedContractTest}.
 */
class TargetLoginChangedContractTest {

  @Test
  void theComponentsAreQitsSystemsInTheSameOrder() {
    List<String> components =
        Arrays.stream(TargetLoginChanged.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    assertEquals(
        List.of("eventId", "targetId", "targetName", "claude", "kimi", "checkedAt", "occurredAt"),
        components);
  }

  @Test
  void theWireNameIsTheClassName() {
    assertEquals("TargetLoginChanged", TargetLoginChanged.class.getSimpleName());
  }

  /** What {@link TargetLoginEvents} reads decodes by name; a harness the payload leaves out is null. */
  @Test
  void aPayloadDecodesByNameAndAMissingHarnessIsNull() {
    UUID targetId = UUID.randomUUID();
    String payload =
        "{\"checkedAt\":\"2026-11-11T10:42:00Z\",\"claude\":\"ABSENT\",\"targetId\":\""
            + targetId
            + "\",\"targetName\":\"lab\"}";

    TargetLoginChanged event = CanonicalJson.payloadTo(payload, TargetLoginChanged.class);

    assertEquals(targetId.toString(), event.targetId());
    assertEquals("lab", event.targetName());
    assertEquals("ABSENT", event.claude());
    assertNull(event.kimi());
    assertEquals("2026-11-11T10:42:00Z", event.checkedAt());
  }
}
