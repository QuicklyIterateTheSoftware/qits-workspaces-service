package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.workspaces.control.BootstrapRunService;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.entity.BootstrapOutcome;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The reader {@code workspace_bootstrap_run} spent a release without. The writer was already
 * exercised by the daemon bootstrap tests; what these cases prove is that what it wrote is now
 * askable for, and in the join shape the Actions panel needs.
 */
@QuarkusTest
public class WorkspaceBootstrapRunControllerTest {

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @Inject WorkspaceService workspaceService;

  @Inject BootstrapRunService bootstrapRuns;

  private record Seeded(String repoId, String label, Long id) {}

  private Seeded workspace(String label) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createWorkspace(repoId, label, "master", label);
    return new Seeded(repoId, label, workspaceIds.of(repoId, label));
  }

  private static String base(Long id) {
    return "/workspaces/api/workspaces/" + id + "/bootstrap-runs";
  }

  @Test
  public void aWorkspaceWhoseChainHasNeverRunAnswersAnEmptyList() throws Exception {
    Seeded seeded = workspace("bootstrap-fresh");

    // Not a 404: a freshly created workspace has no rows yet, and the panel renders that state.
    given().get(base(seeded.id())).then().statusCode(200).body("runs", hasSize(0));
  }

  @Test
  public void eachStepsLastRunIsReadableWithItsCommandAnchor() throws Exception {
    Seeded seeded = workspace("bootstrap-runs");

    bootstrapRuns.recordOutcome(
        seeded.repoId(),
        seeded.label(),
        seeded.id(),
        "install",
        "npm install",
        BootstrapOutcome.SUCCEEDED,
        "cmd-install-1",
        0);
    // SKIPPED spawns no command, so its anchor is null — the client must render that as "the check
    // said it was not needed" rather than as a missing log.
    bootstrapRuns.recordOutcome(
        seeded.repoId(),
        seeded.label(),
        seeded.id(),
        "migrate",
        "flyway migrate",
        BootstrapOutcome.SKIPPED,
        null,
        null);

    given()
        .get(base(seeded.id()))
        .then()
        .statusCode(200)
        .body("runs", hasSize(2))
        // bootstrapCommandId, not the display name: this is the key the daemon's declared
        // GET /bootstrap-commands is joined on, and two steps may well share a command name.
        .body("runs.find { it.bootstrapCommandId == 'install' }.commandName", equalTo("npm install"))
        .body("runs.find { it.bootstrapCommandId == 'install' }.outcome", equalTo("SUCCEEDED"))
        .body("runs.find { it.bootstrapCommandId == 'install' }.commandId", equalTo("cmd-install-1"))
        .body("runs.find { it.bootstrapCommandId == 'install' }.exitCode", equalTo(0))
        .body("runs.find { it.bootstrapCommandId == 'migrate' }.outcome", equalTo("SKIPPED"))
        .body("runs.find { it.bootstrapCommandId == 'migrate' }.commandId", nullValue());
  }

  @Test
  public void aRerunOverwritesTheStepRatherThanAppending() throws Exception {
    Seeded seeded = workspace("bootstrap-rerun");

    bootstrapRuns.recordOutcome(
        seeded.repoId(), seeded.label(), seeded.id(), "install", "npm install",
        BootstrapOutcome.FAILED, "cmd-1", 1);
    bootstrapRuns.recordOutcome(
        seeded.repoId(), seeded.label(), seeded.id(), "install", "npm install",
        BootstrapOutcome.SUCCEEDED, "cmd-2", 0);

    // One row per (workspace, command), so this is a last-run view and never a log — which is what
    // the panel shows beside each declared step.
    given()
        .get(base(seeded.id()))
        .then()
        .statusCode(200)
        .body("runs", hasSize(1))
        .body("runs[0].outcome", equalTo("SUCCEEDED"))
        .body("runs[0].commandId", equalTo("cmd-2"));
  }

  @Test
  public void anUnknownWorkspaceIs404() {
    given().get(base(999_999L)).then().statusCode(404);
  }
}
