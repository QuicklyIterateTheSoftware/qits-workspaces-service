package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceImage;
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
   * The registry host and repository path — the half that IS still config, and the only half.
   * {@code qits.workspace.image-repo} is a committed property because where the registry is, is a
   * deployment's business; the version half is the pinned dependency's (see {@link #IMAGE_VERSION}).
   *
   * <p>Read from config rather than written down so this constant asserts that the factory joins the
   * halves the config really carries. The composed reference carries two colons, so anything that
   * ever tried to split it into name and tag would fail on it rather than on a container launch.
   */
  private static final String IMAGE_REPO =
      ConfigProvider.getConfig().getValue("qits.workspace.image-repo", String.class);

  /**
   * <b>The pin, not a config value.</b> {@code qits.workspace.image-version} ships unset now — it is
   * an operator's emergency override and nothing else — and the version a launch really uses is the
   * version of {@code eu.wohlben.qits:qits-workspace-daemon-protocol}, the jar this reactor pins and
   * whose release also pushed the image. Reading the constant is reading what a deployment reads;
   * reading the (absent) config key would be asserting against the override path on every case.
   */
  private static final String IMAGE_VERSION = WorkspaceImage.VERSION;

  private static final String IMAGE = IMAGE_REPO + ":" + IMAGE_VERSION;


  private WorkspaceContainerFactory factory() {
    WorkspaceContainerFactory f = new WorkspaceContainerFactory();
    f.imageRepo = IMAGE_REPO;
    // Empty, which is a deployment's normal state: the version comes off the pinned dependency and
    // the config key is only an override. A fixture that set it would test the override on every
    // case and never the shipped path.
    f.imageVersionOverride = Optional.empty();
    f.platformMcpUrl = "http://dev-qits-platform-access-mcp-service:8080/";
    f.network = "qits-net";
    f.claudeVolume = "qits_shared_dot_claude";
    f.claudeMount = "/claude-home";
    f.mavenVolume = "qits_shared_m2";
    f.pnpmVolume = "qits_shared_pnpm";
    // No public domain, which is what the suites pin (%test.qits.workspace.domain is empty): the
    // cases that want QITS_DOMAIN handed on set a domain themselves.
    f.domain = Optional.empty();
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
    f.idpUrl = "http://qits-idp:8080/idp";
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
    // The one central MCP server (epic qits-630), the qits CLI served over HTTP.
    assertEnv(
        c,
        "QITS_PLATFORM_MCP_URL",
        "http://dev-qits-platform-access-mcp-service:8080/mcp");
    // The self-clone base, told rather than left to the daemon's pre-split derivation
    // (/artifacts/git), which 404s now that the git host is qits-githost under /git.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_GIT_BASE_URL", "http://qits-platform-edge:8080/git");
    // Where ContainerProxyRoute addresses this container. Asserted as a literal rather than through
    // ContainerProxyPath.base: the daemon in the other repo matches this string, so a test that
    // computed it the same way the production code does would rename itself along with the bug.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_API_BASE_PATH", "/workspaces/container/1/");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_WORKSPACE_ID", "work");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_REPOSITORY_ID", "repo12345678abc");
    // The project-scoped name the daemon self-clones under (/git/<projectId>/<repoName>), so
    // committed relative submodule urls resolve natively (docs/epics/qits-workspace-daemon/ Part
    // 1).
    assertEnv(c, "QITS_WORKSPACE_DAEMON_PROJECT_ID", "proj-1");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_REPO_NAME", "my-repo");
    // Workspace services are gone (qits-947), and the image's daemon has no switch left to set.
    assertFalse(
        c.env().keySet().stream().anyMatch(k -> k.startsWith("QITS_WORKSPACE_DAEMON_SERVICE")),
        "no service env reaches the container");
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
  void aConfiguredPlatformMcpUrlOverridesTheDefault() {
    WorkspaceContainerFactory f = factory();
    f.platformMcpUrl = "http://qits-platform-access-mcp-service.example:9090";

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEnv(
        c,
        "QITS_PLATFORM_MCP_URL",
        "http://qits-platform-access-mcp-service.example:9090/mcp");
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
    // One platform-wide audience for both (epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4) — no longer
    // qits-githost-specific or environment-qualified.
    assertEnv(c, "QITS_GIT_AUTH_AUDIENCE", "qits-platform");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL", "http://qits-idp:8080/idp/token");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE", "qits-platform");
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

  /**
   * A row holding a workspace token (qits-1084) is addressed through the WIRED address planes — the
   * bean the commission decided on — rather than this factory's own domain key, and carries the
   * token and none of the pair block, even when a pair lookup would also answer.
   */
  @Test
  void aTokenHoldingRowTakesTheWiredPlaneAndCarriesNoPair() {
    WorkspaceContainerFactory f = factory();
    f.addressPlanes =
        StubInstance.of(
            new WorkspaceAddressPlanes() {
              @Override
              public WorkspaceAddressPlane plane() {
                return WorkspaceAddressPlane.of("wired.example", List.of());
              }
            });
    f.credentials =
        StubInstance.of(
            new WorkspaceCredentials() {
              @Override
              public Optional<WorkspaceCredential> forWorkspace(Long rowId) {
                return Optional.of(new WorkspaceCredential("ws-1-a", "s3cr3t"));
              }

              @Override
              public Optional<WorkspaceToken> tokenFor(Long rowId) {
                return Optional.of(new WorkspaceToken("tok-id-1", "qits_tok_1", "tok-workspace-1"));
              }
            });

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEnv(c, "QITS_WORKSPACE_DAEMON_URL", "wss://workspaces.qits.wired.example/workspaces/daemon/1");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_GIT_BASE_URL", "https://githost.qits.wired.example/git");
    assertEnv(c, "QITS_TOKEN", "qits_tok_1");
    assertEnv(c, "QITS_TOKEN_SUBJECT", "tok-workspace-1");
    assertEnv(c, "QITS_GIT_AUTH_HOST", "githost.qits.wired.example");
    for (String pairKey :
        List.of(
            "QITS_COMMISSIONED_CLIENT_ID",
            "QITS_COMMISSIONED_CLIENT_SECRET",
            "QITS_GIT_AUTH_TOKEN_URL",
            "QITS_GIT_AUTH_AUDIENCE",
            "QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL",
            "QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE")) {
      assertFalse(c.env().containsKey(pairKey), pairKey + " in " + c.env());
    }
    // Everything DIRECT stays: the network and the extra host.
    assertEquals("qits-net", c.network());
    assertEquals(List.of("host.docker.internal:host-gateway"), c.addHosts());
  }

  /** A token with a blank value or subject is no token: the pair's spec, unchanged. */
  @Test
  void aBlankTokenIsNoTokenAndThePairStands() {
    WorkspaceContainerFactory f = factory();
    f.credentials =
        StubInstance.of(
            new WorkspaceCredentials() {
              @Override
              public Optional<WorkspaceCredential> forWorkspace(Long rowId) {
                return Optional.of(new WorkspaceCredential("ws-1-a", "s3cr3t"));
              }

              @Override
              public Optional<WorkspaceToken> tokenFor(Long rowId) {
                return Optional.of(new WorkspaceToken("tok-id-1", " ", "tok-workspace-1"));
              }
            });

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertFalse(c.env().containsKey("QITS_TOKEN"), c.env().toString());
    assertEnv(c, "QITS_COMMISSIONED_CLIENT_ID", "ws-1-a");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_URL", "ws://qits:8080/workspaces/daemon/1");
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
  void handsTheContainerThePublicDomainAndNoRegistryUrl() {
    // QITS_DOMAIN is the ONLY registry input: the workspace image derives
    // https://registry.qits.<domain> and https://mirror.qits.<domain> from it in code. The name is
    // the CONTRACT, asserted literally — the image's npm shim and Maven settings read it, and a
    // rename sends every workspace build to the fallback domain silently.
    WorkspaceContainerFactory f = factory();
    f.domain = Optional.of("wohlben.eu");

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEnv(c, "QITS_DOMAIN", "wohlben.eu");
    assertNoRegistryUrl(c);
  }

  @Test
  void handsOnTheDomainTrimmed() {
    WorkspaceContainerFactory f = factory();
    f.domain = Optional.of("  wohlben.eu ");

    assertEnv(
        f.forWorkspace("repo12345678abc", "work", 1L, "main", null), "QITS_DOMAIN", "wohlben.eu");
  }

  @Test
  void injectsNoDomainWhenNoneIsSet() {
    // Unset or blank ⇒ nothing, and the image falls back to the platform's own domain. Never an
    // empty value: that is not the same thing as absent to a shell's ${QITS_DOMAIN:-…}.
    for (Optional<String> domain : List.of(Optional.<String>empty(), Optional.of(" "))) {
      WorkspaceContainerFactory f = factory();
      f.domain = domain;

      WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

      assertFalse(c.env().containsKey("QITS_DOMAIN"), "domain " + domain);
      assertNoRegistryUrl(c);
    }
  }

  /**
   * No registry address of any spelling rides the spec: every one of them used to be an internal
   * URL ({@code dev-qits-artifacts:8080}) that ended up committed in lockfiles.
   */
  private static void assertNoRegistryUrl(WorkspaceContainer c) {
    for (String key : c.env().keySet()) {
      assertFalse(
          key.endsWith("_URL") && (key.contains("MAVEN") || key.contains("NPM")),
          "registry URL variable injected: " + key);
    }
    assertNull(c.env().get("npm_config_registry"));
    assertNull(c.env().get("QITS_MAVEN_REPOSITORY_URL"));
    assertNull(c.env().get("QITS_MAVEN_CENTRAL_URL"));
    assertNull(c.env().get("QITS_WORKSPACE_NPM_REGISTRY_URL"));
  }

  @Test
  void neverSpellsAnEnvironmentKeyTheContainerServiceWouldRefuse() {
    // Every key on a container spec must be POSIX-shaped: qits-containers validates them and
    // answers 400 INVALID, which surfaces as a workspace stuck in FAILED with no container at all.
    WorkspaceContainerFactory f = factory();
    f.domain = Optional.of("wohlben.eu");

    for (String key : f.forWorkspace("repo12345678abc", "work", 1L, "main", null).env().keySet()) {
      assertTrue(
          key.matches("[A-Za-z_][A-Za-z0-9_]*"),
          "environment key is not POSIX-shaped and would be refused: " + key);
    }
  }

  @Test
  void answersTheSameDomainOnEveryEnsure() {
    // The value rides the SPEC, and a spec that differs from the running container's is a
    // Recreate.ifChanged REPLACEMENT — so a value derived per call would turn every resume into a
    // destroyed container. Two calls for two workspaces on one factory must carry the identical
    // string, which is what "a constant off config" means here.
    WorkspaceContainerFactory f = factory();
    f.domain = Optional.of("wohlben.eu");

    WorkspaceContainer first = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);
    WorkspaceContainer second = f.forWorkspace("repo12345678abc", "other", 2L, "feature", null);

    assertEquals(first.env().get("QITS_DOMAIN"), second.env().get("QITS_DOMAIN"));
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
   * The committed repo and the PINNED version compose to a fully qualified reference.
   *
   * <p>It used to assert a literal, because the version was a shipped config default. That is the
   * line this whole change deletes: the default aged until it named {@code 2026.820.155203}, an
   * image the registry's retention had removed, and nothing here could tell. The version half is now
   * the pinned dependency's, so a literal would have to be edited by every bump of it — which is
   * exactly the edit a train makes and a person forgets. What is still worth pinning is the SHAPE:
   * the registry host survives (it carries its own {@code host:port}, so the reference has two
   * colons and cannot be split naively), and the two halves are joined with one colon between them.
   */
  @Test
  void composesTheShippedDefaultReference() {
    // registry.qits.${QITS_DOMAIN}/qits/workspace, QITS_DOMAIN expanded from this module's test
    // application.properties (qits-1402: the registry moved behind the public edge) — not a bare
    // literal any more, so this also proves the expansion actually ran.
    assertEquals(
        "registry.qits.test.wohlben.eu/qits/workspace:" + WorkspaceImage.VERSION,
        factory().image(),
        "repo and pinned version joined as <repo>:<version>, fully qualified");
  }

  /**
   * An operator's {@code QITS_WORKSPACE_IMAGE_VERSION} still wins — the emergency door, and the only
   * thing left of what used to be the normal path. A hand-built factory stands in for the injection
   * so the override is exercised without booting the app.
   */
  @Test
  void theInjectedVersionWinsOverThePin() {
    WorkspaceContainerFactory overridden = new WorkspaceContainerFactory();
    overridden.imageRepo = "registry.dev.localhost:8080/qits/workspace";
    overridden.imageVersionOverride = Optional.of("2026.999.000000");

    assertEquals(
        "registry.dev.localhost:8080/qits/workspace:2026.999.000000", overridden.image());
  }

  /**
   * …and a BLANK override is not an override.
   *
   * <p>Worth its own case because of how the key is delivered: SmallRye maps an environment variable
   * onto the property, and a deployment that renders {@code QITS_WORKSPACE_IMAGE_VERSION=} — a
   * template with nothing to put in it, which is exactly what qits-configuration leaves behind when
   * the entry it used to write is retired — produces a present, empty value rather than an absent
   * one. Taken literally that composes {@code …/qits/workspace:} and every container launch fails on
   * a reference with no tag.
   */
  @Test
  void aBlankOverrideFallsBackToThePin() {
    WorkspaceContainerFactory blank = new WorkspaceContainerFactory();
    blank.imageRepo = "registry.dev.localhost:8080/qits/workspace";
    blank.imageVersionOverride = Optional.of("");

    assertEquals("registry.dev.localhost:8080/qits/workspace:" + WorkspaceImage.VERSION, blank.image());
  }

  // --- a workspace is a slot (qits-1152) -------------------------------------------------------

  @Test
  void aWorkspaceIsToldNoBranchParentOrWorkItem() {
    WorkspaceContainer c = factory().forWorkspace("repo-1", "ws-1", 7L, "feature/x", "main", "qits-614");

    for (String key :
        List.of(
            "QITS_WORKSPACE_DAEMON_BRANCH",
            "QITS_WORKSPACE_DAEMON_PARENT",
            "QITS_WORKSPACE_DAEMON_ENTITY_ID",
            "QITS_WORKSPACE_DAEMON_ENTITY_TITLE",
            "QITS_WORKSPACE_DAEMON_ENTITY_STATUS",
            "QITS_WORKSPACE_DAEMON_ENTITY_BLOCKED",
            "QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN",
            "QITS_WORKSPACE_DAEMON_EDITOR_ENABLED",
            "QITS_WORKSPACE_DAEMON_EDITOR_PORT",
            "QITS_WORKSPACE_DAEMON_PROJECTS")) {
      assertNull(c.env().get(key), key);
    }
  }
}
