package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The runner launch spec (qits-851): the DIRECT spec's identity, behaviour and home environment,
 * the public image, four logical mounts, the factory labels and the limits — and none of the
 * addresses or credentials a DIRECT container carries. Plain JUnit over the golden test's fixture,
 * so the DIRECT spec it is compared with is the pinned one.
 */
class RunnerWorkspaceSpecsTest {

  private static Workspace row() {
    Workspace row = new Workspace();
    row.id = 7L;
    row.repositoryId = "repo12345678abc";
    row.workspaceId = "work";
    row.branch = "task/a";
    row.parent = "epic/b";
    row.entityId = "qits-614";
    row.placement = WorkspacePlacement.RUNNER;
    return row;
  }

  private static RunnerWorkspaceSpecs specs(WorkspaceContainerFactory factory) {
    RunnerWorkspaceSpecs specs = new RunnerWorkspaceSpecs();
    specs.factory = factory;
    return specs;
  }

  @Test
  void composesThePublicImageTheFourMountsTheLabelsAndTheLimits() {
    RunnerLaunchSpec spec = specs(WorkspaceContainerFactoryGoldenSpecTest.factory()).compose(row());

    assertEquals("registry.qits.example.eu/qits/workspace:2026.1001.120000", spec.image());
    assertEquals(
        List.of(
            new RunnerLaunchSpec.Mount(RunnerLaunchSpec.Volume.WORKSPACE, "/workspace"),
            new RunnerLaunchSpec.Mount(RunnerLaunchSpec.Volume.DOT_CLAUDE, "/claude-home"),
            new RunnerLaunchSpec.Mount(RunnerLaunchSpec.Volume.M2, "/caches/m2"),
            new RunnerLaunchSpec.Mount(RunnerLaunchSpec.Volume.PNPM, "/caches/pnpm")),
        spec.mounts());
    assertEquals(
        List.of("qits.repository", "qits.workspace", "qits.branch", "qits.parent", "qits.project"),
        List.copyOf(spec.labels().keySet()));
    assertEquals("proj-1", spec.labels().get("qits.project"));
    assertTrue(
        spec.labels().keySet().stream().noneMatch(k -> k.startsWith("qits.workspaces.runner.")));
    assertEquals(new RunnerLaunchSpec.Limits("4g", "8g", "4096", "2", 600), spec.limits());
    assertTrue(spec.init());
  }

  @Test
  void carriesExactlyTheDirectSpecsIdentityBehaviourAndHomeEnvironment() {
    WorkspaceContainerFactory factory = WorkspaceContainerFactoryGoldenSpecTest.factory();
    RunnerLaunchSpec spec = specs(factory).compose(row());
    Map<String, String> direct =
        factory.forWorkspace("repo12345678abc", "work", 7L, "task/a", "epic/b", "qits-614").env();

    List<String> shared =
        List.of(
            "TZ",
            "QITS_WORKSPACE_DAEMON_WORKSPACE_ID",
            "QITS_WORKSPACE_DAEMON_REPOSITORY_ID",
            "QITS_WORKSPACE_DAEMON_BRANCH",
            "QITS_WORKSPACE_DAEMON_PARENT",
            "QITS_WORKSPACE_DAEMON_ENTITY_ID",
            "QITS_WORKSPACE_DAEMON_ENTITY_TITLE",
            "QITS_WORKSPACE_DAEMON_ENTITY_STATUS",
            "QITS_WORKSPACE_DAEMON_ENTITY_BLOCKED",
            "QITS_WORKSPACE_DAEMON_PROJECT_ID",
            "QITS_WORKSPACE_DAEMON_REPO_NAME",
            "QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN",
            "QITS_WORKSPACE_DAEMON_AUTO_PUSH_ENABLED",
            "QITS_WORKSPACE_DAEMON_SERVICES_AUTOSTART",
            "QITS_WORKSPACE_DAEMON_SERVICE_READY_GRACE_MS",
            "QITS_WORKSPACE_DAEMON_SERVICE_RESTART_BACKOFF_INITIAL_MS",
            "QITS_WORKSPACE_DAEMON_SERVICE_RESTART_BACKOFF_MAX_MS",
            "QITS_WORKSPACE_DAEMON_SERVICE_STOP_GRACE_MS",
            "GIT_AUTHOR_NAME",
            "GIT_AUTHOR_EMAIL",
            "GIT_COMMITTER_NAME",
            "GIT_COMMITTER_EMAIL",
            "CLAUDE_CONFIG_DIR",
            "KIMI_CODE_HOME",
            "MAVEN_OPTS",
            "npm_config_store_dir",
            "QITS_DOMAIN");
    assertEquals(shared, List.copyOf(spec.env().keySet()), "the runner env is these, in order");
    for (String key : shared) {
      assertEquals(direct.get(key), spec.env().get(key), key);
    }
  }

  @Test
  void carriesNoAddressAndNoCredential() {
    RunnerLaunchSpec spec = specs(WorkspaceContainerFactoryGoldenSpecTest.factory()).compose(row());

    for (String key : spec.env().keySet()) {
      assertFalse(key.equals("QITS_WORKSPACE_DAEMON_URL"), key);
      assertFalse(key.equals("QITS_WORKSPACE_DAEMON_GIT_BASE_URL"), key);
      assertFalse(key.startsWith("QITS_GIT_AUTH_"), key);
      assertFalse(key.endsWith("_MCP_URL"), key);
      assertFalse(key.startsWith("QITS_COMMISSIONED_"), key);
      assertFalse(key.startsWith("QITS_WORKSPACE_DAEMON_AUTH_"), key);
      assertFalse(key.equals("GIT_CONFIG_GLOBAL"), key);
      assertFalse(key.equals("QITS_WORKSPACE_DAEMON_API_TOKEN"), key);
    }
    // The record has no field for a network, an extra host, the docker socket or a user at all;
    // what it does carry is asserted whole above. The component list says so.
    assertEquals(
        List.of("image", "env", "mounts", "labels", "limits", "init"),
        java.util.Arrays.stream(RunnerLaunchSpec.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList());
  }

  @Test
  void refusesToComposeWithoutAPublicDomain() {
    WorkspaceContainerFactory factory = WorkspaceContainerFactoryGoldenSpecTest.factory();
    factory.domain = Optional.empty();

    assertThrows(IllegalStateException.class, () -> specs(factory).compose(row()));
  }
}
