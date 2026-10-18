package eu.wohlben.qits.workspaces.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The <b>wire contract</b> of the thirteen lifecycle events (qits-1236), pinned at the source and
 * serialised through {@link EventEnvelope#of} — the path {@code QitsEventBus.publish} takes, so the
 * payload asserted is the one that would be PUT, the {@code QitsEvent} mix-in applied.
 *
 * <p>No consumer imports this jar today: {@code qits wait --event AgentRemoved --when
 * 'agentId=<id>'} selects on the payload's field names, so <b>renaming a field here breaks every
 * declared wait that names it</b>, with nothing failing at compile time anywhere. These names are
 * the contract. Plain JUnit: {@code CanonicalJson} builds its own mapper and needs no container.
 */
class LifecycleEventsContractTest {

  private static final Instant AT = Instant.parse("2026-10-14T09:24:09Z");

  private static final String AGENT = "3f2b8a4e-5c1d-4e7f-9a6b-0c8d7e6f5a4b";

  private static final String WORK = "0a6d48f7-fe1c-4cfb-b123-f7fd9d84d7e2";

  private static final String REPO = "5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397";

  /** Every event, every field set, with the sorted payload keys a subscriber can select on. */
  private static Map<QitsEvent, List<String>> everyEvent() {
    List<String> agent = List.of("agentId", "repositoryId", "workId", "workspaceId");
    Map<QitsEvent, List<String>> out = new LinkedHashMap<>();
    out.put(new AgentPlaced(AGENT, WORK, "385", REPO, AT), agent);
    out.put(
        new AgentStarted(AGENT, WORK, "385", REPO, "cmd-1", true, false, AT),
        with(agent, "commandId", "launched", "resumed"));
    out.put(
        new AgentStartRefused(AGENT, WORK, "385", REPO, "fatal: already checked out", AT),
        with(agent, "detail"));
    out.put(
        new AgentRequeued(
            AGENT, WORK, "385", REPO, AgentRequeued.WORKSPACE_FAILED, "Workspace 385 did not come up.", AT),
        with(agent, "detail", "reason"));
    out.put(new AgentYielded(AGENT, WORK, "385", REPO, AT), agent);
    out.put(
        new AgentResumed(AGENT, WORK, "385", REPO, AgentResumed.TURN_PENDING, AT),
        with(agent, "reason"));
    out.put(
        new AgentObsolete(AGENT, WORK, "385", REPO, "ticket/qits-1236", "2026.1014.1", "abc123", AT),
        with(agent, "branch", "commitSha", "version"));
    out.put(
        new AgentCleanupDeferred(
            AGENT, WORK, "385", REPO, AgentCleanupDeferred.ITEM_NOT_CLOSED, "its item is IMPLEMENTED, not closed", AT),
        with(agent, "detail", "reason"));
    out.put(
        new AgentRemoved(
            AGENT, WORK, "385", REPO, AgentRemoved.RELEASED_IDLE_CLEAN, true, "released, idle and clean", AT),
        with(agent, "detail", "harnessStopped", "reason"));
    out.put(
        new AgentHarnessExited(
            AGENT, WORK, "385", REPO, "cmd-1", "EXITED", 0, AgentHarnessExited.SELF, AT),
        with(agent, "commandId", "exitCode", "status", "stoppedBy"));
    out.put(new WorkspaceStarted("385", REPO, AT), List.of("repositoryId", "workspaceId"));
    out.put(
        new WorkspaceFailed("385", REPO, "no workspace-daemon dialed home within 30000ms", AT),
        List.of("repositoryId", "runtimeError", "workspaceId"));
    out.put(
        new WorkspaceRemoved("385", REPO, WorkspaceRemoved.NO_AGENT_LEFT, AT),
        List.of("reason", "repositoryId", "workspaceId"));
    return out;
  }

  private static List<String> with(List<String> base, String... more) {
    List<String> all = new ArrayList<>(base);
    all.addAll(List.of(more));
    return all.stream().sorted().toList();
  }

  @Test
  void theWireNameIsTheClassName() {
    assertEquals(
        List.of(
            "AgentPlaced",
            "AgentStarted",
            "AgentStartRefused",
            "AgentRequeued",
            "AgentYielded",
            "AgentResumed",
            "AgentObsolete",
            "AgentCleanupDeferred",
            "AgentRemoved",
            "AgentHarnessExited",
            "WorkspaceStarted",
            "WorkspaceFailed",
            "WorkspaceRemoved"),
        everyEvent().keySet().stream().map(e -> EventEnvelope.of(e).name()).toList());
  }

  @Test
  void eachPayloadCarriesExactlyItsFieldsAndNoneOfTheEnvelopes() {
    for (Map.Entry<QitsEvent, List<String>> entry : everyEvent().entrySet()) {
      QitsEvent event = entry.getKey();
      EventEnvelope envelope = EventEnvelope.of(event);
      assertEquals(AT, envelope.occurredAt(), event.name());
      assertEquals(4, event.eventId().version(), event.name());

      JsonNode payload = CanonicalJson.parse(envelope.payload());
      List<String> keys = new ArrayList<>();
      payload.fieldNames().forEachRemaining(keys::add);
      assertEquals(entry.getValue(), keys.stream().sorted().toList(), event.name());
      assertFalse(envelope.payload().contains(event.eventId().toString()), event.name());
      assertFalse(envelope.payload().contains("2026-10-14"), event.name());
    }
    // Two announcements of the same facts are two occurrences.
    assertNotEquals(
        new AgentPlaced(AGENT, WORK, "385", REPO, AT).eventId(),
        new AgentPlaced(AGENT, WORK, "385", REPO, AT).eventId());
  }

  /** Ids are strings, booleans are booleans, and an absent field is an absent key. */
  @Test
  void theValuesAreTypedAsASubscriberSelectsOnThem() {
    assertEquals(
        "{\"agentId\":\"" + AGENT + "\",\"detail\":\"released, idle and clean\","
            + "\"harnessStopped\":true,\"reason\":\"RELEASED_IDLE_CLEAN\",\"repositoryId\":\"" + REPO
            + "\",\"workId\":\"" + WORK + "\",\"workspaceId\":\"385\"}",
        CanonicalJson.payload(
            new AgentRemoved(
                AGENT, WORK, "385", REPO, AgentRemoved.RELEASED_IDLE_CLEAN, true,
                "released, idle and clean", AT)));
    // A never-placed agent has no workspace, and an obsolete one's release may name no commit.
    assertEquals(
        "{\"agentId\":\"" + AGENT + "\",\"branch\":\"ticket/x\",\"repositoryId\":\"" + REPO
            + "\",\"version\":\"2026.1.1\",\"workId\":\"" + WORK + "\"}",
        CanonicalJson.payload(
            new AgentObsolete(AGENT, WORK, null, REPO, "ticket/x", "2026.1.1", null, AT)));
    // A harness that ended without an exit code says so by the key's absence.
    assertEquals(
        "{\"agentId\":\"" + AGENT + "\",\"commandId\":\"cmd-1\",\"repositoryId\":\"" + REPO
            + "\",\"status\":\"KILLED\",\"stoppedBy\":\"KILLED\",\"workId\":\"" + WORK
            + "\",\"workspaceId\":\"385\"}",
        CanonicalJson.payload(
            new AgentHarnessExited(
                AGENT, WORK, "385", REPO, "cmd-1", "KILLED", null, AgentHarnessExited.KILLED, AT)));
  }

  /**
   * Every record leads with {@code eventId} and ends with {@code occurredAt}, the two the contract
   * declares, and none has a component the canonical mix-in would silently drop.
   */
  @Test
  void theRecordComponentsAreThePublishedOnes() {
    for (QitsEvent event : everyEvent().keySet()) {
      List<String> components =
          Arrays.stream(event.getClass().getRecordComponents()).map(RecordComponent::getName).toList();
      assertEquals("eventId", components.get(0), event.name());
      assertEquals("occurredAt", components.get(components.size() - 1), event.name());
      for (String envelopeWord : List.of("name", "signature")) {
        assertFalse(components.contains(envelopeWord), event.name() + " has " + envelopeWord);
      }
    }
  }

  /** The reason words, spelled once: each constant is its own name, upper case. */
  @Test
  void theReasonWordsAreTheirOwnNames() throws Exception {
    for (Class<?> type :
        List.of(
            AgentRequeued.class,
            AgentResumed.class,
            AgentCleanupDeferred.class,
            AgentRemoved.class,
            AgentHarnessExited.class,
            WorkspaceRemoved.class)) {
      for (var field : type.getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
            && field.getType() == String.class) {
          assertEquals(field.getName(), field.get(null), type.getSimpleName());
        }
      }
    }
    assertEquals(
        List.of(
            "OBSOLETE_NEVER_PLACED",
            "OBSOLETE_WORKSPACE_GONE",
            "UNKNOWN_TO_DAEMON",
            "RELEASED_IDLE_CLEAN",
            "DROPPED_IDLE_CLEAN",
            "DISCARDED"),
        List.of(
            AgentRemoved.OBSOLETE_NEVER_PLACED,
            AgentRemoved.OBSOLETE_WORKSPACE_GONE,
            AgentRemoved.UNKNOWN_TO_DAEMON,
            AgentRemoved.RELEASED_IDLE_CLEAN,
            AgentRemoved.DROPPED_IDLE_CLEAN,
            AgentRemoved.DISCARDED));
    assertEquals(
        List.of("DAEMON_REMOVE", "DAEMON_YIELD", "KILLED", "SELF"),
        List.of(
            AgentHarnessExited.DAEMON_REMOVE,
            AgentHarnessExited.DAEMON_YIELD,
            AgentHarnessExited.KILLED,
            AgentHarnessExited.SELF));
  }
}
