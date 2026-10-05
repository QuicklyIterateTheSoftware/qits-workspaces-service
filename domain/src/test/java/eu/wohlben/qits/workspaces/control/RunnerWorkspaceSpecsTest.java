package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The runner launch spec (qits-851, qits-799): the DIRECT spec's identity, behaviour and home
 * environment, every address off the {@link WorkspaceAddressPlane}, the public image, four logical
 * mounts, the factory labels and the limits — and none of the wire aliases or credentials a DIRECT
 * container carries. Plain JUnit over the golden test's fixture, so the DIRECT spec it is compared
 * with is the pinned one.
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

  /** The plane every case composes from: a public domain other than the fixture factory's own. */
  private static final WorkspaceAddressPlane PLANE =
      WorkspaceAddressPlane.of("wohlben.eu", List.of("registry.dev.localhost:8080"));

  private static RunnerLaunchSpec spec() {
    return specs(WorkspaceContainerFactoryGoldenSpecTest.factory()).compose(row(), PLANE);
  }

  private static RunnerWorkspaceSpecs specs(WorkspaceContainerFactory factory) {
    RunnerWorkspaceSpecs specs = new RunnerWorkspaceSpecs();
    specs.factory = factory;
    return specs;
  }

  @Test
  void composesThePublicImageTheFourMountsTheLabelsAndTheLimits() {
    RunnerLaunchSpec spec = spec();

    assertEquals("registry.qits.wohlben.eu/qits/workspace:2026.1001.120000", spec.image());
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
    RunnerLaunchSpec spec = specs(factory).compose(row(), PLANE);
    Map<String, String> direct =
        factory.forWorkspace("repo12345678abc", "work", 7L, "task/a", "epic/b", "qits-614").env();

    List<String> addresses =
        List.of(
            "QITS_WORKSPACE_DAEMON_URL",
            "QITS_REPOSITORY_MCP_URL",
            "QITS_OBSERVABILITY_MCP_URL",
            "QITS_PLATFORM_MCP_URL",
            "QITS_WORKSPACE_DAEMON_GIT_BASE_URL");
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
            "npm_config_store_dir");
    List<String> expected = new java.util.ArrayList<>();
    expected.add("TZ");
    expected.addAll(addresses);
    expected.addAll(shared.subList(1, shared.size()));
    expected.add("QITS_DOMAIN");
    assertEquals(expected, List.copyOf(spec.env().keySet()), "the runner env is these, in order");
    for (String key : shared) {
      assertEquals(direct.get(key), spec.env().get(key), key);
    }
    // The addresses are the DIRECT spec's keys with the plane's values, and the domain is the
    // plane's — the fixture factory's own (example.eu) is the DIRECT spec's.
    Map<String, String> env = spec.env();
    assertEquals("wss://workspaces.qits.wohlben.eu/workspaces/daemon/7", env.get(addresses.get(0)));
    assertEquals("https://projects.qits.wohlben.eu/projects/mcp", env.get(addresses.get(1)));
    assertEquals(
        "https://observability.qits.wohlben.eu/observability/mcp", env.get(addresses.get(2)));
    assertEquals("https://mcp.qits.wohlben.eu/mcp", spec.env().get(addresses.get(3)));
    assertEquals("https://githost.qits.wohlben.eu/git", spec.env().get(addresses.get(4)));
    assertEquals("wohlben.eu", spec.env().get("QITS_DOMAIN"));
    for (String key : addresses) {
      assertTrue(direct.containsKey(key), key);
    }
  }

  /**
   * qits-799's acceptance: no network and no extra host (the record has no field for either), no
   * wire alias, internal name or local spelling in any value, and the image on the public registry.
   */
  @Test
  void aRunnerSpecNamesOnlyPublicEdgeHosts() {
    RunnerLaunchSpec spec = spec();

    for (Map.Entry<String, String> e : spec.env().entrySet()) {
      assertFalse(e.getValue().contains("-qits-"), e.toString());
      assertFalse(e.getValue().contains(".internal"), e.toString());
      assertFalse(e.getValue().contains(".localhost"), e.toString());
      assertFalse(
          e.getValue().startsWith("http://") || e.getValue().startsWith("ws://"), e.toString());
    }
    assertTrue(spec.image().startsWith("registry.qits.wohlben.eu/"), spec.image());
    assertFalse(
        java.util.Arrays.stream(RunnerLaunchSpec.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .anyMatch(n -> n.equals("network") || n.equals("addHosts")));
  }

  /** The DIRECT spec of the same row still carries its network and its extra host: untouched. */
  @Test
  void theDirectSpecOfTheSameRowKeepsItsNetworkAndExtraHost() {
    WorkspaceContainer direct =
        WorkspaceContainerFactoryGoldenSpecTest.factory()
            .forWorkspace("repo12345678abc", "work", 7L, "task/a", "epic/b", "qits-614");

    assertEquals("qits-net", direct.network());
    assertEquals(List.of("host.docker.internal:host-gateway"), List.copyOf(direct.addHosts()));
    assertEquals(
        "ws://qits:8080/workspaces/daemon/7", direct.env().get("QITS_WORKSPACE_DAEMON_URL"));
  }

  @Test
  void carriesNoCredentialYet() {
    RunnerLaunchSpec spec = spec();

    for (String key : spec.env().keySet()) {
      assertFalse(key.startsWith("QITS_GIT_AUTH_"), key);
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
  void refusesToComposeWithoutAPlane() {
    assertThrows(
        IllegalStateException.class,
        () -> specs(WorkspaceContainerFactoryGoldenSpecTest.factory()).compose(row(), null));
  }
}
