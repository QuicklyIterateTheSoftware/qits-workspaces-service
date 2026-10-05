package eu.wohlben.qits.workspaces.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** The health report's own bound (qits-850): kept apart from what a runner says of itself. */
class WorkspaceRunnerCapabilitiesTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static ObjectNode report(int dataChars) {
    ObjectNode report = JSON.createObjectNode();
    report.put("ok", true);
    ObjectNode check = report.putArray("checks").addObject();
    check.put("name", "nodeInventory");
    check.put("ok", true);
    check.putObject("data").put("containers", "x".repeat(dataChars));
    return report;
  }

  @Test
  void aLargeReportKeepsItsVerdictsAndDropsItsData() {
    String stored =
        WorkspaceRunnerCapabilities.withHealth(
            "{\"version\":\"1.0\"}",
            report(WorkspaceRunnerCapabilities.HEALTH_MAX_CHARS + 1));

    JsonNode health = WorkspaceRunnerCapabilities.decode(stored).path("health");
    assertTrue(health.path(WorkspaceRunnerCapabilities.DATA_OMITTED).asBoolean());
    assertTrue(health.path("checks").get(0).path("ok").asBoolean());
    assertEquals(0, health.path("checks").get(0).path("data").size());
    assertEquals("1.0", WorkspaceRunnerCapabilities.decode(stored).path("version").asText());
  }

  /**
   * A report larger than what a runner may say of itself does not count against that bound: the
   * runner's next ordinary report still merges.
   */
  @Test
  void aStoredReportDoesNotCountAgainstTheRunnersOwnBound() throws Exception {
    String stored =
        WorkspaceRunnerCapabilities.withHealth(
            null, report(WorkspaceRunnerCapabilities.MAX_CHARS * 2));

    String merged =
        WorkspaceRunnerCapabilities.merge(stored, JSON.readTree("{\"login\":{\"claude\":\"PRESENT\"}}"));

    assertEquals(
        "PRESENT", WorkspaceRunnerCapabilities.decode(merged).path("login").path("claude").asText());
    assertTrue(merged.length() > WorkspaceRunnerCapabilities.MAX_CHARS);
  }
}
