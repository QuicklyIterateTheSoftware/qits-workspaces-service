package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import eu.wohlben.qits.workspaces.control.WaitSubjects.Kind;
import eu.wohlben.qits.workspaces.control.WaitSubjects.Subject;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link WaitSubjects}: what a wait's selection names, and what an event says of it (epic qits-1681). */
class WaitSubjectsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** One group per state, the shape the CLI's presets write. */
  private static EventSelection perState(String event, String refPath, String ref, String statePath, String... states) {
    return new EventSelection(
        event,
        Arrays.stream(states)
            .map(s -> List.of(Condition.exact(refPath, ref), Condition.exact(statePath, s)))
            .toList());
  }

  private static JsonNode json(String text) throws Exception {
    return JSON.readTree(text);
  }

  @Test
  void theReleaseRequestPresetNamesTheRequestAndItsEndStates() {
    Subject subject =
        WaitSubjects.parse(
            perState(
                "ReleaseRequestStateChanged",
                "releaseRequestQualifiedId",
                "qits-qits-rr-12",
                "state",
                "FINALIZED",
                "REJECTED",
                "FAILED",
                "WITHDRAWN",
                "OBSOLETE"));
    assertEquals(Kind.RELEASE_REQUEST, subject.kind());
    assertEquals("qits-qits-rr-12", subject.ref());
    assertEquals(List.of("FINALIZED", "REJECTED", "FAILED", "WITHDRAWN", "OBSOLETE"), subject.targetStates());
  }

  @Test
  void aRequestNamedByItsUuidIsARequestToo() {
    Subject subject =
        WaitSubjects.parse(perState("ReleaseRequestStateChanged", "releaseRequestId", "u-1", "state", "FINALIZED"));
    assertEquals(Kind.RELEASE_REQUEST, subject.kind());
    assertEquals("u-1", subject.ref());
  }

  @Test
  void theCiRunPresetNamesTheRun() {
    Subject subject =
        WaitSubjects.parse(perState("BuildStatusChanged", "runId", "r-1", "status", "SUCCESS", "FAILED"));
    assertEquals(new Subject(Kind.CI_RUN, "r-1", List.of("SUCCESS", "FAILED")), subject);
  }

  @Test
  void aBuildOutcomeEventNamesTheRunWithNoTargetState() {
    assertEquals(
        new Subject(Kind.CI_RUN, "r-1", List.of()),
        WaitSubjects.parse(EventSelection.of("BuildFailed", Condition.exact("runId", "r-1"))));
    assertEquals(
        new Subject(Kind.CI_RUN, "r-1", List.of()),
        WaitSubjects.parse(EventSelection.of("BuildSuccessful", Condition.exact("runId", "r-1"))));
  }

  @Test
  void theWorkPresetNamesTheItemAndItsStatus() {
    Subject subject =
        WaitSubjects.parse(
            EventSelection.of(
                "EntityTransitioned",
                Condition.exact("entities[].entityId", "e-1"),
                Condition.exact("entities[].status", "VERIFIED")));
    assertEquals(new Subject(Kind.WORK_ITEM, "e-1", List.of("VERIFIED")), subject);
  }

  @Test
  void aCommentWaitNamesItsItemsThread() {
    assertEquals(
        new Subject(Kind.COMMENTS, "e-1", List.of()),
        WaitSubjects.parse(EventSelection.of("EntityCommented", Condition.exact("entityId", "e-1"))));
  }

  @Test
  void groupsNamingDifferentSubjectsAreOther() {
    EventSelection mixed =
        new EventSelection(
            "BuildStatusChanged",
            List.of(
                List.of(Condition.exact("runId", "r-1"), Condition.exact("status", "SUCCESS")),
                List.of(Condition.exact("runId", "r-2"), Condition.exact("status", "SUCCESS"))));
    assertEquals(Kind.OTHER, WaitSubjects.parse(mixed).kind());
    assertNull(WaitSubjects.parse(mixed).ref());
  }

  @Test
  void aGroupWithoutTheRefIsOther() {
    EventSelection partial =
        new EventSelection(
            "BuildStatusChanged",
            List.of(
                List.of(Condition.exact("runId", "r-1")),
                List.of(Condition.exact("status", "SUCCESS"))));
    assertEquals(Kind.OTHER, WaitSubjects.parse(partial).kind());
  }

  @Test
  void aPrefixOnTheRefIsOther() {
    assertEquals(
        Kind.OTHER,
        WaitSubjects.parse(EventSelection.of("BuildStatusChanged", Condition.prefix("runId", "r-"))).kind());
  }

  @Test
  void anUnknownEventIsOther() {
    assertEquals(
        Kind.OTHER,
        WaitSubjects.parse(EventSelection.of("SCMRelease", Condition.exact("repositoryName", "x"))).kind());
    assertEquals(Kind.OTHER, WaitSubjects.parse(EventSelection.of("BuildStatusChanged")).kind());
  }

  @Test
  void aRequestEventSaysItsStateWhenItNamesTheSubjectByEitherId() throws Exception {
    JsonNode event = json("{\"releaseRequestId\":\"u-1\",\"releaseRequestQualifiedId\":\"x-rr-1\",\"state\":\"RELEASED\"}");
    assertEquals(Optional.of("RELEASED"), WaitSubjects.stateIn("ReleaseRequestStateChanged", event, List.of("x-rr-1")));
    assertEquals(
        Optional.of("RELEASED"),
        WaitSubjects.stateIn("ReleaseRequestStateChanged", event, Arrays.asList("x", "u-1", null)));
    assertEquals(Optional.empty(), WaitSubjects.stateIn("ReleaseRequestStateChanged", event, List.of("other")));
  }

  @Test
  void runEventsSayTheRunsStatus() throws Exception {
    assertEquals(
        Optional.of("RUNNING"),
        WaitSubjects.stateIn("BuildStatusChanged", json("{\"runId\":\"r-1\",\"status\":\"RUNNING\"}"), List.of("r-1")));
    assertEquals(
        Optional.of("SUCCESS"), WaitSubjects.stateIn("BuildSuccessful", json("{\"runId\":\"r-1\"}"), List.of("r-1")));
    assertEquals(
        Optional.of("FAILED"), WaitSubjects.stateIn("BuildFailed", json("{\"runId\":\"r-1\"}"), List.of("r-1")));
    assertEquals(
        Optional.empty(), WaitSubjects.stateIn("BuildFailed", json("{\"runId\":\"r-2\"}"), List.of("r-1")));
  }

  @Test
  void anEntityTransitionSaysTheNamedEntitysStatus() throws Exception {
    JsonNode event =
        json("{\"entities\":[{\"entityId\":\"e-2\",\"status\":\"DONE\"},{\"entityId\":\"e-1\",\"status\":\"VERIFIED\"}]}");
    assertEquals(Optional.of("VERIFIED"), WaitSubjects.stateIn("EntityTransitioned", event, List.of("e-1")));
    assertEquals(Optional.empty(), WaitSubjects.stateIn("EntityTransitioned", event, List.of("e-3")));
  }

  @Test
  void aCommentCarriesNoState() throws Exception {
    assertEquals(
        Optional.empty(), WaitSubjects.stateIn("EntityCommented", json("{\"entityId\":\"e-1\"}"), List.of("e-1")));
  }

  @Test
  void finalStatesPerKind() {
    assertTrue(WaitSubjects.isFinal(Kind.RELEASE_REQUEST, "FINALIZED"));
    assertTrue(WaitSubjects.isFinal(Kind.RELEASE_REQUEST, "OBSOLETE"));
    assertFalse(WaitSubjects.isFinal(Kind.RELEASE_REQUEST, "REJECTED"));
    assertFalse(WaitSubjects.isFinal(Kind.RELEASE_REQUEST, "RELEASED"));
    assertTrue(WaitSubjects.isFinal(Kind.CI_RUN, "CANCELLED"));
    assertFalse(WaitSubjects.isFinal(Kind.CI_RUN, "QUEUED"));
    assertFalse(WaitSubjects.isFinal(Kind.CI_RUN, "RUNNING"));
    assertTrue(WaitSubjects.isFinal(Kind.WORK_ITEM, "DROPPED"));
    assertFalse(WaitSubjects.isFinal(Kind.WORK_ITEM, "VERIFIED"));
    assertFalse(WaitSubjects.isFinal(Kind.COMMENTS, "DONE"));
    assertFalse(WaitSubjects.isFinal(Kind.OTHER, "DONE"));
    assertFalse(WaitSubjects.isFinal(Kind.RELEASE_REQUEST, null));
  }

  @Test
  void aStoredKindReadsBack() {
    assertNull(WaitSubjects.kindOf(null));
    assertEquals(Kind.CI_RUN, WaitSubjects.kindOf("CI_RUN"));
    assertEquals(Kind.OTHER, WaitSubjects.kindOf("SOMETHING_NEW"));
  }
}
