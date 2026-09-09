package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.ConfigProvider;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The always-on cross-cutting config the factory guarantees on every workspace container. Plain
 * JUnit (same package) sets the {@code @ConfigProperty} fields directly, so no docker or Quarkus
 * boot is needed — this is the coverage {@link FakeContainerRuntime} (which models neither the
 * volume nor the labels) cannot give.
 *
 * <p>Read through {@link WorkspaceContainer}'s readers, not through a rendered argv: the socket is
 * gone and the {@code docker run} flags with it, so what is asserted here is the decision (this
 * volume at this path, this env var with this value) rather than the string it used to become.
 */
class WorkspaceContainerFactoryTest {

  /**
   * The shipped default image reference, composed from the two keys the service now ships: {@code
   * qits.workspace.image-repo} (registry host with a port, repository path) and {@code
   * qits.workspace.image-version} (the calver tag). Read from config rather than written down — the
   * version half is a fallback the deployer overrides at runtime (from qits-configuration), so a
   * literal here would go red on a bump that works as intended. The composed reference carries two
   * colons, so anything that ever tried to split it into name and tag would fail on it rather than
   * on a container launch. {@link #composesTheShippedDefaultReference} pins the exact default; the
   * reuse here asserts the factory joins the same halves the config carries.
   */
  private static final String IMAGE_REPO =
      ConfigProvider.getConfig().getValue("qits.workspace.image-repo", String.class);

  private static final String IMAGE_VERSION =
      ConfigProvider.getConfig().getValue("qits.workspace.image-version", String.class);

  private static final String IMAGE = IMAGE_REPO + ":" + IMAGE_VERSION;

  /**
   * The editor pin, read from config for the reason the workspace pin is: the version half is a
   * fallback the deployer overrides from qits-configuration, so a literal here would go red on a
   * bump that works as intended. {@link #composesTheShippedEditorReference} pins the exact default.
   */
  private static final String EDITOR_IMAGE_REPO =
      ConfigProvider.getConfig().getValue("qits.editor.image-repo", String.class);

  private static final String EDITOR_IMAGE_VERSION =
      ConfigProvider.getConfig().getValue("qits.editor.image-version", String.class);

  private static final String EDITOR_IMAGE = EDITOR_IMAGE_REPO + ":" + EDITOR_IMAGE_VERSION;

  /**
   * The Maven Central pull-through the service ships, read from config rather than written down for
   * the reason the image halves are: a deployment may blank it (the off switch) or move it, and a
   * literal here would go red on a change that works as intended. {@link
   * #shipsTheMirrorAsTheCentralDefault} pins the exact shipped value once; every other case reuses
   * this so it asserts the factory injects what config carries.
   */
  private static final String MAVEN_CENTRAL_URL =
      ConfigProvider.getConfig().getValue("qits.workspace.maven-central-url", String.class);

  private WorkspaceContainerFactory factory() {
    WorkspaceContainerFactory f = new WorkspaceContainerFactory();
    f.imageRepo = IMAGE_REPO;
    f.imageVersion = IMAGE_VERSION;
    f.editorImageRepo = EDITOR_IMAGE_REPO;
    f.editorImageVersion = EDITOR_IMAGE_VERSION;
    f.editorPort = 13339;
    f.projectsUrl = "http://qits-projects:8080/";
    f.observabilityUrl = "http://qits-observability:8080/";
    f.network = "qits-net";
    f.claudeVolume = "qits_shared_dot_claude";
    f.claudeMount = "/claude-home";
    f.mavenVolume = "qits_shared_m2";
    f.pnpmVolume = "qits_shared_pnpm";
    // The shipped posture is the three registry keys BLANK — no default address exists to ship —
    // so the default factory here carries none, and the test that wants them sets them itself.
    f.mavenRepositoryUrl = Optional.empty();
    f.npmRegistryUrl = Optional.empty();
    f.npmProxyUrl = Optional.empty();
    // …and the fourth registry key is the one that DOES ship an address, so the default factory
    // carries it: qits-platform-mirror is a platform service with no environment in its name, which
    // is exactly why a default is possible here and was not for the three above.
    f.mavenCentralUrl = Optional.of(MAVEN_CENTRAL_URL);
    f.timezone = Optional.empty();
    // Mirrors the shipped default: a 4g memory cap with an 8g memory+swap total on every
    // container, pids/cpus off.
    f.memoryLimit = Optional.of("4g");
    f.memorySwapLimit = Optional.of("8g");
    f.pidsLimit = Optional.empty();
    f.cpus = Optional.empty();
    f.gitIdentity = identity("qits", "qits@local");
    // An explicit git-host so qitsHost() is deterministic (no WSL/host.docker.internal detection),
    // the same way the devcontainer pins the `qits` alias — this is what workspace-daemon dials
    // home to.
    f.qitsHostResolver = resolver("qits");
    f.qitsPort = "8080";
    f.containerGitUrl = "http://qits-platform-edge:8080";
    f.gitHostAudience = "dev-qits-githost";
    f.idpUrl = "http://qits-idp:8080/idp";
    f.machineAudience = "dev-qits-workspaces";
    // Mirrors the shipped default (qits.bootstrap.autorun-enabled): the daemon self-runs bootstrap.
    f.bootstrapAutorunEnabled = true;
    // Mirrors the shipped qits.services.* defaults, forwarded to the daemon's in-container
    // ServiceSupervisor (Part 4).
    f.servicesAutostartEnabled = true;
    f.serviceReadyGraceMs = 10000;
    f.serviceBackoffInitialMs = 1000;
    f.serviceBackoffMaxMs = 30000;
    f.serviceStopGraceMs = 5000;
    // A live project scope, so the daemon self-clones name-addressed. Stubbed (the real resolver
    // needs a tx + DB); the no-scope fallback has its own test.
    f.nameResolver =
        nameResolver(
            Optional.of(new RepositoryAddressResolver.ProjectScopedName("proj-1", "my-repo")));
    // No repository registry by default — the lookup fallback has its own test.
    f.repositories = StubInstance.empty();
    // No credential lookup by default: the shipped posture is no issuer wired, so a container
    // carries no commissioned pair. The two cases that do have one set this themselves.
    f.credentials = StubInstance.empty();
    // No posture lookup either, which is the "port not installed" answer and must read as no admin
    // workspace exists — the socket is the one thing an absence may never grant.
    f.postures = StubInstance.empty();
    // No agent configuration on the row: a container created before the document existed, which is
    // a container on the harness library's shipped defaults. The cases that have one set it
    // themselves.
    f.agentConfiguration = StubInstance.empty();
    f.agentConfigurationPath = "/tmp/qits/agent-configuration.json";
    return f;
  }

  private static Instance<RepositoryAddressResolver> nameResolver(
      Optional<RepositoryAddressResolver.ProjectScopedName> scopedName) {
    return StubInstance.of(repoId -> scopedName);
  }

  private static GitIdentity identity(String name, String email) {
    GitIdentity identity = new GitIdentity();
    identity.name = name;
    identity.email = email;
    return identity;
  }

  private static QitsHostResolver resolver(String host) {
    QitsHostResolver r = new QitsHostResolver();
    r.configured = host;
    return r;
  }

  @Test
  void alwaysSeedsTheCredentialVolumeLabelsHostUserAndImage() {
    WorkspaceContainer c = factory().forWorkspace("repo12345678abc", "work", 1L, "main", "0parent");

    // The guarantee: the shared credential volume is mounted on every container, the shared build
    // caches beside it at their fixed paths, and nothing else. Asserted as the whole list because
    // the mounts are the one thing a workspace cannot be quietly given an extra of; the
    // per-workspace /workspace volume is absent because persist-workspace is off on this
    // hand-built factory (a plain field, so it is Java's false rather than the shipped default).
    assertEquals(
        List.of(
            new WorkspaceContainer.Mount("qits_shared_dot_claude", "/claude-home"),
            new WorkspaceContainer.Mount("qits_shared_m2", "/caches/m2"),
            new WorkspaceContainer.Mount("qits_shared_pnpm", "/caches/pnpm")),
        c.volumes());
    // ...and every in-container `claude` is pointed at it regardless of HOME, so a login persists
    // across containers even for ad-hoc runs.
    assertEnv(c, "CLAUDE_CONFIG_DIR", "/claude-home/.claude");
    // ...and Kimi Code's data root likewise (KIMI_CODE_HOME relocates config, credentials and
    // sessions onto the same volume).
    assertEnv(c, "KIMI_CODE_HOME", "/claude-home/.kimi-code");
    // The build-cache tools pointed at their mounts, so downloads are reused across builds.
    assertEnv(c, "MAVEN_OPTS", "-Dmaven.repo.local=/caches/m2");
    assertEnv(c, "npm_config_store_dir", "/caches/pnpm/store");
    // The qits.* reconciliation labels.
    assertLabel(c, "qits.repository", "repo12345678abc");
    assertLabel(c, "qits.workspace", "work");
    assertLabel(c, "qits.branch", "main");
    assertLabel(c, "qits.parent", "0parent");
    // Host alias, host uid, deterministic name, image.
    assertEquals(List.of("host.docker.internal:host-gateway"), c.addHosts());
    // The uid is the host's, so the value is whatever this machine's is — what is asserted is that
    // one was resolved and it is a uid, not a copy of the private lookup that produced it.
    assertTrue(c.user().matches("\\d+"), c.user());
    assertEquals("qits-ws-work-repo1234", c.name());
    assertEquals(IMAGE, c.image());
    // workspace-daemon's dial-home coordinates + identity, injected as env
    // (QITS_WORKSPACE_DAEMON_* -> qits.workspace-daemon.*) — workspace-daemon runs in-container so
    // it can't call QitsHostResolver; the URL is composed here.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_URL", "ws://qits:8080/workspaces/daemon/1");
    assertEnv(c, "QITS_REPOSITORY_MCP_URL", "http://qits-projects:8080/projects/mcp");
    assertEnv(
        c,
        "QITS_OBSERVABILITY_MCP_URL",
        "http://qits-observability:8080/observability/mcp");
    // The self-clone base, told rather than left to the daemon's pre-split derivation
    // (/artifacts/git), which 404s now that the git host is qits-githost under /git.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_GIT_BASE_URL", "http://qits-platform-edge:8080/git");
    // Where ContainerProxyRoute addresses this container. Asserted as a literal rather than through
    // ContainerProxyPath.base: the daemon in the other repo matches this string, so a test that
    // computed it the same way the production code does would rename itself along with the bug.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_API_BASE_PATH", "/workspaces/container/1/");
    // The per-workspace half of QITS_PUBLIC_BASE, which the daemon completes with the declared
    // service id (+ web-view base-path) at every spawn. Literal for the same cross-repo reason as
    // above: ServiceProxyRoute's verbatim proxy answers under exactly this prefix.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_SERVICE_PROXY_BASE", "/workspaces/service/1");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_WORKSPACE_ID", "work");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_REPOSITORY_ID", "repo12345678abc");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_BRANCH", "main");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_PARENT", "0parent");
    // The project-scoped name the daemon self-clones under (/git/<projectId>/<repoName>), so
    // committed relative submodule urls resolve natively (docs/epics/qits-workspace-daemon/ Part
    // 1).
    assertEnv(c, "QITS_WORKSPACE_DAEMON_PROJECT_ID", "proj-1");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_REPO_NAME", "my-repo");
    // The bootstrap kill switch the daemon honours when it self-runs the chain on boot (Part 3).
    assertEnv(c, "QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN", "true");
    // Part 4: the service (dev-server) auto-start kill switch the daemon honours as its boot tail.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_SERVICES_AUTOSTART", "true");
    // The shared network, so qits reaches the container's ports by DNS name with no host publish.
    assertEquals("qits-net", c.network());
    // The memory cap — without it a dev server's JVMs size against the whole host's RAM and can
    // OOM the host (docs/issues/resolved/2026-07-21_workspace-container-unbounded-memory-host-oom.md)
    // — and the memory+swap total beside it, docker's --memory-swap, which INCLUDES the cap: 4g/8g
    // is 4G of RAM plus 4G of swap.
    assertEquals("4g", c.memory());
    assertEquals("8g", c.memorySwap());
    // pids/cpus are off by default.
    assertNull(c.pidsLimit());
    assertNull(c.cpus());
    // The blank default timezone inherits qits' own zone, so container wall-clock matches qits'.
    assertEnv(c, "TZ", ZoneId.systemDefault().getId());
    // The commit identity as container-level env, so every git process in the container (qits'
    // verbs, the agent, actions, ad-hoc shells) commits as the configured identity.
    assertEnv(c, "GIT_AUTHOR_NAME", "qits");
    assertEnv(c, "GIT_AUTHOR_EMAIL", "qits@local");
    assertEnv(c, "GIT_COMMITTER_NAME", "qits");
    assertEnv(c, "GIT_COMMITTER_EMAIL", "qits@local");
  }

  @Test
  void aRepoWithoutAProjectScopeInjectsBlankNameEnvSoTheDaemonIdAddresses() {
    WorkspaceContainerFactory f = factory();
    f.nameResolver = nameResolver(Optional.empty());

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    // Blank scope ⇒ the Provisioner clones id-addressed (/git/<repositoryId>), mirroring cloneUrl's
    // fallback. Blank rather than missing: the daemon reads the var either way.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_PROJECT_ID", "");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_REPO_NAME", "");
  }

  @Test
  void withoutANameResolverTheProjectScopedAddressFallsBackToTheRepositoryRegistry() {
    // The deployable has no RepositoryAddressResolver. RepositoryLookup is therefore the production
    // source for both halves of the address relative submodule urls need.
    WorkspaceContainerFactory f = factory();
    f.nameResolver = nameResolver(Optional.empty());
    f.repositories =
        StubInstance.of(
            repoId ->
                Optional.of(
                    new RepositoryLookup.RepositoryView(
                        repoId,
                        "qits-qits",
                        "53c78589-6af3-4221-b3ef-315c867b0863",
                        "main")));

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEnv(c, "QITS_WORKSPACE_DAEMON_PROJECT_ID", "53c78589-6af3-4221-b3ef-315c867b0863");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_REPO_NAME", "qits-qits");
    assertLabel(c, "qits.project", "53c78589-6af3-4221-b3ef-315c867b0863");
  }

  @Test
  void aRegistryThatStumblesCostsTheAddressAndNeverTheContainer() {
    // The registry is an HTTP call to another service, so it can be down while a workspace still
    // has to start. The address is enrichment — the daemon id-addresses without it — and enrichment
    // may never be a provisioning gate. Blank, not absent: the daemon reads the var either way.
    WorkspaceContainerFactory f = factory();
    f.nameResolver = nameResolver(Optional.empty());
    f.repositories =
        StubInstance.of(
            (RepositoryLookup)
                repoId -> {
                  throw new IllegalStateException("qits-projects unreachable");
                });

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEnv(c, "QITS_WORKSPACE_DAEMON_PROJECT_ID", "");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_REPO_NAME", "");
    assertLabel(c, "qits.project", "");
    assertEquals(IMAGE, c.image());
  }

  @Test
  void aRepositoryWithNoRegisteredNameLeavesTheAddressBlankRatherThanHalfOfIt() {
    // A row the registry answers for but cannot name is not addressable by name, and half an
    // address is not one: the daemon would compose /git/<projectId>/ and clone nothing. So both
    // halves go blank together and the daemon id-addresses, which is correct pre-cutover and quiet
    // after it.
    WorkspaceContainerFactory f = factory();
    f.nameResolver = nameResolver(Optional.empty());
    f.repositories =
        StubInstance.of(
            (RepositoryLookup)
                repoId ->
                    Optional.of(
                        new RepositoryLookup.RepositoryView(repoId, null, "proj-9", "main")));

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEquals("", c.env().get("QITS_WORKSPACE_DAEMON_REPO_NAME"));
    assertEquals("proj-9", c.env().get("QITS_WORKSPACE_DAEMON_PROJECT_ID"), "the id still resolves");
  }

  @Test
  void aConfiguredIdentityFlowsIntoTheContainerEnv() {
    WorkspaceContainerFactory f = factory();
    f.gitIdentity = identity("qits-bot", "qits-bot@example.com");

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEnv(c, "GIT_AUTHOR_NAME", "qits-bot");
    assertEnv(c, "GIT_AUTHOR_EMAIL", "qits-bot@example.com");
    assertEnv(c, "GIT_COMMITTER_NAME", "qits-bot");
    assertEnv(c, "GIT_COMMITTER_EMAIL", "qits-bot@example.com");
  }

  @Test
  void anExplicitTimezoneOverridesTheInheritedZone() {
    WorkspaceContainerFactory f = factory();
    f.timezone = Optional.of("Pacific/Auckland");

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEnv(c, "TZ", "Pacific/Auckland");
  }

  @Test
  void aBlankMemoryLimitDisablesTheCap() {
    WorkspaceContainerFactory f = factory();
    f.memoryLimit = Optional.of("  ");

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    // The blank never reaches the container: absent, not "  ", so the spec carries no cap at all.
    assertNull(c.memory());
  }

  @Test
  void aBlankSwapLimitGrantsNoSwap() {
    WorkspaceContainerFactory f = factory();
    f.memorySwapLimit = Optional.of("  ");

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    // The blank never reaches the container, and a null here is what tells the adapter to send the
    // memory cap for both values — no swap, the shape this service always sent.
    assertEquals("4g", c.memory());
    assertNull(c.memorySwap());
  }

  @Test
  void configuredPidsAndCpuLimitsFlowIntoTheContainer() {
    WorkspaceContainerFactory f = factory();
    f.pidsLimit = Optional.of("2048");
    f.cpus = Optional.of("2.5");

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEquals("2048", c.pidsLimit());
    assertEquals("2.5", c.cpus());
  }

  @Test
  void blankingAVolumeOmitsOnlyThatMount() {
    WorkspaceContainerFactory f = factory();
    f.claudeVolume = "";
    f.pnpmVolume = "";

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    // The blanked caches drop their mount — and, for claude/kimi, the credential-dir env too —
    // while the still-configured Maven cache stays.
    assertEquals(
        List.of(new WorkspaceContainer.Mount("qits_shared_m2", "/caches/m2")), c.volumes());
    assertFalse(c.env().containsKey("CLAUDE_CONFIG_DIR"), c.env().toString());
    assertFalse(c.env().containsKey("KIMI_CODE_HOME"), c.env().toString());
    assertEnv(c, "MAVEN_OPTS", "-Dmaven.repo.local=/caches/m2");
    // Everything else still present, incl. an empty parent label for the null parent.
    assertEquals(List.of("host.docker.internal:host-gateway"), c.addHosts());
    assertLabel(c, "qits.repository", "repo12345678abc");
    assertLabel(c, "qits.parent", "");
    assertEquals("qits-ws-work-repo1234", c.name());
  }

  @Test
  void aCommissionedWorkspaceCarriesItsPlatformCredentialAsEnv() {
    WorkspaceContainerFactory f = factory();
    f.credentials =
        StubInstance.of(rowId -> Optional.of(new WorkspaceCredential("ws-1-a", "s3cr3t")));

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    // The pair the workspace authenticates to the platform with — registry pulls and pushes from
    // inside the container, once reads are gated. Two variables, both or neither.
    assertEnv(c, "QITS_COMMISSIONED_CLIENT_ID", "ws-1-a");
    assertEnv(c, "QITS_COMMISSIONED_CLIENT_SECRET", "s3cr3t");
    assertEnv(c, "GIT_CONFIG_GLOBAL", "/etc/qits-gitconfig");
    assertEnv(c, "QITS_GIT_AUTH_HOST", "qits-platform-edge:8080");
    assertEnv(c, "QITS_GIT_AUTH_TOKEN_URL", "http://qits-idp:8080/idp/token");
    assertEnv(c, "QITS_GIT_AUTH_AUDIENCE", "dev-qits-githost");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL", "http://qits-idp:8080/idp/token");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE", "dev-qits-workspaces");
  }

  // --- the agent configuration a container is born with -----------------------------------------

  @Test
  void aWorkspaceWhoseRowHoldsADocumentCarriesItAndThePathToWriteItAt() {
    WorkspaceContainerFactory f = factory();
    String document = "{\"version\":1,\"surfaces\":[{\"surface\":\"epic.chat\"}]}";
    f.agentConfiguration = StubInstance.of(rowId -> Optional.of(document));

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    // The document byte for byte — the container is born with what qits-projects resolved, and
    // nothing re-renders it on the way — plus the path the daemon materializes it at and hands to
    // the shared harness library. Both or neither.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION", document);
    assertEnv(c, "QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION_PATH", "/tmp/qits/agent-configuration.json");
  }

  @Test
  void noDocumentMeansNoAgentConfigurationEnvAtAll() {
    // Every way of not having one, and they must all read the same: no lookup installed, a lookup
    // that answers empty (a container created before this shipped, or one whose fetch failed — the
    // row records the reason and the environment says nothing), a blank column, and a lookup that
    // threw. The last is the one worth asserting: a database blink must cost the document and never
    // the container, and a path naming a file nothing wrote would fail the daemon at boot.
    for (Instance<AgentConfigurationDocuments> lookup :
        List.of(
            StubInstance.<AgentConfigurationDocuments>empty(),
            StubInstance.<AgentConfigurationDocuments>of(rowId -> Optional.empty()),
            StubInstance.<AgentConfigurationDocuments>of(rowId -> Optional.of("  ")),
            StubInstance.<AgentConfigurationDocuments>of(
                rowId -> {
                  throw new IllegalStateException("the database blinked");
                }))) {
      WorkspaceContainerFactory f = factory();
      f.agentConfiguration = lookup;

      WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

      assertFalse(
          c.env().containsKey("QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION"), c.env().toString());
      assertFalse(
          c.env().containsKey("QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION_PATH"),
          c.env().toString());
    }
  }

  @Test
  void noCommissionMeansNoCredentialEnvAtAll() {
    // Both spellings of "not wired" — no lookup installed, and one that answers empty — and the
    // half-answer that must never become half a pair.
    for (Instance<WorkspaceCredentials> lookup :
        List.of(
            StubInstance.<WorkspaceCredentials>empty(),
            StubInstance.<WorkspaceCredentials>of(rowId -> Optional.empty()),
            StubInstance.<WorkspaceCredentials>of(
                rowId -> Optional.of(new WorkspaceCredential("ws-1-a", " "))))) {
      WorkspaceContainerFactory f = factory();
      f.credentials = lookup;

      WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

      assertFalse(c.env().containsKey("QITS_COMMISSIONED_CLIENT_ID"), c.env().toString());
      assertFalse(c.env().containsKey("QITS_COMMISSIONED_CLIENT_SECRET"), c.env().toString());
      assertFalse(c.env().containsKey("QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL"), c.env().toString());
      assertFalse(c.env().containsKey("QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE"), c.env().toString());
    }
  }

  /** Assert the container carries {@code key} with exactly {@code value} in its environment. */
  private static void assertEnv(WorkspaceContainer container, String key, String value) {
    assertTrue(container.env().containsKey(key), () -> "no " + key + " in " + container.env());
    assertEquals(value, container.env().get(key), key);
  }

  /** Assert the container carries {@code key} with exactly {@code value} in its labels. */
  private static void assertLabel(WorkspaceContainer container, String key, String value) {
    assertTrue(container.labels().containsKey(key), () -> "no " + key + " in " + container.labels());
    assertEquals(value, container.labels().get(key), key);
  }

  @Test
  void tellsTheContainerWhereThePlatformRegistriesAreWhenItHasBeenTold() {
    WorkspaceContainerFactory f = factory();
    f.mavenRepositoryUrl = Optional.of("http://dev-qits-artifacts:8080/artifacts/maven/maven");
    f.npmProxyUrl = Optional.of("http://qits-platform-mirror:8080/artifacts/npm/npmjs/");
    f.npmRegistryUrl = Optional.of("http://dev-qits-artifacts:8080/artifacts/npm/npm/");

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    // The names are the CONTRACT and not an implementation detail, which is why they are asserted
    // literally: the two npm keys are npm's own environment form (npm_config_*), which is what
    // outranks the .npmrc every SPA commits, and the Maven key is what the image's profile snippet
    // reads before it adds its -s. Rename any of the three here and a workspace goes back to
    // resolving the public internet, silently, with a green build.
    assertEquals(
        "http://dev-qits-artifacts:8080/artifacts/maven/maven", c.env().get("QITS_MAVEN_REPOSITORY_URL"));
    assertEquals(
        "http://qits-platform-mirror:8080/artifacts/npm/npmjs/", c.env().get("npm_config_registry"));
    assertEquals(
        "http://dev-qits-artifacts:8080/artifacts/npm/npm/",
        c.env().get("QITS_WORKSPACE_NPM_REGISTRY_URL"));
    // The name qits-containers would REFUSE. Asserted absent because the refusal is a 400 that
    // fails the whole container launch, not a dropped variable — a workspace simply never starts.
    assertNull(c.env().get("npm_config_@qits:registry"));
  }

  @Test
  void neverSpellsAnEnvironmentKeyTheContainerServiceWouldRefuse() {
    // Every key on a container spec must be POSIX-shaped: qits-containers validates them and
    // answers 400 INVALID, which surfaces as a workspace stuck in FAILED with no container at all.
    WorkspaceContainerFactory f = factory();
    f.mavenRepositoryUrl = Optional.of("http://a/maven");
    f.npmProxyUrl = Optional.of("http://b/npmjs/");
    f.npmRegistryUrl = Optional.of("http://c/npm/");

    for (String key : f.forWorkspace("repo12345678abc", "work", 1L, "main", null).env().keySet()) {
      assertTrue(
          key.matches("[A-Za-z_][A-Za-z0-9_]*"),
          "environment key is not POSIX-shaped and would be refused: " + key);
    }
  }

  @Test
  void tellsTheContainerNothingAboutRegistriesItWasNotToldAbout() {
    // Absent is a supported configuration, not a misconfiguration: a deployment that wires none of
    // the three gets a container identical to the one it got before these keys existed. Asserted
    // because the alternative — injecting a derived or defaulted address — would point builds at a
    // host that does not exist on that deployment, which is worse than leaving them as they were.
    WorkspaceContainer c = factory().forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertNull(c.env().get("QITS_MAVEN_REPOSITORY_URL"));
    assertNull(c.env().get("npm_config_registry"));
    assertNull(c.env().get("npm_config_@qits:registry"));
  }

  @Test
  void routesMavenCentralThroughTheMirrorByDefault() {
    // The name is the CONTRACT, asserted literally for the reason the three above are: the workspace
    // image's /etc/qits/maven-settings.xml activates its central-proxy profile on the PRESENCE of a
    // non-empty QITS_MAVEN_CENTRAL_URL and mirrors the qits-central repository to its value. Rename
    // it and every workspace build silently goes back out to repo1.maven.org, green.
    WorkspaceContainer c = factory().forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEnv(c, "QITS_MAVEN_CENTRAL_URL", MAVEN_CENTRAL_URL);
  }

  @Test
  void shipsTheMirrorAsTheCentralDefault() {
    // The one place the shipped address is written down, and it is a STEP-PLANE address: a workspace
    // container sits on qits-net, where the mirror answers under its own service alias on its own
    // /mirror route. A published host name (mirror.<env>.<domain>) would resolve to nothing in
    // there, and the /artifacts route belongs to the hosted registry, which does not proxy Central.
    assertEquals("http://qits-platform-mirror:8080/mirror/maven/central", MAVEN_CENTRAL_URL);
  }

  @Test
  void tellsTheContainerNothingAboutCentralWhenTheKeyIsBlanked() {
    // Blanking the key is the OFF SWITCH and the only one. Nothing injected ⇒ the image's profile
    // never activates (it is a property-presence activation, and an empty environment value does not
    // activate one — measured on Maven 3.9) ⇒ the build resolves Maven Central directly, exactly as
    // it did before this key existed. Asserted because the alternative — injecting an empty string —
    // looks identical in a deployment's env and is NOT the same thing to a settings file.
    WorkspaceContainerFactory f = factory();
    f.mavenCentralUrl = Optional.of("");

    assertNull(
        f.forWorkspace("repo12345678abc", "work", 1L, "main", null)
            .env()
            .get("QITS_MAVEN_CENTRAL_URL"));
  }

  @Test
  void answersTheSameCentralAddressOnEveryEnsure() {
    // The value rides the SPEC, and a spec that differs from the running container's is a
    // Recreate.ifChanged REPLACEMENT — so an address derived per call would turn every resume into a
    // destroyed container. Two calls for two workspaces on one factory must carry the identical
    // string, which is what "a constant off config" means here.
    WorkspaceContainerFactory f = factory();

    WorkspaceContainer first = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);
    WorkspaceContainer second = f.forWorkspace("repo12345678abc", "other", 2L, "feature", null);

    assertEquals(
        first.env().get("QITS_MAVEN_CENTRAL_URL"), second.env().get("QITS_MAVEN_CENTRAL_URL"));
  }

  // --- the admin posture ------------------------------------------------------------------------

  @Test
  void bindsTheDockerSocketForAWorkspaceWhoseRowSaysAdmin() {
    WorkspaceContainerFactory f = factory();
    f.postures = StubInstance.of((WorkspacePostures) rowId -> true);

    assertTrue(f.forWorkspace("repo12345678abc", "work", 7L, "main", null).hostDockerSocket());
  }

  @Test
  void bindsNothingForAnOrdinaryWorkspace() {
    // The claim that matters. The socket is root-equivalent on the host, so the default has to be
    // "no", and it has to be "no" for the workspace that simply did not ask rather than only for
    // the one that asked not to.
    WorkspaceContainerFactory f = factory();
    f.postures = StubInstance.of((WorkspacePostures) rowId -> false);

    assertFalse(f.forWorkspace("repo12345678abc", "work", 7L, "main", null).hostDockerSocket());
  }

  @Test
  void aPostureLookupThatFailsGrantsNothing() {
    // Both absences, and they must fall the same way: no port installed at all, and a port that
    // threw. A credential lookup that stumbles costs the container something it was meant to have;
    // a posture lookup that stumbles must not GIVE it something it was not. That asymmetry is the
    // reason this is a test rather than a comment.
    WorkspaceContainerFactory absent = factory();
    assertFalse(absent.forWorkspace("repo12345678abc", "work", 7L, "main", null).hostDockerSocket());

    WorkspaceContainerFactory broken = factory();
    broken.postures =
        StubInstance.of(
            (WorkspacePostures)
                rowId -> {
                  throw new IllegalStateException("the database blinked");
                });
    assertFalse(broken.forWorkspace("repo12345678abc", "work", 7L, "main", null).hostDockerSocket());
  }

  /**
   * The two keys compose to the fully qualified default the deployment starts with. This pins the
   * shipped {@code META-INF/microprofile-config.properties} default exactly, because the version
   * half no longer moves in this file — the deployer overrides it from qits-configuration — so the
   * default is now stable.
   */
  @Test
  void composesTheShippedDefaultReference() {
    assertEquals(
        "registry.dev.localhost:8080/qits/workspace:2026.820.155203",
        factory().image(),
        "repo and version joined as <repo>:<version>, fully qualified");
  }

  /**
   * The deployer injects {@code QITS_WORKSPACE_IMAGE_VERSION}, which SmallRye maps onto {@code
   * qits.workspace.image-version}, and the injected value wins over the default. A hand-built factory
   * stands in for that injection so the override is exercised without booting the app: the factory
   * composes whatever version it is handed against the committed repo, staying fully qualified.
   */
  @Test
  void theInjectedVersionWinsOverTheDefault() {
    WorkspaceContainerFactory overridden = new WorkspaceContainerFactory();
    overridden.imageRepo = "registry.dev.localhost:8080/qits/workspace";
    overridden.imageVersion = "2026.999.000000";

    assertEquals(
        "registry.dev.localhost:8080/qits/workspace:2026.999.000000", overridden.image());
  }

  // --- the editor posture -----------------------------------------------------------------------

  /** A posture port answering "the project wrapper's main workspace" and nothing else. */
  private static WorkspacePostures wrapperMain(boolean answer) {
    return new WorkspacePostures() {
      @Override
      public boolean isAdmin(Long rowId) {
        return false;
      }

      @Override
      public boolean isWrapperMain(Long rowId) {
        return answer;
      }
    };
  }

  @Test
  void theWrapperMainWorkspaceRunsTheEditorImageAndIsToldSo() {
    WorkspaceContainerFactory f = factory();
    f.postures = StubInstance.of(wrapperMain(true));

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "main", 7L, "main", null);

    assertEquals(EDITOR_IMAGE, c.image());
    assertTrue(c.editor(), "the description carries the decision, so the adapter reads it once");
    // Both vars or neither: `enabled` alone would leave the daemon and the host's proxy free to
    // pick different ports, and a port alone would name a listener nothing starts.
    assertEquals("true", c.env().get("QITS_WORKSPACE_DAEMON_EDITOR_ENABLED"));
    assertEquals("13339", c.env().get("QITS_WORKSPACE_DAEMON_EDITOR_PORT"));
  }

  @Test
  void anOrdinaryWorkspaceIsUntouchedByTheEditor() {
    // The claim that matters for every workspace that is not the wrapper's main one: the plain
    // image, and NOTHING said about an editor. Silence is what the daemon's own default reads as
    // "no editor", so an explicitly-false pair here would be a second way of saying the same thing.
    WorkspaceContainerFactory f = factory();
    f.postures = StubInstance.of(wrapperMain(false));

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 7L, "feature/x", "main");

    assertEquals(IMAGE, c.image());
    assertFalse(c.editor());
    assertNull(c.env().get("QITS_WORKSPACE_DAEMON_EDITOR_ENABLED"));
    assertNull(c.env().get("QITS_WORKSPACE_DAEMON_EDITOR_PORT"));
  }

  @Test
  void thePostureIsReproducibleAcrossEnsures() {
    // THE SPEC-HASH RULE, from the outside. The orchestrator has no start verb: a stopped container
    // is resumed by presenting its spec AGAIN under Recreate.ifChanged, so a spec that differs is a
    // REPLACEMENT. Two calls with the same arguments must therefore describe the same container —
    // the image, the editor environment and the flag alike — which is what makes the posture a
    // lookup rather than a parameter somebody could forget to pass on the resume path.
    WorkspaceContainerFactory f = factory();
    f.postures = StubInstance.of(wrapperMain(true));

    WorkspaceContainer first = f.forWorkspace("repo12345678abc", "main", 7L, "main", null);
    WorkspaceContainer second = f.forWorkspace("repo12345678abc", "main", 7L, "main", null);

    assertEquals(first.image(), second.image());
    assertEquals(first.env(), second.env());
    assertEquals(first.editor(), second.editor());
  }

  @Test
  void aPostureLookupThatFailsLeavesTheWorkspacePlain() {
    // Both absences again, and both fall to the ordinary workspace. Absent means no editor exists,
    // which is what every workspace was before one did; a read that threw must not be the thing
    // that decides a container runs a different image.
    WorkspaceContainerFactory absent = factory();
    WorkspaceContainer plain = absent.forWorkspace("repo12345678abc", "main", 7L, "main", null);
    assertEquals(IMAGE, plain.image());
    assertFalse(plain.editor());

    WorkspaceContainerFactory broken = factory();
    broken.postures =
        StubInstance.of(
            new WorkspacePostures() {
              @Override
              public boolean isAdmin(Long rowId) {
                return false;
              }

              @Override
              public boolean isWrapperMain(Long rowId) {
                throw new IllegalStateException("the registry blinked");
              }
            });
    WorkspaceContainer degraded = broken.forWorkspace("repo12345678abc", "main", 7L, "main", null);
    assertEquals(IMAGE, degraded.image());
    assertNull(degraded.env().get("QITS_WORKSPACE_DAEMON_EDITOR_ENABLED"));
  }

  /**
   * The editor's two keys compose the same way the workspace pin's do, and this pins the shipped
   * default exactly. The version names no released tag yet — qits-workspace-editor-oci has never
   * been released — so what is asserted is the placeholder the properties file documents, and the
   * first real release moves both this literal and that comment together.
   */
  @Test
  void composesTheShippedEditorReference() {
    assertEquals(
        "registry.dev.localhost:8080/qits/workspace-editor:2026.823.71954",
        factory().editorImage(),
        "repo and version joined as <repo>:<version>, fully qualified");
  }
}
