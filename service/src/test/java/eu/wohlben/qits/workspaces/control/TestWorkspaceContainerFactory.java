package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * A {@link WorkspaceContainerFactory} configured by hand, for the plain-JUnit tests of the
 * orchestrator adapter in {@code containershost}.
 *
 * <p><b>It lives in this package because the factory's {@code @ConfigProperty} fields are
 * package-private</b>, and it lives in the {@code service} test tree because that is where the
 * adapter is. Both facts together are why this is a class rather than a method on the test: the test
 * cannot sit in this package (it is about a class in another one) and cannot reach these fields from
 * its own.
 *
 * <p>The values mirror what the service ships, and every one of them is asserted somewhere by the
 * adapter's test — an image with a port and a calver tag, the three shared volumes at their fixed
 * mounts, a memory cap with swap headroom and no pids/cpus cap, a deterministic qits host so the daemon's
 * dial-home URL is a literal rather than this machine's. {@code domain}'s own
 * {@code WorkspaceContainerFactoryTest} builds the same thing for the same reason; the two are
 * duplicated because the modules do not share a test classpath.
 */
public final class TestWorkspaceContainerFactory {

  /**
   * The image every test container runs, split into the two keys the factory now composes. Invented,
   * and deliberately not a copy of the shipped pin.
   */
  public static final String IMAGE_REPO = "localhost:8081/qits/workspace";

  public static final String IMAGE_VERSION = "2026.101.1";

  /** The composed reference, {@code <repo>:<version>} — what a spec carries. */
  public static final String IMAGE = IMAGE_REPO + ":" + IMAGE_VERSION;

  /**
   * The editor image, on its own repo and its own calver — deliberately a different version from
   * {@link #IMAGE_VERSION}, because the two images are released by two repositories on two trains
   * and a fixture that shared a number could not tell a derived reference from a configured one.
   */
  public static final String EDITOR_IMAGE_REPO = "localhost:8081/qits/workspace-editor";

  public static final String EDITOR_IMAGE_VERSION = "2026.202.2";

  public static final String EDITOR_IMAGE = EDITOR_IMAGE_REPO + ":" + EDITOR_IMAGE_VERSION;

  /** The loopback port the fixture's editor is told to serve on — the shipped default. */
  public static final int EDITOR_PORT = 13339;

  /**
   * The Maven Central pull-through every fixture container is told about. Invented like the image
   * reference, and present rather than empty because this key ships non-empty: a fixture with it
   * blank would model the off switch instead of the shipped posture.
   */
  public static final String MAVEN_CENTRAL_URL = "http://mirror.test:8080/mirror/maven/central";

  private TestWorkspaceContainerFactory() {}

  /** A factory with the per-workspace {@code /workspace} volume on — the shipped default. */
  public static WorkspaceContainerFactory persistent() {
    return build(true);
  }

  /** A factory with {@code qits.workspace.persist-workspace} off — the kill switch's shape. */
  public static WorkspaceContainerFactory ephemeralWorkspace() {
    return build(false);
  }

  /** A factory with {@code qits.workspace.memory-swap-limit} blanked — a deployment granting no swap. */
  public static WorkspaceContainerFactory noSwap() {
    WorkspaceContainerFactory f = build(true);
    f.memorySwapLimit = Optional.empty();
    return f;
  }

  /**
   * A factory whose workspaces hold a commissioned platform credential — the shape a deployment with
   * an issuer wired has. The shipped posture is the other one, which is why the two builders above
   * leave the lookup empty.
   */
  public static WorkspaceContainerFactory commissioned(String clientId, String secret) {
    WorkspaceContainerFactory f = build(true);
    f.credentials = StubInstance.of(rowId -> Optional.of(new WorkspaceCredential(clientId, secret)));
    return f;
  }

  /** Where a container's agent-configuration document lands — the shipped default. */
  public static final String AGENT_CONFIGURATION_PATH = "/tmp/qits/agent-configuration.json";

  /** The document a configured fixture's workspace was born with. Authored, and minimally one. */
  public static final String AGENT_CONFIGURATION_DOCUMENT =
      "{\"version\":1,\"surfaces\":[{\"surface\":\"epic.chat\"}]}";

  /**
   * A factory whose workspaces carry an agent-configuration document — the shape a deployment with
   * qits-projects answering has. Its own builder for {@link #admin()}'s reason: the adapter's test
   * asserts the whole spec, and the difference between two specs is the claim.
   */
  public static WorkspaceContainerFactory configured() {
    WorkspaceContainerFactory f = build(true);
    f.agentConfiguration = StubInstance.of(rowId -> Optional.of(AGENT_CONFIGURATION_DOCUMENT));
    return f;
  }

  private static WorkspaceContainerFactory build(boolean persistWorkspace) {
    WorkspaceContainerFactory f = new WorkspaceContainerFactory();
    f.imageRepo = IMAGE_REPO;
    f.imageVersion = IMAGE_VERSION;
    f.editorImageRepo = EDITOR_IMAGE_REPO;
    f.editorImageVersion = EDITOR_IMAGE_VERSION;
    f.editorPort = EDITOR_PORT;
    f.projectsUrl = "http://qits-projects:8080";
    f.observabilityUrl = "http://qits-observability:8080";
    f.network = "qits-net";
    f.claudeVolume = "qits_shared_dot_claude";
    f.claudeMount = "/claude-home";
    f.mavenVolume = "qits_shared_m2";
    f.pnpmVolume = "qits_shared_pnpm";
    // The registry addresses a deployment tells the factory. Empty here, which is the shipped
    // posture — there is no default address to ship — and enough to keep every container this
    // builder makes identical to one built before these keys existed.
    f.mavenRepositoryUrl = Optional.empty();
    f.npmRegistryUrl = Optional.empty();
    f.npmProxyUrl = Optional.empty();
    // Maven Central's pull-through, which unlike the three above IS shipped non-empty — the mirror
    // is a platform service with no environment in its name, so the address is the same on every
    // deployment. Set here so this fixture carries the shipped posture; the address itself is
    // invented, for the reason the image reference above is.
    f.mavenCentralUrl = Optional.of(MAVEN_CENTRAL_URL);
    f.workspaceVolumePrefix = "qits_workspace_";
    f.persistWorkspace = persistWorkspace;
    f.timezone = Optional.of("UTC");
    f.memoryLimit = Optional.of("4g");
    f.memorySwapLimit = Optional.of("8g");
    f.pidsLimit = Optional.empty();
    f.cpus = Optional.empty();
    // The @ConfigProperty defaultValue never runs here — a hand-built factory must restate it, or
    // the spec carries null where every deployment carries 600.
    f.oomScoreAdj = 600;
    f.gitIdentity = identity();
    f.qitsHostResolver = resolver();
    f.qitsPort = "8080";
    f.containerGitUrl = "http://qits-platform-edge:8080";
    f.gitHostAudience = "qits-githost";
    f.idpUrl = "http://qits-idp:8080/idp";
    f.machineAudience = "qits-workspaces";
    f.daemonApiToken = "qits-workspace-daemon";
    f.bootstrapAutorunEnabled = true;
    f.autoPushEnabled = true;
    f.servicesAutostartEnabled = true;
    f.serviceReadyGraceMs = 10000;
    f.serviceBackoffInitialMs = 1000;
    f.serviceBackoffMaxMs = 30000;
    f.serviceStopGraceMs = 5000;
    f.nameResolver =
        StubInstance.of(
            repoId ->
                Optional.of(new RepositoryAddressResolver.ProjectScopedName("proj-1", "my-repo")));
    f.repositories = StubInstance.empty();
    // No issuer wired — the shipped posture, so no container carries a commissioned pair.
    f.credentials = StubInstance.empty();
    // No posture lookup — an ordinary workspace, which is what every workspace is unless somebody
    // asked otherwise at creation. `admin()` below is the other one.
    f.postures = StubInstance.empty();
    // No agent configuration on the row — a container on the harness library's shipped defaults,
    // which is what every container was born with before the document existed. `configured()` below
    // is the other one, and the adapter's test asserts the whole spec both ways.
    f.agentConfiguration = StubInstance.empty();
    f.agentConfigurationPath = AGENT_CONFIGURATION_PATH;
    return f;
  }

  /**
   * A factory whose workspaces are the ADMIN kind — the posture that binds the host's docker socket
   * into the container. Its own builder rather than a flag on {@link #persistent()}, because the
   * adapter's test asserts the whole spec twice and the difference between the two is the claim.
   */
  public static WorkspaceContainerFactory admin() {
    WorkspaceContainerFactory f = build(true);
    f.postures = StubInstance.of(rowId -> true);
    return f;
  }

  /**
   * A factory whose workspaces are the project wrapper's main one — the EDITOR posture, which picks
   * the editor image and hands the daemon its editor environment. Its own builder for the reason
   * {@link #admin()} has one: the adapter's test asserts the whole spec twice, and the difference
   * between the two is the claim.
   *
   * <p>An explicit implementation rather than a lambda, because {@code isWrapperMain} is a {@code
   * default} method — a lambda would set the admin answer and leave this one false.
   */
  public static WorkspaceContainerFactory editor() {
    WorkspaceContainerFactory f = build(true);
    f.postures = StubInstance.of(wrapperMain());
    return f;
  }

  /** A posture port that answers "the wrapper's main workspace" and "not admin". */
  static WorkspacePostures wrapperMain() {
    return new WorkspacePostures() {
      @Override
      public boolean isAdmin(Long rowId) {
        return false;
      }

      @Override
      public boolean isWrapperMain(Long rowId) {
        return true;
      }
    };
  }

  private static GitIdentity identity() {
    GitIdentity identity = new GitIdentity();
    identity.name = "qits";
    identity.email = "qits@local";
    return identity;
  }

  /** An explicit host, so the daemon's dial-home URL is deterministic rather than this machine's. */
  private static QitsHostResolver resolver() {
    QitsHostResolver r = new QitsHostResolver();
    r.configured = "qits";
    return r;
  }
}
