package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The DIRECT container spec, pinned whole (qits-851).
 *
 * <p>The runner spec ({@code RunnerWorkspaceSpecs}) shares the identity and behaviour environment of
 * {@link WorkspaceContainerFactory#forWorkspace}, and sharing it means moving code out of that
 * method. A spec that changes is a {@code Recreate.ifChanged} REPLACEMENT of every stopped
 * container on its next start, so the move has to be provably a move: this test was committed
 * before it, against the code as it stood, and asserts every field and the ORDER of every map —
 * environment and labels are insertion-ordered, and an order the orchestrator hashes is part of the
 * spec. Three rows: the editor takes the other arm of every branch in the shared block, and the
 * admin row holds the host's docker socket; both stay DIRECT for good, so both are pinned.
 *
 * <p>The admin and editor rows are pinned twice (qits-1084): holding the commissioned client pair —
 * the spec a deployment with no edge plane still composes, and every container launched before the
 * token, byte for byte as it was — and holding a workspace token, where the five addresses come off
 * the edge plane and the credential is {@code QITS_TOKEN} with the git helper on the plane's githost,
 * while qits-net, the extra host, the docker socket and the editor block stay exactly where they
 * were.
 *
 * <p>The one field left out is {@code user}: it is this machine's uid, so it is asserted as a uid
 * and not as a value.
 */
class WorkspaceContainerFactoryGoldenSpecTest {

  static WorkspaceContainerFactory factory() {
    WorkspaceContainerFactory f = new WorkspaceContainerFactory();
    f.imageRepo = "registry.dev.localhost:8080/qits/workspace";
    f.imageVersionOverride = Optional.of("2026.1001.120000");
    f.editorImageRepo = "registry.dev.localhost:8080/qits/workspace-editor";
    f.editorImageVersionOverride = Optional.of("2026.1001.130000");
    f.editorPort = 13339;
    f.projectsUrl = "http://qits-projects:8080/";
    f.observabilityUrl = "http://dev-qits-observability:8080/";
    f.platformMcpUrl = "http://dev-qits-platform-access-mcp-service:8080/";
    f.network = "qits-net";
    f.claudeVolume = "qits_shared_dot_claude";
    f.claudeMount = "/claude-home";
    f.mavenVolume = "qits_shared_m2";
    f.pnpmVolume = "qits_shared_pnpm";
    f.domain = Optional.of("example.eu");
    f.workspaceVolumePrefix = "qits_workspace_";
    f.persistWorkspace = true;
    f.timezone = Optional.of("Europe/Berlin");
    f.memoryLimit = Optional.of("4g");
    f.memorySwapLimit = Optional.of("8g");
    f.pidsLimit = Optional.of("4096");
    f.cpus = Optional.of("2");
    f.oomScoreAdj = 600;
    GitIdentity identity = new GitIdentity();
    identity.name = "qits";
    identity.email = "qits@local";
    f.gitIdentity = identity;
    QitsHostResolver resolver = new QitsHostResolver();
    resolver.configured = "qits";
    f.qitsHostResolver = resolver;
    f.qitsPort = "8080";
    f.containerGitUrl = "http://qits-platform-edge:8080";
    f.idpUrl = "http://qits-idp:8080/idp";
    f.daemonApiToken = "qits-workspace-daemon";
    f.bootstrapAutorunEnabled = true;
    f.autoPushEnabled = true;
    f.nameResolver =
        StubInstance.of(
            repoId ->
                Optional.of(new RepositoryAddressResolver.ProjectScopedName("proj-1", "my-repo")));
    f.repositories = StubInstance.empty();
    f.credentials =
        StubInstance.of(rowId -> Optional.of(new WorkspaceCredential("ws-7-a", "s3cr3t")));
    f.postures = StubInstance.empty();
    f.editorProjects = StubInstance.of(() -> List.of("alpha/alpha-alpha", "gamma/gamma-gamma"));
    f.entityFacts =
        StubInstance.of(rowId -> Optional.of(new EntityFacts("Fix the login", "IMPLEMENTING", true)));
    return f;
  }

  @Test
  void anOrdinaryWorkspacesSpecIsExactlyThis() {
    WorkspaceContainer c =
        factory().forWorkspace("repo12345678abc", "work", 7L, "task/a", "epic/b", "qits-614");

    assertTrue(c.user().matches("\\d+"), c.user());
    assertEquals(
        """
        name=qits-ws-work-repo1234
        image=registry.dev.localhost:8080/qits/workspace:2026.1001.120000
        editor=false
        hostDockerSocket=false
        network=qits-net
        memory=4g
        memorySwap=8g
        pidsLimit=4096
        cpus=2
        oomScoreAdj=600
        addHost=host.docker.internal:host-gateway
        label qits.repository=repo12345678abc
        label qits.workspace=work
        label qits.branch=task/a
        label qits.parent=epic/b
        label qits.project=proj-1
        env TZ=Europe/Berlin
        env QITS_WORKSPACE_DAEMON_URL=ws://qits:8080/workspaces/daemon/7
        env QITS_REPOSITORY_MCP_URL=http://qits-projects:8080/projects/mcp
        env QITS_OBSERVABILITY_MCP_URL=http://dev-qits-observability:8080/observability/mcp
        env QITS_PLATFORM_MCP_URL=http://dev-qits-platform-access-mcp-service:8080/mcp
        env QITS_WORKSPACE_DAEMON_GIT_BASE_URL=http://qits-platform-edge:8080/git
        env QITS_WORKSPACE_DAEMON_API_BASE_PATH=/workspaces/container/7/
        env QITS_WORKSPACE_DAEMON_WORKSPACE_ID=work
        env QITS_WORKSPACE_DAEMON_REPOSITORY_ID=repo12345678abc
        env QITS_WORKSPACE_DAEMON_BRANCH=task/a
        env QITS_WORKSPACE_DAEMON_PARENT=epic/b
        env QITS_WORKSPACE_DAEMON_ENTITY_ID=qits-614
        env QITS_WORKSPACE_DAEMON_ENTITY_TITLE=Fix the login
        env QITS_WORKSPACE_DAEMON_ENTITY_STATUS=IMPLEMENTING
        env QITS_WORKSPACE_DAEMON_ENTITY_BLOCKED=true
        env QITS_WORKSPACE_DAEMON_PROJECT_ID=proj-1
        env QITS_WORKSPACE_DAEMON_REPO_NAME=my-repo
        env QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN=true
        env QITS_WORKSPACE_DAEMON_AUTO_PUSH_ENABLED=true
        env QITS_WORKSPACE_DAEMON_SERVICES_AUTOSTART=false
        env QITS_WORKSPACE_DAEMON_API_TOKEN=qits-workspace-daemon
        env QITS_COMMISSIONED_CLIENT_ID=ws-7-a
        env QITS_COMMISSIONED_CLIENT_SECRET=s3cr3t
        env GIT_CONFIG_GLOBAL=/etc/qits-gitconfig
        env QITS_GIT_AUTH_HOST=qits-platform-edge:8080
        env QITS_GIT_AUTH_TOKEN_URL=http://qits-idp:8080/idp/token
        env QITS_GIT_AUTH_AUDIENCE=qits-platform
        env QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL=http://qits-idp:8080/idp/token
        env QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE=qits-platform
        env GIT_AUTHOR_NAME=qits
        env GIT_AUTHOR_EMAIL=qits@local
        env GIT_COMMITTER_NAME=qits
        env GIT_COMMITTER_EMAIL=qits@local
        env CLAUDE_CONFIG_DIR=/claude-home/.claude
        env KIMI_CODE_HOME=/claude-home/.kimi-code
        env MAVEN_OPTS=-Dmaven.repo.local=/caches/m2
        env npm_config_store_dir=/caches/pnpm/store
        env QITS_DOMAIN=example.eu
        volume qits_shared_dot_claude:/claude-home
        volume qits_shared_m2:/caches/m2
        volume qits_shared_pnpm:/caches/pnpm
        volume qits_workspace_work:/workspace
        """,
        render(c));
  }

  @Test
  void theEditorsSpecHoldingThePairIsExactlyThis() {
    WorkspaceContainerFactory f = factory();
    f.postures =
        StubInstance.of(
            new WorkspacePostures() {
              @Override
              public boolean isAdmin(Long rowId) {
                return false;
              }

              @Override
              public boolean isEditor(Long rowId) {
                return true;
              }
            });
    WorkspaceContainer c = f.forWorkspace("editor", "editor", 9L, null, null, null);

    assertEquals(
        """
        name=qits-ws-editor-editor
        image=registry.dev.localhost:8080/qits/workspace-editor:2026.1001.130000
        editor=true
        hostDockerSocket=false
        network=qits-net
        memory=4g
        memorySwap=8g
        pidsLimit=4096
        cpus=2
        oomScoreAdj=600
        addHost=host.docker.internal:host-gateway
        label qits.repository=editor
        label qits.workspace=editor
        label qits.branch=
        label qits.parent=
        label qits.project=
        env TZ=Europe/Berlin
        env QITS_WORKSPACE_DAEMON_URL=ws://qits:8080/workspaces/daemon/9
        env QITS_REPOSITORY_MCP_URL=http://qits-projects:8080/projects/mcp
        env QITS_OBSERVABILITY_MCP_URL=http://dev-qits-observability:8080/observability/mcp
        env QITS_PLATFORM_MCP_URL=http://dev-qits-platform-access-mcp-service:8080/mcp
        env QITS_WORKSPACE_DAEMON_GIT_BASE_URL=http://qits-platform-edge:8080/git
        env QITS_WORKSPACE_DAEMON_API_BASE_PATH=/workspaces/container/9/
        env QITS_WORKSPACE_DAEMON_WORKSPACE_ID=editor
        env QITS_WORKSPACE_DAEMON_REPOSITORY_ID=
        env QITS_WORKSPACE_DAEMON_BRANCH=
        env QITS_WORKSPACE_DAEMON_PARENT=
        env QITS_WORKSPACE_DAEMON_PROJECT_ID=
        env QITS_WORKSPACE_DAEMON_REPO_NAME=
        env QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN=true
        env QITS_WORKSPACE_DAEMON_AUTO_PUSH_ENABLED=true
        env QITS_WORKSPACE_DAEMON_SERVICES_AUTOSTART=false
        env QITS_WORKSPACE_DAEMON_API_TOKEN=qits-workspace-daemon
        env QITS_COMMISSIONED_CLIENT_ID=ws-7-a
        env QITS_COMMISSIONED_CLIENT_SECRET=s3cr3t
        env GIT_CONFIG_GLOBAL=/etc/qits-gitconfig
        env QITS_GIT_AUTH_HOST=qits-platform-edge:8080
        env QITS_GIT_AUTH_TOKEN_URL=http://qits-idp:8080/idp/token
        env QITS_GIT_AUTH_AUDIENCE=qits-platform
        env QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL=http://qits-idp:8080/idp/token
        env QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE=qits-platform
        env QITS_WORKSPACE_DAEMON_EDITOR_ENABLED=true
        env QITS_WORKSPACE_DAEMON_EDITOR_PORT=13339
        env QITS_WORKSPACE_DAEMON_PROJECTS=alpha/alpha-alpha,gamma/gamma-gamma
        env GIT_AUTHOR_NAME=qits
        env GIT_AUTHOR_EMAIL=qits@local
        env GIT_COMMITTER_NAME=qits
        env GIT_COMMITTER_EMAIL=qits@local
        env CLAUDE_CONFIG_DIR=/claude-home/.claude
        env KIMI_CODE_HOME=/claude-home/.kimi-code
        env MAVEN_OPTS=-Dmaven.repo.local=/caches/m2
        env npm_config_store_dir=/caches/pnpm/store
        env QITS_DOMAIN=example.eu
        volume qits_shared_dot_claude:/claude-home
        volume qits_shared_m2:/caches/m2
        volume qits_shared_pnpm:/caches/pnpm
        volume qits_workspace_editor:/workspace
        """,
        render(c));
  }

  /**
   * An admin row: the ordinary spec plus the host's docker socket, and nothing else different. Pinned
   * because the runner work (qits-625) must leave every admin spec exactly as it is: an admin
   * workspace is DIRECT for good.
   */
  @Test
  void anAdminWorkspacesSpecHoldingThePairIsExactlyThis() {
    WorkspaceContainerFactory f = factory();
    f.postures =
        StubInstance.of(
            new WorkspacePostures() {
              @Override
              public boolean isAdmin(Long rowId) {
                return true;
              }

              @Override
              public boolean isEditor(Long rowId) {
                return false;
              }
            });
    WorkspaceContainer c =
        f.forWorkspace("repo12345678abc", "admin", 8L, "admin/recovery", "main", null);

    assertTrue(c.user().matches("\\d+"), c.user());
    assertEquals(
        """
        name=qits-ws-admin-repo1234
        image=registry.dev.localhost:8080/qits/workspace:2026.1001.120000
        editor=false
        hostDockerSocket=true
        network=qits-net
        memory=4g
        memorySwap=8g
        pidsLimit=4096
        cpus=2
        oomScoreAdj=600
        addHost=host.docker.internal:host-gateway
        label qits.repository=repo12345678abc
        label qits.workspace=admin
        label qits.branch=admin/recovery
        label qits.parent=main
        label qits.project=proj-1
        env TZ=Europe/Berlin
        env QITS_WORKSPACE_DAEMON_URL=ws://qits:8080/workspaces/daemon/8
        env QITS_REPOSITORY_MCP_URL=http://qits-projects:8080/projects/mcp
        env QITS_OBSERVABILITY_MCP_URL=http://dev-qits-observability:8080/observability/mcp
        env QITS_PLATFORM_MCP_URL=http://dev-qits-platform-access-mcp-service:8080/mcp
        env QITS_WORKSPACE_DAEMON_GIT_BASE_URL=http://qits-platform-edge:8080/git
        env QITS_WORKSPACE_DAEMON_API_BASE_PATH=/workspaces/container/8/
        env QITS_WORKSPACE_DAEMON_WORKSPACE_ID=admin
        env QITS_WORKSPACE_DAEMON_REPOSITORY_ID=repo12345678abc
        env QITS_WORKSPACE_DAEMON_BRANCH=admin/recovery
        env QITS_WORKSPACE_DAEMON_PARENT=main
        env QITS_WORKSPACE_DAEMON_ENTITY_TITLE=Fix the login
        env QITS_WORKSPACE_DAEMON_ENTITY_STATUS=IMPLEMENTING
        env QITS_WORKSPACE_DAEMON_ENTITY_BLOCKED=true
        env QITS_WORKSPACE_DAEMON_PROJECT_ID=proj-1
        env QITS_WORKSPACE_DAEMON_REPO_NAME=my-repo
        env QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN=true
        env QITS_WORKSPACE_DAEMON_AUTO_PUSH_ENABLED=true
        env QITS_WORKSPACE_DAEMON_SERVICES_AUTOSTART=false
        env QITS_WORKSPACE_DAEMON_API_TOKEN=qits-workspace-daemon
        env QITS_COMMISSIONED_CLIENT_ID=ws-7-a
        env QITS_COMMISSIONED_CLIENT_SECRET=s3cr3t
        env GIT_CONFIG_GLOBAL=/etc/qits-gitconfig
        env QITS_GIT_AUTH_HOST=qits-platform-edge:8080
        env QITS_GIT_AUTH_TOKEN_URL=http://qits-idp:8080/idp/token
        env QITS_GIT_AUTH_AUDIENCE=qits-platform
        env QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL=http://qits-idp:8080/idp/token
        env QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE=qits-platform
        env GIT_AUTHOR_NAME=qits
        env GIT_AUTHOR_EMAIL=qits@local
        env GIT_COMMITTER_NAME=qits
        env GIT_COMMITTER_EMAIL=qits@local
        env CLAUDE_CONFIG_DIR=/claude-home/.claude
        env KIMI_CODE_HOME=/claude-home/.kimi-code
        env MAVEN_OPTS=-Dmaven.repo.local=/caches/m2
        env npm_config_store_dir=/caches/pnpm/store
        env QITS_DOMAIN=example.eu
        volume qits_shared_dot_claude:/claude-home
        volume qits_shared_m2:/caches/m2
        volume qits_shared_pnpm:/caches/pnpm
        volume qits_workspace_admin:/workspace
        """,
        render(c));
  }

  // --- a DIRECT row that holds a workspace token (qits-1084) ------------------------------------

  /** A credential lookup that answers a workspace token for every row, and no pair. */
  static jakarta.enterprise.inject.Instance<WorkspaceCredentials> tokenHolding(String token, String subject) {
    return StubInstance.of(
        new WorkspaceCredentials() {
          @Override
          public Optional<WorkspaceCredential> forWorkspace(Long rowId) {
            return Optional.empty();
          }

          @Override
          public Optional<WorkspaceToken> tokenFor(Long rowId) {
            return Optional.of(new WorkspaceToken("tok-id-" + rowId, token, subject));
          }
        });
  }

  static WorkspacePostures posture(boolean admin, boolean editor) {
    return new WorkspacePostures() {
      @Override
      public boolean isAdmin(Long rowId) {
        return admin;
      }

      @Override
      public boolean isEditor(Long rowId) {
        return editor;
      }
    };
  }

  /**
   * An admin row holding a workspace token: the edge's five addresses, the token's four lines where
   * the pair's eight were, and every DIRECT field — the network, the extra host, the docker socket,
   * the shared volumes — unchanged.
   */
  @Test
  void anAdminWorkspacesSpecHoldingATokenIsExactlyThis() {
    WorkspaceContainerFactory f = factory();
    f.postures = StubInstance.of(posture(true, false));
    f.credentials = tokenHolding("qits_tok_admin8", "tok-workspace-admin-8");
    WorkspaceContainer c =
        f.forWorkspace("repo12345678abc", "admin", 8L, "admin/recovery", "main", null);

    assertTrue(c.user().matches("\\d+"), c.user());
    assertEquals(
        """
        name=qits-ws-admin-repo1234
        image=registry.dev.localhost:8080/qits/workspace:2026.1001.120000
        editor=false
        hostDockerSocket=true
        network=qits-net
        memory=4g
        memorySwap=8g
        pidsLimit=4096
        cpus=2
        oomScoreAdj=600
        addHost=host.docker.internal:host-gateway
        label qits.repository=repo12345678abc
        label qits.workspace=admin
        label qits.branch=admin/recovery
        label qits.parent=main
        label qits.project=proj-1
        env TZ=Europe/Berlin
        env QITS_WORKSPACE_DAEMON_URL=wss://workspaces.qits.example.eu/workspaces/daemon/8
        env QITS_REPOSITORY_MCP_URL=https://projects.qits.example.eu/projects/mcp
        env QITS_OBSERVABILITY_MCP_URL=https://observability.qits.example.eu/observability/mcp
        env QITS_PLATFORM_MCP_URL=https://mcp.qits.example.eu/mcp
        env QITS_WORKSPACE_DAEMON_GIT_BASE_URL=https://githost.qits.example.eu/git
        env QITS_WORKSPACE_DAEMON_API_BASE_PATH=/workspaces/container/8/
        env QITS_WORKSPACE_DAEMON_WORKSPACE_ID=admin
        env QITS_WORKSPACE_DAEMON_REPOSITORY_ID=repo12345678abc
        env QITS_WORKSPACE_DAEMON_BRANCH=admin/recovery
        env QITS_WORKSPACE_DAEMON_PARENT=main
        env QITS_WORKSPACE_DAEMON_ENTITY_TITLE=Fix the login
        env QITS_WORKSPACE_DAEMON_ENTITY_STATUS=IMPLEMENTING
        env QITS_WORKSPACE_DAEMON_ENTITY_BLOCKED=true
        env QITS_WORKSPACE_DAEMON_PROJECT_ID=proj-1
        env QITS_WORKSPACE_DAEMON_REPO_NAME=my-repo
        env QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN=true
        env QITS_WORKSPACE_DAEMON_AUTO_PUSH_ENABLED=true
        env QITS_WORKSPACE_DAEMON_SERVICES_AUTOSTART=false
        env QITS_WORKSPACE_DAEMON_API_TOKEN=qits-workspace-daemon
        env QITS_TOKEN=qits_tok_admin8
        env QITS_TOKEN_SUBJECT=tok-workspace-admin-8
        env GIT_CONFIG_GLOBAL=/etc/qits-gitconfig
        env QITS_GIT_AUTH_HOST=githost.qits.example.eu
        env GIT_AUTHOR_NAME=qits
        env GIT_AUTHOR_EMAIL=qits@local
        env GIT_COMMITTER_NAME=qits
        env GIT_COMMITTER_EMAIL=qits@local
        env CLAUDE_CONFIG_DIR=/claude-home/.claude
        env KIMI_CODE_HOME=/claude-home/.kimi-code
        env MAVEN_OPTS=-Dmaven.repo.local=/caches/m2
        env npm_config_store_dir=/caches/pnpm/store
        env QITS_DOMAIN=example.eu
        volume qits_shared_dot_claude:/claude-home
        volume qits_shared_m2:/caches/m2
        volume qits_shared_pnpm:/caches/pnpm
        volume qits_workspace_admin:/workspace
        """,
        render(c));
  }

  /**
   * The editor holding a workspace token: the edge's addresses and the token, with the editor block
   * — its port and every project's wrapper — exactly where the pair's spec has it.
   */
  @Test
  void theEditorsSpecHoldingATokenIsExactlyThis() {
    WorkspaceContainerFactory f = factory();
    f.postures = StubInstance.of(posture(false, true));
    f.credentials = tokenHolding("qits_tok_editor9", "tok-workspace-editor-9");
    WorkspaceContainer c = f.forWorkspace("editor", "editor", 9L, null, null, null);

    assertEquals(
        """
        name=qits-ws-editor-editor
        image=registry.dev.localhost:8080/qits/workspace-editor:2026.1001.130000
        editor=true
        hostDockerSocket=false
        network=qits-net
        memory=4g
        memorySwap=8g
        pidsLimit=4096
        cpus=2
        oomScoreAdj=600
        addHost=host.docker.internal:host-gateway
        label qits.repository=editor
        label qits.workspace=editor
        label qits.branch=
        label qits.parent=
        label qits.project=
        env TZ=Europe/Berlin
        env QITS_WORKSPACE_DAEMON_URL=wss://workspaces.qits.example.eu/workspaces/daemon/9
        env QITS_REPOSITORY_MCP_URL=https://projects.qits.example.eu/projects/mcp
        env QITS_OBSERVABILITY_MCP_URL=https://observability.qits.example.eu/observability/mcp
        env QITS_PLATFORM_MCP_URL=https://mcp.qits.example.eu/mcp
        env QITS_WORKSPACE_DAEMON_GIT_BASE_URL=https://githost.qits.example.eu/git
        env QITS_WORKSPACE_DAEMON_API_BASE_PATH=/workspaces/container/9/
        env QITS_WORKSPACE_DAEMON_WORKSPACE_ID=editor
        env QITS_WORKSPACE_DAEMON_REPOSITORY_ID=
        env QITS_WORKSPACE_DAEMON_BRANCH=
        env QITS_WORKSPACE_DAEMON_PARENT=
        env QITS_WORKSPACE_DAEMON_PROJECT_ID=
        env QITS_WORKSPACE_DAEMON_REPO_NAME=
        env QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN=true
        env QITS_WORKSPACE_DAEMON_AUTO_PUSH_ENABLED=true
        env QITS_WORKSPACE_DAEMON_SERVICES_AUTOSTART=false
        env QITS_WORKSPACE_DAEMON_API_TOKEN=qits-workspace-daemon
        env QITS_TOKEN=qits_tok_editor9
        env QITS_TOKEN_SUBJECT=tok-workspace-editor-9
        env GIT_CONFIG_GLOBAL=/etc/qits-gitconfig
        env QITS_GIT_AUTH_HOST=githost.qits.example.eu
        env QITS_WORKSPACE_DAEMON_EDITOR_ENABLED=true
        env QITS_WORKSPACE_DAEMON_EDITOR_PORT=13339
        env QITS_WORKSPACE_DAEMON_PROJECTS=alpha/alpha-alpha,gamma/gamma-gamma
        env GIT_AUTHOR_NAME=qits
        env GIT_AUTHOR_EMAIL=qits@local
        env GIT_COMMITTER_NAME=qits
        env GIT_COMMITTER_EMAIL=qits@local
        env CLAUDE_CONFIG_DIR=/claude-home/.claude
        env KIMI_CODE_HOME=/claude-home/.kimi-code
        env MAVEN_OPTS=-Dmaven.repo.local=/caches/m2
        env npm_config_store_dir=/caches/pnpm/store
        env QITS_DOMAIN=example.eu
        volume qits_shared_dot_claude:/claude-home
        volume qits_shared_m2:/caches/m2
        volume qits_shared_pnpm:/caches/pnpm
        volume qits_workspace_editor:/workspace
        """,
        render(c));
  }

  /**
   * A row that holds a token on a deployment whose domain has gone (no edge plane): the start fails
   * saying so, rather than launching a token container on internal addresses that cannot spend it.
   */
  @Test
  void aTokenHoldingRowWithNoEdgePlaneIsRefusedRatherThanMisaddressed() {
    WorkspaceContainerFactory f = factory();
    f.domain = Optional.empty();
    f.postures = StubInstance.of(posture(true, false));
    f.credentials = tokenHolding("qits_tok_admin8", "tok-workspace-admin-8");

    EdgePlaneUnconfigured refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            EdgePlaneUnconfigured.class,
            () -> f.forWorkspace("repo12345678abc", "admin", 8L, "admin/recovery", "main", null));
    assertTrue(refused.getMessage().startsWith(EdgePlaneUnconfigured.CODE), refused.getMessage());
  }

  /** Every field but the host uid, one per line, maps in their own order. */
  static String render(WorkspaceContainer c) {
    StringBuilder out = new StringBuilder();
    out.append("name=").append(c.name()).append('\n');
    out.append("image=").append(c.image()).append('\n');
    out.append("editor=").append(c.editor()).append('\n');
    out.append("hostDockerSocket=").append(c.hostDockerSocket()).append('\n');
    out.append("network=").append(c.network()).append('\n');
    out.append("memory=").append(c.memory()).append('\n');
    out.append("memorySwap=").append(c.memorySwap()).append('\n');
    out.append("pidsLimit=").append(c.pidsLimit()).append('\n');
    out.append("cpus=").append(c.cpus()).append('\n');
    out.append("oomScoreAdj=").append(c.oomScoreAdj()).append('\n');
    c.addHosts().forEach(h -> out.append("addHost=").append(h).append('\n'));
    c.labels().forEach((k, v) -> out.append("label ").append(k).append('=').append(v).append('\n'));
    c.env().forEach((k, v) -> out.append("env ").append(k).append('=').append(v).append('\n'));
    c.volumes()
        .forEach(
            m ->
                out.append("volume ")
                    .append(m.volumeName())
                    .append(':')
                    .append(m.containerPath())
                    .append('\n'));
    return out.toString();
  }
}
