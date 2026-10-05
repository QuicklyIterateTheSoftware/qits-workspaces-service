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
 * The runner launch spec (qits-851, qits-799, qits-802): the DIRECT spec's identity, behaviour,
 * handshake and home environment, every address off the {@link WorkspaceAddressPlane}, the row's
 * workspace token, the public image, four logical mounts, the factory labels and the limits — and
 * none of the wire aliases or the client pair a DIRECT container carries. Plain JUnit over the golden test's fixture, so the DIRECT spec it is compared
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
    row.commissionedTokenId = "tok-id-7";
    row.commissionedTokenSubject = "tok-workspace-7";
    row.commissionedToken = "qits_tok_seven";
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
    List<String> paths =
        List.of("QITS_WORKSPACE_DAEMON_API_BASE_PATH", "QITS_WORKSPACE_DAEMON_SERVICE_PROXY_BASE");
    List<String> identity =
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
            "QITS_WORKSPACE_DAEMON_SERVICE_STOP_GRACE_MS");
    List<String> credential =
        List.of("QITS_TOKEN", "QITS_TOKEN_SUBJECT", "GIT_CONFIG_GLOBAL", "QITS_GIT_AUTH_HOST");
    List<String> home =
        List.of(
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
    expected.addAll(paths);
    expected.addAll(identity.subList(1, identity.size()));
    expected.add("QITS_WORKSPACE_DAEMON_API_TOKEN");
    expected.addAll(credential);
    expected.addAll(home);
    expected.add("QITS_DOMAIN");
    assertEquals(expected, List.copyOf(spec.env().keySet()), "the runner env is these, in order");
    List<String> shared = new java.util.ArrayList<>(identity);
    shared.addAll(paths);
    shared.add("QITS_WORKSPACE_DAEMON_API_TOKEN");
    shared.addAll(home);
    for (String key : shared) {
      assertTrue(direct.containsKey(key), key);
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

  /**
   * qits-802: the credential is the row's token, with the git helper told the plane's githost, and
   * nothing of the DIRECT pair block — no client pair, no token url, no audience.
   */
  @Test
  void carriesTheRowsTokenAndNoneOfTheDirectPairBlock() {
    Map<String, String> env = spec().env();

    assertEquals("qits_tok_seven", env.get("QITS_TOKEN"));
    assertEquals("tok-workspace-7", env.get("QITS_TOKEN_SUBJECT"));
    assertEquals("/etc/qits-gitconfig", env.get("GIT_CONFIG_GLOBAL"));
    assertEquals("githost.qits.wohlben.eu", env.get("QITS_GIT_AUTH_HOST"));
    for (String key :
        List.of(
            "QITS_COMMISSIONED_CLIENT_ID",
            "QITS_COMMISSIONED_CLIENT_SECRET",
            "QITS_GIT_AUTH_TOKEN_URL",
            "QITS_GIT_AUTH_AUDIENCE",
            "QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL",
            "QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE")) {
      assertFalse(env.containsKey(key), key);
    }
  }

  /**
   * The two credential env sets are disjoint as qits-802 lists them: what a DIRECT row with a
   * commissioned pair carries and a RUNNER row never does, and what a RUNNER row carries and a
   * DIRECT row never does. The two keys both write (the helper's config and its host) are the shared
   * mechanism, not a credential.
   */
  @Test
  void theRunnerAndDirectCredentialSetsAreDisjoint() {
    // The golden fixture's DIRECT row holds a commissioned pair.
    WorkspaceContainerFactory factory = WorkspaceContainerFactoryGoldenSpecTest.factory();
    Map<String, String> direct =
        factory.forWorkspace("repo12345678abc", "work", 7L, "task/a", "epic/b", "qits-614").env();
    Map<String, String> runner = specs(factory).compose(row(), PLANE).env();

    List<String> directOnly =
        List.of(
            "QITS_COMMISSIONED_CLIENT_ID",
            "QITS_COMMISSIONED_CLIENT_SECRET",
            "QITS_GIT_AUTH_TOKEN_URL",
            "QITS_GIT_AUTH_AUDIENCE",
            "QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL",
            "QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE");
    List<String> runnerOnly = List.of("QITS_TOKEN", "QITS_TOKEN_SUBJECT");
    for (String key : directOnly) {
      assertTrue(direct.containsKey(key), key);
      assertFalse(runner.containsKey(key), key);
    }
    for (String key : runnerOnly) {
      assertTrue(runner.containsKey(key), key);
      assertFalse(direct.containsKey(key), key);
    }
  }

  /** A row with no token composes no credential at all, never half of one. */
  @Test
  void aRowWithNoTokenCarriesNoCredential() {
    Workspace row = row();
    row.commissionedTokenId = null;
    row.commissionedTokenSubject = null;
    row.commissionedToken = null;
    RunnerLaunchSpec spec =
        specs(WorkspaceContainerFactoryGoldenSpecTest.factory()).compose(row, PLANE);

    for (String key : spec.env().keySet()) {
      assertFalse(key.startsWith("QITS_GIT_AUTH_"), key);
      assertFalse(key.startsWith("QITS_COMMISSIONED_"), key);
      assertFalse(key.startsWith("QITS_WORKSPACE_DAEMON_AUTH_"), key);
      assertFalse(key.startsWith("QITS_TOKEN"), key);
      assertFalse(key.equals("GIT_CONFIG_GLOBAL"), key);
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
