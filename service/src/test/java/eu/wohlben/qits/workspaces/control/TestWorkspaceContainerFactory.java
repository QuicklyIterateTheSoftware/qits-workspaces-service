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
   * A factory whose {@code qits.workspace.claude-mount} is NOT the shipped default — for a test
   * that must prove a reader derives the mount from the factory rather than carrying its own copy
   * of the default value (qits-945, {@code RunnerLoginCommandTest}): a reader that silently fell
   * back to {@code /claude-home} would pass against {@link #persistent()} just as happily.
   */
  public static WorkspaceContainerFactory withClaudeMount(String mount) {
    WorkspaceContainerFactory f = build(true);
    f.claudeMount = mount;
    return f;
  }

  /**
   * A factory whose containers run as {@code uid} rather than as whoever owns this machine's {@code
   * user.home} — for a row whose request body is committed (qits-1163, {@code ContainersContract}):
   * the host uid is 0 in one container and 1001 on the CI builder, so a pact written from {@link
   * #persistent()} depends on the machine that builds it.
   */
  public static WorkspaceContainerFactory withHostUid(long uid) {
    WorkspaceContainerFactory f = build(true);
    f.hostUid = () -> uid;
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

  /**
   * A factory whose workspaces hold a workspace token on an edge plane under {@code domain} — the
   * shape an admin or editor row has on a deployment with a public {@code QITS_DOMAIN} (qits-1084).
   * The admin posture, because a DIRECT token row is an admin or editor row.
   */
  public static WorkspaceContainerFactory tokenHolding(
      String domain, String token, String subject) {
    WorkspaceContainerFactory f = build(true);
    f.domain = Optional.of(domain);
    f.postures = StubInstance.of(rowId -> true);
    f.credentials =
        StubInstance.of(
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
    return f;
  }

  private static WorkspaceContainerFactory build(boolean persistWorkspace) {
    WorkspaceContainerFactory f = new WorkspaceContainerFactory();
    f.imageRepo = IMAGE_REPO;
    // Set through the OVERRIDE, because this fixture's whole point is an invented reference that is
    // not the shipped pin — and the pin is now a compiled-in constant, not something a field
    // assignment can replace. The two versions below stay invented and stay different from each
    // other, for the reason the constants' javadoc gives.
    f.imageVersionOverride = Optional.of(IMAGE_VERSION);
    f.platformMcpUrl = "http://dev-qits-platform-access-mcp-service:8080";
    f.network = "qits-net";
    f.claudeVolume = "qits_shared_dot_claude";
    f.claudeMount = "/claude-home";
    f.mavenVolume = "qits_shared_m2";
    f.pnpmVolume = "qits_shared_pnpm";
    // No public domain, the suites' pinned posture: containers here carry no QITS_DOMAIN, the only
    // registry input a workspace container is ever handed.
    f.domain = Optional.empty();
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
    f.idpUrl = "http://qits-idp:8080/idp";
    f.daemonApiToken = "qits-workspace-daemon";
    f.autoPushEnabled = true;
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
