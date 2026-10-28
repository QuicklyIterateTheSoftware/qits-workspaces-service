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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The <b>wire contract</b> of the fourteen lifecycle events (qits-1236) and {@link DeskWaitClosed}
 * (qits-1618), pinned at the source and
 * serialised through {@link EventEnvelope#of} — the path {@code QitsEventBus.publish} takes, so the
 * payload asserted is the one that would be PUT, the {@code QitsEvent} mix-in applied.
 *
 * <p>No consumer imports this jar today: {@code qits wait --event AgentRemoved --when
 * 'agentId=<id>'} selects on the payload's field names, so <b>renaming a field here breaks every
 * declared wait that names it</b>, with nothing failing at compile time anywhere. These names are
 * the contract. {@link DeskWaitClosed} has a consumer that does read it, qits-projects, which hands
 * its {@code turn} to the project's front desk: its names are pinned in full below. Plain JUnit: {@code CanonicalJson} builds its own mapper and needs no container.
 */
class LifecycleEventsContractTest {

  private static final Instant AT = Instant.parse("2026-10-14T09:24:09Z");

  private static final String AGENT = "3f2b8a4e-5c1d-4e7f-9a6b-0c8d7e6f5a4b";

  private static final String WORK = "0a6d48f7-fe1c-4cfb-b123-f7fd9d84d7e2";

  private static final String REPO = "5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397";

  private static final String PROJECT = "9b1c3d5e-7f80-4a1b-8c2d-3e4f5a6b7c8d";

  private static final String WAIT = "c4d5e6f7-0819-4a2b-9c3d-4e5f60718293";

  private static DeskWaitClosed deskWaitMatched() {
    return new DeskWaitClosed(
        UUID.randomUUID(), WAIT, PROJECT, "rr 12 finalized", DeskWaitClosed.MATCHED,
        "ReleaseRequestFinalized", "ev-42", "Your wait \"rr 12 finalized\" matched: …", AT);
  }

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
        new AgentNudged(AGENT, WORK, "385", REPO, 1, AgentNudged.SELF_BLOCKED, AT),
        with(agent, "nudgeCount", "reason"));
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
            AGENT, WORK, "385", REPO, AgentRemoved.RELEASED_IDLE_CLEAN, true, "released, idle and clean",
            null, AT),
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
    out.put(
        deskWaitMatched(),
        List.of(
            "closedAt", "eventName", "label", "matchedEventId", "projectId", "state", "turn", "waitId"));
    // Epic qits-1681: an agent's waits and its activity flips. Each carries projectId.
    List<String> agentInProject = with(agent, "projectId");
    out.put(
        new AgentWaitOpened(
            AGENT, WORK, "385", REPO, PROJECT, "wait-1", "release request x-rr-1 to end",
            "ReleaseRequestStateChanged", "RELEASE_REQUEST", "x-rr-1", List.of("FINALIZED"), AT),
        with(agentInProject, "eventName", "label", "subjectKind", "subjectRef", "targetStates", "waitId"));
    out.put(
        new AgentWaitChanged(
            AGENT, WORK, "385", REPO, PROJECT, "wait-1", "RELEASE_REQUEST", "x-rr-1", "x-rr-1",
            "RELEASED", Instant.parse("2026-10-13T09:00:00Z"), AT),
        with(
            agentInProject, "subjectKind", "subjectQualifiedId", "subjectRef", "subjectState",
            "subjectStateAt", "waitId"));
    out.put(
        new AgentWaitClosed(AGENT, WORK, "385", REPO, PROJECT, "wait-1", AgentWaitClosed.MATCHED, "ev-1", AT),
        with(agentInProject, "matchedEventId", "state", "waitId"));
    out.put(
        new AgentActivityChanged(
            AGENT, WORK, "385", REPO, PROJECT, "IDLE", Instant.parse("2026-10-13T09:00:00Z"), AT),
        with(agentInProject, "activity", "at", "waitingSince"));
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
            "AgentNudged",
            "AgentResumed",
            "AgentObsolete",
            "AgentCleanupDeferred",
            "AgentRemoved",
            "AgentHarnessExited",
            "WorkspaceStarted",
            "WorkspaceFailed",
            "WorkspaceRemoved",
            "DeskWaitClosed",
            "AgentWaitOpened",
            "AgentWaitChanged",
            "AgentWaitClosed",
            "AgentActivityChanged"),
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
      if (!(event instanceof DeskWaitClosed) && !(event instanceof AgentActivityChanged)) {
        // DeskWaitClosed carries closedAt in its payload, by contract, and AgentActivityChanged its
        // `at` (the frame's time, which is also its occurrence).
        assertFalse(envelope.payload().contains("2026-10-14"), event.name());
      }
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
                "released, idle and clean", null, AT)));
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
   * {@code discardedBy} names the token subject of the caller who discarded the agent (qits-1694):
   * present on the wire only when known, and additive — a null stays an absent key, exactly as
   * every other optional field here does, so an old consumer ignores it. Production sets it only
   * alongside {@link AgentRemoved#DISCARDED} (the agent-page discard); the wire itself admits it on
   * any reason, which is what this asserts.
   */
  @Test
  void discardedByNamesTheDiscardingCallerWhenKnown() {
    AgentRemoved discardedByAPerson =
        new AgentRemoved(
            AGENT, WORK, "385", REPO, AgentRemoved.DISCARDED, true, "discarded by a person",
            "person@example.com", AT);
    JsonNode discardedPayload = CanonicalJson.parse(EventEnvelope.of(discardedByAPerson).payload());
    assertEquals("person@example.com", discardedPayload.get("discardedBy").asText());

    AgentRemoved discardedByUnknownCaller =
        new AgentRemoved(
            AGENT, WORK, "385", REPO, AgentRemoved.DISCARDED, true, "discarded by a person", null, AT);
    JsonNode unknownPayload = CanonicalJson.parse(EventEnvelope.of(discardedByUnknownCaller).payload());
    assertFalse(unknownPayload.has("discardedBy"), "null is an absent key, not a null one");
  }

  /**
   * DeskWaitClosed, as qits-projects reads it: every field a string, {@code closedAt} ISO-8601 and
   * the envelope's {@code occurredAt}; a timeout names no matched event, by the key's absence. The
   * id is the one the close stored — two records of one close are one event.
   */
  @Test
  void aDeskWaitClosedIsTheConsumersShape() {
    assertEquals(
        "{\"closedAt\":\"2026-10-14T09:24:09Z\",\"eventName\":\"ReleaseRequestFinalized\","
            + "\"label\":\"rr 12 finalized\",\"matchedEventId\":\"ev-42\",\"projectId\":\"" + PROJECT
            + "\",\"state\":\"MATCHED\",\"turn\":\"Your wait \\\"rr 12 finalized\\\" matched: …\","
            + "\"waitId\":\"" + WAIT + "\"}",
        CanonicalJson.payload(deskWaitMatched()));
    UUID id = UUID.randomUUID();
    DeskWaitClosed timedOut =
        new DeskWaitClosed(
            id, WAIT, PROJECT, "rr 12 finalized", DeskWaitClosed.TIMED_OUT, "ReleaseRequestFinalized",
            null, "Your wait \"rr 12 finalized\" timed out after 2h: …", AT);
    JsonNode payload = CanonicalJson.parse(EventEnvelope.of(timedOut).payload());
    assertEquals("TIMED_OUT", payload.get("state").asText());
    assertFalse(payload.has("matchedEventId"));
    assertEquals(AT, EventEnvelope.of(timedOut).occurredAt());
    assertEquals(id, timedOut.eventId());
    assertEquals(
        id,
        new DeskWaitClosed(
                id, WAIT, PROJECT, "rr 12 finalized", DeskWaitClosed.TIMED_OUT,
                "ReleaseRequestFinalized", null, "…", AT)
            .eventId());
    assertEquals(List.of("MATCHED", "TIMED_OUT"), List.of(DeskWaitClosed.MATCHED, DeskWaitClosed.TIMED_OUT));
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
