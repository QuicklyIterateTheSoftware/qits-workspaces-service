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
 * The wire contract {@link TargetLeaseChanged}'s javadoc names: the record is spelled exactly as
 * qits-system's {@code
 * eu.wohlben.qits.system.targets.events.TargetLeaseChanged} (epic qits-1481, qits-1818 for {@code
 * oomKills}/{@code oomScope}), and this class holds the two together. Plain JUnit: {@code
 * CanonicalJson} builds its own mapper and needs no container.
 */
class TargetLeaseChangedContractTest {

  @Test
  void theComponentsAreQitsSystemsInTheSameOrder() {
    List<String> components =
        Arrays.stream(TargetLeaseChanged.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    assertEquals(
        List.of(
            "eventId",
            "leaseId",
            "ownerApp",
            "ownerRef",
            "slotType",
            "targetId",
            "state",
            "exitCode",
            "detail",
            "oomKills",
            "oomScope",
            "occurredAt"),
        components);
  }

  @Test
  void theWireNameIsTheClassName() {
    assertEquals("TargetLeaseChanged", TargetLeaseChanged.class.getSimpleName());
  }

  /**
   * What {@link TargetLeaseEvents} actually reads: a payload naming {@code oomKills} and {@code
   * oomScope} decodes them, and a payload from a daemon too old to send them — or qits-system's
   * own listing of a lease that has never been killed — leaves both null rather than failing.
   */
  @Test
  void oomFieldsDecodeByNameAndAreAbsentOnAnOlderPayload() {
    UUID leaseId = UUID.randomUUID();
    String withKills =
        "{\"detail\":null,\"exitCode\":null,\"leaseId\":\""
            + leaseId
            + "\",\"oomKills\":3,\"oomScope\":\"LEASE\",\"ownerApp\":\"qits-workspaces\","
            + "\"ownerRef\":\"9\",\"slotType\":\"WORKSPACE\",\"state\":\"RUNNING\","
            + "\"targetId\":null}";

    TargetLeaseChanged event = CanonicalJson.payloadTo(withKills, TargetLeaseChanged.class);

    assertEquals(leaseId.toString(), event.leaseId());
    assertEquals(3L, event.oomKills());
    assertEquals("LEASE", event.oomScope());

    String withoutKills =
        "{\"leaseId\":\""
            + leaseId
            + "\",\"ownerApp\":\"qits-workspaces\",\"ownerRef\":\"9\",\"slotType\":\"WORKSPACE\","
            + "\"state\":\"RUNNING\"}";

    TargetLeaseChanged older = CanonicalJson.payloadTo(withoutKills, TargetLeaseChanged.class);

    assertNull(older.oomKills());
    assertNull(older.oomScope());
  }
}
