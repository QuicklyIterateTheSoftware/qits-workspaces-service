package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link EventPayloadCatalog#refusal}: which wait paths can never match their event (qits-1849), and
 * which the evaluator can match and so must stay admitted.
 */
class EventPayloadCatalogTest {

  private static final EventPayloadCatalog CATALOG = EventPayloadCatalog.shipped();

  private static Optional<String> check(String event, Condition... group) {
    return CATALOG.refusal(EventSelection.of(event, group));
  }

  private static String refused(String event, Condition... group) {
    Optional<String> reason = check(event, group);
    assertTrue(reason.isPresent(), "expected a refusal for " + List.of(group));
    return reason.get();
  }

  private static void admitted(String event, Condition... group) {
    assertEquals(Optional.empty(), check(event, group), "expected " + List.of(group) + " admitted");
  }

  @Test
  void anUnknownFieldIsRefusedWithTheClosestAndEveryField() {
    String reason = refused("BuildStatusChanged", Condition.exact("repositoryName", "x"));
    assertEquals(
        "BuildStatusChanged carries no field 'repositoryName'; did you mean 'repoName'? Its fields:"
            + " branch, commitSha, phase, previousStatus, projectId, releaseRequestId,"
            + " releaseRequestQualifiedId, repoId, repoName, runId, status.",
        reason);
  }

  @Test
  void theHintPrefersACaseOnlyDifferenceThenTheSmallestEdit() {
    assertTrue(refused("BuildStatusChanged", Condition.exact("RunId", "1")).contains("did you mean 'runId'?"));
    assertTrue(refused("BuildStatusChanged", Condition.exact("stauts", "1")).contains("did you mean 'status'?"));
    assertTrue(
        refused("BuildStatusChanged", Condition.exact("sha", "1")).contains("did you mean 'commitSha'?"),
        "one name containing the other");
    assertTrue(
        refused("ReleaseRequestStateChanged", Condition.exact("repositoryId", "1"))
            .contains("did you mean 'repoId'?"),
        "an abbreviation");
    String none = refused("BuildStatusChanged", Condition.exact("zzzzzzzz", "1"));
    assertTrue(!none.contains("did you mean"), none);
  }

  @Test
  void theEnvelopeFieldsAreNoPayloadField() {
    String reason = refused("AgentRemoved", Condition.exists("occurredAt"));
    assertTrue(reason.startsWith("AgentRemoved carries no field 'occurredAt' (occurredAt rides the envelope"), reason);
    refused("AgentRemoved", Condition.exact("eventId", "e"));
  }

  @Test
  void anUnknownFieldInsideAListElementNamesThatElementsFields() {
    String reason = refused("EntityTransitioned", Condition.exact("entities[].state", "DONE"));
    assertTrue(
        reason.startsWith(
            "EntityTransitioned carries no field 'entities[].state'; did you mean 'entities[].status'?"
                + " The fields of 'entities[]': archetype, changedBy, entityId,"),
        reason);
  }

  @Test
  void bracketsOnAFieldThatIsNoListAreRefused() {
    assertTrue(refused("BuildStatusChanged", Condition.exact("status[]", "x")).contains("is not a list"));
  }

  @Test
  void descendingBelowASingleValueIsRefused() {
    String reason = refused("BuildStatusChanged", Condition.exact("status.x", "y"));
    assertTrue(reason.contains("'status' is a single value"), reason);
    assertTrue(
        refused("ReleaseRequestStateChanged", Condition.exact("branches[].name", "b"))
            .contains("'branches[]' is a single value"));
  }

  @Test
  void passingThroughAListWithoutBracketsIsRefused() {
    String reason = refused("EntityTransitioned", Condition.exact("entities.status", "DONE"));
    assertTrue(reason.contains("write 'entities[].status'"), reason);
  }

  @Test
  void comparingAWholeListIsRefusedWithAHintToItsElements() {
    String scalars = refused("ReleaseRequestStateChanged", Condition.exact("branches", "ticket/x"));
    assertTrue(scalars.contains("write 'branches[]'"), scalars);
    String objects = refused("EntityTransitioned", Condition.prefix("entities", "x"));
    assertTrue(objects.contains("'entities[].<field>' (archetype, "), objects);
    String elements = refused("EntityTransitioned", Condition.exact("entities[]", "x"));
    assertTrue(elements.contains("is an object"), elements);
  }

  @Test
  void presenceHoldsOnEveryDeclaredFieldIncludingABareList() {
    admitted("EntityTransitioned", Condition.absent("entities"));
    admitted("EntityTransitioned", Condition.exists("entities"));
    admitted("EntityTransitioned", Condition.exists("entities[]"));
    admitted("EntityTransitioned", Condition.absent("entities[].parentId"));
    admitted("AgentRemoved", Condition.absent("discardedBy"));
    admitted("BuildStatusChanged", Condition.exists("releaseRequestQualifiedId"));
  }

  @Test
  void listElementsAreReachedWithBrackets() {
    admitted("ReleaseRequestStateChanged", Condition.exact("branches[]", "ticket/qits-1849"));
    admitted("EntityCommented", Condition.exact("mentions[]", "desk"));
    admitted("AgentWaitOpened", Condition.prefix("targetStates[]", "FIN"));
    admitted(
        "EntityTransitioned",
        Condition.exact("entities[].entityId", "e"),
        Condition.exact("entities[].status", "VERIFIED"));
  }

  /** What the CLI's presets write, and what {@link WaitSubjects} reads, must stay admitted. */
  @Test
  void thePresetPathsAreAdmitted() {
    admitted(
        "ReleaseRequestStateChanged",
        Condition.exact("releaseRequestQualifiedId", "qits-qits-rr-1"),
        Condition.exact("state", "FINALIZED"));
    admitted("ReleaseRequestStateChanged", Condition.exact("releaseRequestId", "r"));
    admitted("BuildStatusChanged", Condition.exact("runId", "r"), Condition.exact("status", "SUCCESS"));
    admitted("BuildSuccessful", Condition.exact("runId", "r"));
    admitted("BuildFailed", Condition.exact("runId", "r"));
    admitted("EntityCommented", Condition.exact("entityId", "e"), Condition.exact("changedBy", "s"));
    admitted(
        "BranchDeliveryChanged",
        Condition.exact("branch", "ticket/x"),
        Condition.exact("repository", "r"),
        Condition.exact("step", "LIVE"),
        Condition.exact("everywhere", "LIVE"));
    admitted("AgentRemoved", Condition.exact("agentId", "a"));
    admitted("SCMRelease", Condition.exact("repositoryName", "qits-qits"));
  }

  @Test
  void everyGroupIsChecked() {
    EventSelection selection =
        new EventSelection(
            "BuildStatusChanged",
            List.of(
                List.of(Condition.exact("runId", "r")),
                List.of(Condition.exact("runId", "r"), Condition.exact("repositoryName", "x"))));
    assertTrue(CATALOG.refusal(selection).orElseThrow().contains("'repositoryName'"));
  }

  @Test
  void anEventTheCatalogDoesNotNameIsNotChecked() {
    admitted("SomethingElseEntirely", Condition.exact("anything.at[].all", "x"));
    assertEquals(Optional.empty(), CATALOG.refusal(EventSelection.of("BuildStatusChanged")));
  }
}
