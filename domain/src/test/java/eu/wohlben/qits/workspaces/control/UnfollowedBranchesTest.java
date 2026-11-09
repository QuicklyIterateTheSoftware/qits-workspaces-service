package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link UnfollowedBranches#refusal}: which {@code BranchDeliveryChanged} waits can never match,
 * because qits-projects' delivery ledger never follows the branch named (qits-1960).
 */
class UnfollowedBranchesTest {

  private static Optional<String> check(String event, Condition... group) {
    return UnfollowedBranches.refusal(EventSelection.of(event, group));
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
  void aMaintenanceBumpBranchIsRefused() {
    String reason =
        refused(
            "BranchDeliveryChanged",
            Condition.exact("branch", "maintenance/automations/dependency-bump/x"));
    assertTrue(
        reason.startsWith(
            "BranchDeliveryChanged is never announced for"
                + " 'maintenance/automations/dependency-bump/x': the delivery ledger follows only"),
        reason);
    assertTrue(reason.contains("ReleaseRequestStateChanged"), reason);
  }

  @Test
  void aReleaseBackingBranchIsRefused() {
    refused("BranchDeliveryChanged", Condition.exact("branch", "release/x"));
  }

  @Test
  void theDefaultBranchMainIsRefused() {
    refused("BranchDeliveryChanged", Condition.exact("branch", "main"));
  }

  @Test
  void aTagRefIsRefused() {
    refused("BranchDeliveryChanged", Condition.exact("branch", "refs/tags/v1"));
  }

  @Test
  void aReleasedTagFormIsRefused() {
    refused("BranchDeliveryChanged", Condition.exact("branch", "2026.1108.163532"));
  }

  @Test
  void aMaintenancePrefixIsRefused() {
    refused("BranchDeliveryChanged", Condition.prefix("branch", "maintenance/"));
  }

  @Test
  void aReleasePrefixIsRefused() {
    refused("BranchDeliveryChanged", Condition.prefix("branch", "release/"));
  }

  @Test
  void anAgentBranchIsAdmitted() {
    admitted("BranchDeliveryChanged", Condition.exact("branch", "ticket/qits-1"));
  }

  @Test
  void aFeatureBranchIsAdmitted() {
    admitted("BranchDeliveryChanged", Condition.exact("branch", "feature/x"));
  }

  @Test
  void aTicketPrefixIsAdmitted() {
    admitted("BranchDeliveryChanged", Condition.prefix("branch", "ticket/"));
  }

  @Test
  void existenceOfTheBranchFieldIsAdmitted() {
    admitted("BranchDeliveryChanged", Condition.exists("branch"));
  }

  @Test
  void aConditionOnlyOnRepositoryIsAdmitted() {
    admitted("BranchDeliveryChanged", Condition.exact("repository", "qits-qits"));
  }

  @Test
  void anotherEventNamingAMaintenanceBranchIsAdmitted() {
    admitted("SCMRelease", Condition.exact("branch", "maintenance/x"));
  }

  @Test
  void aRefusedBranchInASecondWhenGroupIsStillRefused() {
    EventSelection selection =
        new EventSelection(
            "BranchDeliveryChanged",
            List.of(
                List.of(Condition.exact("repository", "qits-qits")),
                List.of(Condition.exact("branch", "main"))));
    assertTrue(UnfollowedBranches.refusal(selection).orElseThrow().contains("'main'"));
  }
}
