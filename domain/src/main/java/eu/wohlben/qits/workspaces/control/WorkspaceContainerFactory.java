package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceImage;
import eu.wohlben.qits.workspaceeditor.WorkspaceEditorImage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Produces a {@link WorkspaceContainer} already seeded with the cross-cutting configuration every
 * workspace container must have — the container-creation analog of {@code CodingAgentFactory}.
 * Routing all creation through {@link #forWorkspace} makes it structurally impossible to start a
 * workspace container without the shared credential volume, the {@code qits.*} reconciliation
 * labels, the docker-host alias and the host uid. {@code containershost/WorkspaceContainers} is the
 * sole caller; it turns what this describes into the orchestrator's wire spec.
 */
@ApplicationScoped
public class WorkspaceContainerFactory {

  private static final Logger LOG = Logger.getLogger(WorkspaceContainerFactory.class);

  /** qits-projects' service base; forwarded explicitly because the daemon control socket is not a gateway. */
  @ConfigProperty(name = "qits.projects.url")
  String projectsUrl;

  /** qits-observability's service base, used by workspace-scoped coding agents. */
  @ConfigProperty(name = "qits.observability.url", defaultValue = "http://qits-observability:8080")
  String observabilityUrl;

  /**
   * The registry host and path of the image every workspace container runs — the released toolchain
   * with the workspace-daemon as its entrypoint ({@code registry.dev.localhost:8080/qits/workspace}).
   * It is the fixed half of the reference: {@link #imageVersion} carries the calver tag, and {@link
   * #image()} joins them as {@code <repo>:<version>}.
   *
   * <p><b>The registry host is part of the value.</b> A bare name would resolve against whatever is
   * lying in the host daemon's local image store — the {@code qits/workspace:latest} drift this
   * shape exists to end — so the value handed to {@code docker run} must be fully qualified. The
   * reasoning for both halves lives in {@code META-INF/microprofile-config.properties}.
   */
  @ConfigProperty(name = "qits.workspace.image-repo")
  String imageRepo;

  /**
   * The released calver the workspace image is pinned to — <b>{@link WorkspaceImage#VERSION}, the
   * version of the dependency this reactor pins</b>, with config as an explicit override and
   * nothing else.
   *
   * <p><b>It used to be the other way round</b>, and that is the defect this field records. The
   * value came from config, the deployer injected {@code QITS_WORKSPACE_IMAGE_VERSION} out of
   * qits-configuration, and qits-configuration's release listener rewrote that entry the moment
   * qits-workspace-daemon pushed an image. So a new daemon was used by the next workspace without
   * this service's tests ever having seen the pair — and the shipped fallback aged in silence until
   * it named an image the registry's retention had deleted, which made any run <em>without</em> the
   * injection start from a reference that could not be pulled.
   *
   * <p>Now the version is the version of {@code eu.wohlben.qits:qits-workspace-daemon-protocol}, the
   * jar that also carries the protocol both ends speak. It cannot age: the dependency has to resolve
   * for this reactor to build, {@code WorkspaceDaemonPinIT} starts that exact daemon and talks to it
   * before the gate goes green, and the maintenance train moves the pom line through this
   * repository's own release request.
   *
   * <p><b>An emergency override survives, under a name nothing automates.</b> {@code
   * qits.workspace.image-version-override} — {@code QITS_WORKSPACE_IMAGE_VERSION_OVERRIDE} — is
   * {@code Optional} with no shipped default, so absent is the ordinary state. An operator who has
   * to pin a different image live can still set it, and the pair is then explicitly untested by
   * anything, which is the honest reading of an override.
   *
   * <p><b>The key was renamed on 2026-09-16 and the old name is the reason.</b> This used to read
   * {@code qits.workspace.image-version}, which is exactly the key qits-configuration's release
   * listener wrote on every image release — so "an emergency override" and "the automatic pin" were
   * one string, and the automatic one won on every deploy. Retiring the listener's row stops it
   * being written again but cannot unwrite the entries already there, and this service cannot delete
   * them. A different name is what makes the residue stop deciding, with no deletion required and no
   * way for an automatic writer to land on the override again by accident. {@link
   * RetiredImageVersionKeys} says so out loud while the old entries are still present.
   */
  @ConfigProperty(name = "qits.workspace.image-version-override")
  Optional<String> imageVersionOverride;

  /**
   * The registry host and path of the <b>editor</b> image — {@code qits/workspace-editor}, the
   * workspace image with openvscode-server laid on top. Everything {@link #imageRepo}'s comment says
   * about fully qualifying the reference applies here unchanged; the reasoning for both halves lives
   * in {@code META-INF/microprofile-config.properties}.
   *
   * <p>It is a <b>second pair of keys and not a suffix on the first</b>, because the two images are
   * released by two repositories on two calvers: qits-workspace-daemon publishes {@code
   * qits/workspace} and qits-workspace-editor-oci follows it one hop later with its own version. A
   * derived name would tie them into one string and be wrong the moment either train ran alone.
   */
  @ConfigProperty(name = "qits.editor.image-repo")
  String editorImageRepo;

  /**
   * The released calver the editor image is pinned to — {@link WorkspaceEditorImage#VERSION}, with
   * config as an explicit override. Everything {@link #imageVersionOverride}'s javadoc says applies
   * here verbatim, against {@code eu.wohlben.qits:qits-workspace-editor-image} and {@code
   * QITS_EDITOR_IMAGE_VERSION}.
   *
   * <p>One difference worth stating: nothing tests this pair the way {@code WorkspaceDaemonPinIT}
   * tests the workspace one, and nothing can from here. The editor image is the workspace image plus
   * a directory, and what would be under test is whether openvscode-server is where the daemon
   * expects it — which is a question about the image's contents, and this service cannot run an
   * image (no docker in a CI step container). What the dependency buys here is the other half, and
   * it is the half that broke: the version <b>resolves</b>, so it names something that was really
   * published, and it moves through a reviewed release rather than underneath one.
   */
  @ConfigProperty(name = "qits.editor.image-version-override")
  Optional<String> editorImageVersionOverride;

  /**
   * The loopback port the in-container editor listens on, forwarded to the daemon as {@code
   * QITS_WORKSPACE_DAEMON_EDITOR_PORT}. Spelled once here because the host's editor proxy has to
   * dial the same number the daemon was told to serve on; the daemon's own default is the same
   * 13339, so the two agree even for a container launched before this key existed.
   */
  @ConfigProperty(name = "qits.editor.port", defaultValue = "13339")
  int editorPort;

  /**
   * The shared Docker network every workspace container joins (and qits is on), so qits reaches a
   * container's ports by its DNS name with no host-port publishing. Creating it is the bootstrap's
   * job — the orchestrator only probes for it, and this service no longer creates networks at all.
   */
  @ConfigProperty(name = "qits.workspace.network", defaultValue = "qits-net")
  String network;

  /**
   * The shared named volume holding the coding agent's home ({@code ~/.claude} — the one-time OAuth
   * login). Mounted read/write into every workspace container so an in-container {@code claude} can
   * authenticate; blank disables the mount. See {@code docker/workspace/agent-login.sh}.
   */
  @ConfigProperty(name = "qits.workspace.claude-volume", defaultValue = "qits_shared_dot_claude")
  String claudeVolume;

  /** Where {@link #claudeVolume} mounts (and where agent launches point {@code HOME}). */
  @ConfigProperty(name = "qits.workspace.claude-mount", defaultValue = "/claude-home")
  String claudeMount;

  /**
   * Shared build caches mounted into every workspace container (and qits' own devcontainer), so a
   * dependency downloaded by one build is reused by all — the Maven local repo and the pnpm store.
   * Blank disables the mount. Mount points are fixed ({@code /caches/m2}, {@code /caches/pnpm},
   * both {@code chmod 0777} in the image) and Maven/pnpm are pointed at them via {@code MAVEN_OPTS}
   * / {@code npm_config_store_dir}.
   */
  @ConfigProperty(name = "qits.workspace.maven-volume", defaultValue = "qits_shared_m2")
  String mavenVolume;

  @ConfigProperty(name = "qits.workspace.pnpm-volume", defaultValue = "qits_shared_pnpm")
  String pnpmVolume;

  /**
   * The platform's own package registries, as a workspace container must dial them — told outright,
   * never derived, the same rule {@code qits.workspace.container-git-url} follows and for the same
   * reason. Every qits pom and every SPA lockfile names the DEPLOYMENT HOST's published address
   * ({@code registry.dev.localhost:8080}, {@code localhost:8081}); inside a container on qits-net
   * that resolves to loopback, where nothing listens, so a build that is told nothing fails with
   * "Blocked mirror" (Maven refuses plain http) or installs the public internet's packages instead
   * of the platform's. CI has always been told these three addresses — {@code
   * QITS_ARTIFACTS_MAVEN_REGISTRY_URL} and friends — and a workspace was not; that asymmetry is
   * what this closes.
   *
   * <p><b>Absent is a supported configuration.</b> Unset, nothing is injected and the container
   * behaves exactly as it did before: the image's profile snippet adds no {@code -s} without a
   * Maven address, and npm keeps whatever the repository's own {@code .npmrc} says. A wrong guess
   * would be worse than silence — there is no address to derive, because the artifacts alias
   * carries the environment name and the npm proxy is a platform service that does not.
   */
  @ConfigProperty(name = "qits.workspace.maven-repository-url")
  Optional<String> mavenRepositoryUrl;

  /** The hosted {@code @qits} npm scope — qits-artifacts. See {@link #mavenRepositoryUrl}. */
  @ConfigProperty(name = "qits.workspace.npm-registry-url")
  Optional<String> npmRegistryUrl;

  /** The npmjs pull-through cache — qits-platform-mirror. See {@link #mavenRepositoryUrl}. */
  @ConfigProperty(name = "qits.workspace.npm-proxy-url")
  Optional<String> npmProxyUrl;

  /**
   * Maven Central through qits-platform-mirror's pull-through cache — npm's proxy above, for the
   * other package manager. Injected as {@code QITS_MAVEN_CENTRAL_URL}, which the workspace image's
   * {@code /etc/qits/maven-settings.xml} activates its central-proxy profile on; blank injects
   * nothing and a build resolves {@code repo1.maven.org} directly, exactly as before this key
   * existed. That is the off switch, and it is the whole switch — the profile activates on a
   * NON-EMPTY value (measured on Maven 3.9: an empty environment value does not activate a
   * property-presence profile), so no call site needs a guard beyond the blank filter below.
   *
   * <p><b>Unlike the three keys above, this one SHIPS A DEFAULT.</b> Their addresses carry an
   * environment name ({@code dev-qits-artifacts}), so a default would be a guess at the
   * deployment's topology; qits-platform-mirror is a platform service deployed once, under a name
   * with no environment in it, and a workspace container sits on qits-net — so the address is the
   * same everywhere and nothing is being guessed. The mirror plane is on by default platform-wide
   * (qits-ci ships its own two equivalents non-empty), and a workspace was the last builder still
   * reaching Central directly.
   *
   * <p><b>It rides the spec, so it obeys the spec-hash rule</b> the editor and credential blocks
   * carry: environment is part of the spec, and a spec that differs from the running container's is
   * a {@code Recreate.ifChanged} REPLACEMENT. The value is therefore a CONSTANT off config, never
   * derived per call — a per-call value would make every ensure a replacement. Adding it replaces
   * each existing workspace container once, on its next ensure; {@code /workspace} is a volume and
   * survives that.
   */
  @ConfigProperty(name = "qits.workspace.maven-central-url")
  Optional<String> mavenCentralUrl;

  /**
   * Name prefix for the per-workspace {@code /workspace} volume — {@code prefix + workspaceId} (the
   * stable {@code workspace_id}, safe as a docker volume name and 1:1 with the branch).
   * Branch/repo/ project ride as labels, not the name, so a rename never strands the volume. See
   * docs/epics/qits-workspaces/features/2026-07-25_persistent-workspace-volume.md.
   */
  @ConfigProperty(name = "qits.workspace.workspace-volume-prefix", defaultValue = "qits_workspace_")
  String workspaceVolumePrefix;

  /**
   * Feature flag: when {@code true} (default), {@code /workspace} is a per-workspace named volume
   * that survives container recreation; when {@code false}, it reverts to the container's ephemeral
   * writable layer (no {@code -v /workspace}, no per-workspace volume lifecycle) — a reversible
   * per-deployment kill switch while the change beds in.
   */
  @ConfigProperty(name = "qits.workspace.persist-workspace", defaultValue = "true")
  boolean persistWorkspace;

  /**
   * The IANA timezone every workspace container runs in ({@code TZ} env, honored by glibc, the JVM
   * and node — tzdata is in the image). Blank/absent (the default) inherits qits' own zone, so
   * wall-clock output in the container (logs, {@code date}, commit timestamps) matches the
   * environment qits runs in — which the devcontainer in turn inherits from the host via compose.
   * Containers already share the host kernel clock; only the rendered zone can differ. Optional
   * because SmallRye treats an empty property value as "no value" and would fail a plain String.
   */
  @ConfigProperty(name = "qits.workspace.timezone")
  Optional<String> timezone;

  /**
   * Memory cap for every workspace container ({@code --memory}). Without it a container sees the
   * whole host's RAM and every JVM inside sizes its default heap against that — a dev-server
   * service (Maven launcher JVM + forked dev JVM + node dev server) can then OOM the entire host
   * (docs/issues/resolved/2026-07-21_workspace-container-unbounded-memory-host-oom.md). With the
   * cgroup limit in place the JVMs size against it automatically (container support is default-on),
   * so no per-tool {@code -Xmx} plumbing is needed. Blank/absent disables the cap; the shipped
   * default is {@code 4g}. Optional because SmallRye treats an empty property value as "no value".
   */
  @ConfigProperty(name = "qits.workspace.memory-limit")
  Optional<String> memoryLimit;

  /**
   * Memory+swap total for every workspace container ({@code --memory-swap}). Docker's semantics:
   * the value INCLUDES the memory cap, so the shipped {@code 4g}/{@code 8g} pair is 4G of RAM plus
   * 4G of swap — headroom that lets a workspace under momentary pressure page instead of taking an
   * OOM kill. Blank/absent grants no swap: the adapter then sends the memory cap for both values,
   * which was this service's only shape before the key existed. Workspace containers are the ONLY
   * platform containers with swap, and this key is why it stays that way — the grant is made here,
   * by the one service that asks for workspace containers, not in qits-containers for every
   * workload.
   */
  @ConfigProperty(name = "qits.workspace.memory-swap-limit")
  Optional<String> memorySwapLimit;

  /**
   * Process/thread cap ({@code --pids-limit}, fork-bomb guard). Blank/absent (default) disables.
   */
  @ConfigProperty(name = "qits.workspace.pids-limit")
  Optional<String> pidsLimit;

  /** CPU cap ({@code --cpus}). Blank/absent (default) disables. */
  @ConfigProperty(name = "qits.workspace.cpus")
  Optional<String> cpus;

  /**
   * OOM score adjustment ({@code --oom-score-adj}). Higher = the host kills this container sooner
   * under memory pressure. 600 sits above the platform services (0) yet is reaped after ci build
   * containers (1000) and agents (800), so a workspace yields before them.
   */
  @ConfigProperty(name = "qits.workspace.oom-score-adj", defaultValue = "600")
  Integer oomScoreAdj;

  /**
   * The provision-time bootstrap kill switch, forwarded to the in-container daemon (which self-runs
   * the chain on boot — docs/epics/qits-workspace-daemon/ Part 3). Mirrors the host-side {@code
   * qits.bootstrap.autorun-enabled} default; when false the daemon skips the chain.
   */
  @ConfigProperty(name = "qits.bootstrap.autorun-enabled", defaultValue = "true")
  boolean bootstrapAutorunEnabled;

  /**
   * The auto-push kill switch, forwarded to the in-container daemon which pushes committed work to
   * origin on its own as it observes commits (docs/epics/qits-workspace-daemon/ bidirectional
   * auto-sync). When false the daemon never auto-pushes; incoming (host-triggered) pulls are
   * unaffected.
   */
  @ConfigProperty(name = "qits.workspace.auto-push.enabled", defaultValue = "true")
  boolean autoPushEnabled;

  /**
   * Service (dev-server) supervision knobs, forwarded to the in-container daemon which supervises
   * them itself (docs/epics/qits-workspace-daemon/ Part 4). Mirror the host-side {@code
   * qits.services.*} so host projection and container supervision agree: the auto-start kill
   * switch, the ready grace (no readyPattern), the restart backoff bounds, and the stop grace.
   */
  @ConfigProperty(name = "qits.services.autostart-enabled", defaultValue = "true")
  boolean servicesAutostartEnabled;

  @ConfigProperty(name = "qits.services.ready-grace-ms", defaultValue = "10000")
  long serviceReadyGraceMs;

  @ConfigProperty(name = "qits.services.restart-backoff-initial-ms", defaultValue = "1000")
  long serviceBackoffInitialMs;

  @ConfigProperty(name = "qits.services.restart-backoff-max-ms", defaultValue = "30000")
  long serviceBackoffMaxMs;

  @ConfigProperty(name = "qits.services.stop-grace-ms", defaultValue = "5000")
  long serviceStopGraceMs;

  @Inject GitIdentity gitIdentity;

  /**
   * Resolves the repository's project-scoped git-host name so the in-container workspace-daemon can
   * self-clone name-addressed ({@code /git/<projectId>/<name>}) — the addressing that lets
   * committed relative submodule urls resolve natively (docs/epics/qits-workspace-daemon/ Part 1).
   * Injected as {@code QITS_WORKSPACE_DAEMON_PROJECT_ID}/{@code …_REPO_NAME}.
   *
   * <p>Optional: the ordinary production path reads the same pair from {@link RepositoryLookup}; a
   * resolver can override it for an embedding that owns a different address registry.
   */
  @Inject Instance<RepositoryAddressResolver> nameResolver;

  /**
   * The owning project id, for {@code QITS_WORKSPACE_DAEMON_PROJECT_ID} and the {@code
   * qits.project} labels. No {@link RepositoryAddressResolver} implementation exists in the
   * deployable, so {@link RepositoryLookup} supplies both this id and the repository name. An
   * {@code Instance<>} lets the hand-built unit-test factory leave the registry empty.
   */
  @Inject Instance<RepositoryLookup> repositories;

  /**
   * The credential this workspace's container holds toward the platform, injected as {@code
   * QITS_COMMISSIONED_CLIENT_ID}/{@code …_SECRET} below. A lookup rather than an argument, and
   * optional rather than required — {@link WorkspaceCredentials} carries both reasons.
   */
  @Inject Instance<WorkspaceCredentials> credentials;

  /**
   * Whether this workspace is the admin kind — the one input that changes what the container is
   * allowed. A lookup rather than an argument, for the reason {@link WorkspacePostures} spells out,
   * and optional for the reason above it: absent means no admin workspace exists, which is the
   * answer an ordinary workspace gets anyway.
   */
  @Inject Instance<WorkspacePostures> postures;

  /** The repo's project-scoped name, from an override resolver or the repository registry. */
  private Optional<RepositoryAddressResolver.ProjectScopedName> scopedName(String repoId) {
    if (nameResolver.isResolvable()) {
      Optional<RepositoryAddressResolver.ProjectScopedName> resolved =
          nameResolver.get().resolve(repoId);
      if (resolved.isPresent()) {
        return resolved;
      }
    }
    if (!repositories.isResolvable()) {
      return Optional.empty();
    }
    try {
      return repositories
          .get()
          .find(repoId)
          .filter(view -> present(view.projectId()) && present(view.name()))
          .map(
              view ->
                  new RepositoryAddressResolver.ProjectScopedName(
                      view.projectId(), view.name()));
    } catch (RuntimeException e) {
      return Optional.empty(); // address enrichment never prevents container creation
    }
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * The commissioned pair for this workspace, or none. A lookup failure costs the credential and
   * never the container: by the time this runs the provision has already decided a credential is in
   * hand (or that there is none to have), and a read that stumbles here must not turn a resume into
   * a failed launch. A blank half is no credential — see the env block for why never half a pair.
   */
  /**
   * Whether this workspace's container is the admin kind. <b>Every failure direction is false</b>:
   * no port wired, no row, a read that stumbled. A privilege that could be acquired by a lookup
   * going wrong would be a privilege nobody granted — unlike the credential above, where an absence
   * costs the container something it was meant to have, an absence here costs it something it was
   * meant not to have.
   */
  private boolean adminWorkspace(Long rowId) {
    if (rowId == null || !postures.isResolvable()) {
      return false;
    }
    try {
      return postures.get().isAdmin(rowId);
    } catch (RuntimeException e) {
      LOG.warnf(e, "could not read the posture of workspace %s; launching it without the socket", rowId);
      return false;
    }
  }

  /**
   * Whether this workspace is <b>the editor</b> — the one shared editor container the platform
   * opens. <b>Every failure direction is false</b>, exactly as above, and here it means something
   * different from a lost privilege: a false answer for the row that really is the editor describes
   * a plain-image container, and a spec that differs from what is running is a {@code
   * Recreate.ifChanged} replacement. That is why the shipped posture reads a column
   * ({@code PersistedWorkspacePostures}) — a local read that gives the same answer at every ensure,
   * so the falling below is the last resort rather than the ordinary path.
   */
  private boolean editorWorkspace(Long rowId) {
    if (rowId == null || !postures.isResolvable()) {
      return false;
    }
    try {
      return postures.get().isEditor(rowId);
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "could not read the posture of workspace %s; launching it as an ordinary workspace with no"
              + " editor",
          rowId);
      return false;
    }
  }

  private Optional<WorkspaceCredential> workspaceCredential(Long rowId) {
    if (!credentials.isResolvable()) {
      return Optional.empty();
    }
    try {
      return credentials
          .get()
          .forWorkspace(rowId)
          .filter(pair -> present(pair.clientId()) && present(pair.secret()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * The repo's owning project id: the scoped name's when a name resolver answers, else {@link
   * RepositoryLookup}'s, else blank. Failures resolve to blank rather than failing the container —
   * the id is enrichment (labels, the daemon's MCP scoping), never a provisioning gate.
   */
  private String projectIdFor(String repoId) {
    Optional<String> scoped =
        scopedName(repoId).map(RepositoryAddressResolver.ProjectScopedName::projectId);
    if (scoped.isPresent()) {
      return scoped.get();
    }
    if (!repositories.isResolvable()) {
      return "";
    }
    try {
      return repositories
          .get()
          .find(repoId)
          .map(RepositoryLookup.RepositoryView::projectId)
          .orElse("");
    } catch (RuntimeException e) {
      return ""; // an unreachable registry costs the id, never the container
    }
  }

  /**
   * Resolves the address a container uses to reach qits — the same host {@code workspace-daemon}
   * dials for its control socket that git/OTLP/MCP already use ({@link QitsHostResolver}). Injected
   * here so {@code forWorkspace} can compose the dial-home URL as container env, since {@code
   * workspace-daemon} runs in-container and cannot call the resolver itself.
   */
  @Inject QitsHostResolver qitsHostResolver;

  /**
   * The qits HTTP port containers connect to (git/OTLP/MCP and now the workspace-daemon control
   * socket).
   */
  @ConfigProperty(name = "qits.workspace.qits-port", defaultValue = "8080")
  String qitsPort;

  /** The edge address containers use for Git; direct githost traffic is Bearer-only. */
  @ConfigProperty(name = "qits.workspace.container-git-url", defaultValue = "http://qits-platform-edge:8080")
  String containerGitUrl;

  /**
   * The audience a container's Git helper AND the daemon's dial-home control socket bearer both
   * request: the one platform audience (service-client-identity-plan.md, C4), never a
   * qits-githost-specific or an environment-qualified one. Every receiver on this platform accepts
   * it and nothing else, and judges the caller on its roles from there. A literal rather than a
   * config key: the value is the platform's own name, the same string in every service, so there is
   * nothing here for a deployment to state.
   */
  static final String CONTAINER_TOKEN_AUDIENCE = "qits-platform";

  /** Reuse the one IdP authority this service already uses for its own machine client. */
  @ConfigProperty(name = "quarkus.oidc-client.qits.auth-server-url")
  String idpUrl;

  /**
   * The bearer every container's {@code WorkspaceApi} requires — injected as the fifteenth {@code
   * QITS_WORKSPACE_DAEMON_*} var. Read the config key's comment before treating it as a secret.
   */
  @ConfigProperty(name = "qits.workspace.daemon-api-token", defaultValue = "qits-workspace-daemon")
  String daemonApiToken;

  static final String MAVEN_MOUNT = "/caches/m2";
  static final String PNPM_MOUNT = "/caches/pnpm";

  /**
   * The fully qualified, version-pinned workspace image reference: {@code <repo>:<version>}. Composed
   * rather than stored so the version half can be overridden at runtime by an env var while the
   * registry host and path stay committed; the result is byte-identical in shape to the old single
   * key ({@code registry.dev.localhost:8080/qits/workspace:<calver>}). Exposed rather than read from
   * config a second time, so the reference the spec carries and the reference anything else names
   * cannot be two values. Who pulls it is the orchestrator, under pull policy MISSING — the
   * inspect-then-pull this service used to do itself went with the docker socket.
   */
  public String image() {
    return imageRepo + ":" + imageVersion();
  }

  /**
   * The fully qualified, version-pinned <b>editor</b> image reference: {@code <repo>:<version>},
   * composed for the reason {@link #image()} is composed. It is what the one editor workspace runs
   * and what every other workspace does not — see {@link #editorWorkspace}.
   */
  public String editorImage() {
    return editorImageRepo + ":" + editorImageVersion();
  }

  /**
   * The two halves of the workspace reference, readable apart from the reference {@link #image()}
   * joins them into. They exist for the launch-pin route ({@code GET /workspaces/api/pins}), which
   * has to name the repository and the tag separately and must not re-declare either config key: the
   * image a pin names and the image a launch pulls are the same value or the pin is worthless.
   * Splitting {@link #image()} back on a colon would not do — the repo half carries the registry's
   * own {@code host:port}.
   */
  public String imageRepo() {
    return imageRepo;
  }

  /**
   * The workspace image's calver tag — <b>the pinned dependency's version, unless an operator has
   * deliberately overridden it</b>; see {@link #imageRepo()} for why the two halves are readable
   * apart, and {@link #imageVersionOverride} for why the pin is the default and not the other way
   * round.
   *
   * <p>One method rather than a field resolved at injection, because {@code GET /workspaces/api/pins}
   * and every launch have to give the same answer, and a second read of the config key somewhere
   * else is exactly how they would stop doing so.
   */
  public String imageVersion() {
    return imageVersionOverride.filter(value -> !value.isBlank()).orElse(WorkspaceImage.VERSION);
  }

  /** The editor image's registry host and path; see {@link #imageRepo()}. */
  public String editorImageRepo() {
    return editorImageRepo;
  }

  /** The editor image's calver tag; see {@link #imageVersion()}, which it mirrors exactly. */
  public String editorImageVersion() {
    return editorImageVersionOverride
        .filter(value -> !value.isBlank())
        .orElse(WorkspaceEditorImage.VERSION);
  }

  /**
   * The shared credential volume name (blank when the mount is disabled). The orchestrator creates
   * it and the other two at its own boot; this service only names them, so that the adapter can tell
   * a platform volume from the workspace's own when it builds the spec.
   */
  public String claudeVolume() {
    return claudeVolume;
  }

  /** The shared Maven-repo volume name (blank when disabled). The orchestrator creates it. */
  public String mavenVolume() {
    return mavenVolume;
  }

  /** The shared pnpm-store volume name (blank when disabled). The orchestrator creates it. */
  public String pnpmVolume() {
    return pnpmVolume;
  }

  /** The shared network name. The bootstrap creates it; the orchestrator only probes for it. */
  public String network() {
    return network;
  }

  /** Whether {@code /workspace} is a persistent per-workspace volume (vs. the ephemeral layer). */
  public boolean persistWorkspace() {
    return persistWorkspace;
  }

  /**
   * The deterministic per-workspace {@code /workspace} volume name — {@code prefix + workspaceId}.
   */
  public String workspaceVolumeName(String workspaceId) {
    return workspaceVolumePrefix + workspaceId;
  }

  /**
   * The {@code qits.*} labels a per-workspace {@code /workspace} volume carries — {@code
   * qits.managed=workspace-volume} (the reconcile filter) plus {@code qits.project} (resolved from
   * the repo via {@link RepositoryAddressResolver}) and the same repo/workspace/branch/parent identity
   * the container labels carry, so a dangling volume is human-readable and matchable to its row.
   * Ordered (LinkedHashMap) only for stable argv/log output.
   */
  public java.util.Map<String, String> workspaceVolumeLabels(
      String repoId, String workspaceId, String branch, String parent) {
    java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
    labels.put("qits.managed", "workspace-volume");
    labels.put("qits.project", resolveProjectId(repoId));
    labels.put("qits.repository", repoId);
    labels.put("qits.workspace", workspaceId);
    labels.put("qits.branch", branch == null ? "" : branch);
    labels.put("qits.parent", parent == null ? "" : parent);
    return labels;
  }

  /**
   * The project id a repo belongs to (blank when unresolved), for the {@code qits.project} label.
   */
  private String resolveProjectId(String repoId) {
    return projectIdFor(repoId);
  }

  /**
   * A {@link WorkspaceContainer} seeded for {@code workspaceId} of {@code repoId}: its
   * deterministic name, the host uid, the four {@code qits.*} labels startup reconciliation reads
   * back, the {@code host.docker.internal} alias Linux needs, the shared {@code qits-net} network,
   * the configured git commit identity as {@code GIT_*} env ({@link GitIdentity}), the shared
   * credential + build-cache volumes (whenever configured), the configured resource limits (memory
   * cap, memory+swap total, pids, cpus — whenever configured), the image, and the {@code qits-workspace-daemon}
   * dial-home env, and the commissioned platform credential when the workspace holds one. The
   * container's process is {@code qits-workspace-daemon} via the image
   * ENTRYPOINT (no command is appended) — with no {@code sleep infinity}
   * fallback, so a container that can't run the daemon fails to start rather than lingering
   * unmanaged. Everything safety-critical is already in place; the caller may keep chaining but
   * need not.
   */
  public WorkspaceContainer forWorkspace(
      String repoId, String workspaceId, Long rowId, String branch, String parent) {
    // Asked ONCE, at the top, and carried on the description: it decides the image, two environment
    // variables and — through WorkspaceContainer.editor — the lifetime policy the adapter asks for.
    // Three consequences of one fact, so one read of it.
    boolean editor = editorWorkspace(rowId);
    WorkspaceContainer container =
        new WorkspaceContainer()
            .name(containerName(workspaceId, repoId))
            .user(Long.toString(hostUid()))
            .label("qits.repository", repoId)
            .label("qits.workspace", workspaceId)
            .label("qits.branch", branch == null ? "" : branch)
            .label("qits.parent", parent == null ? "" : parent)
            // Linux needs this for host.docker.internal to resolve to the docker bridge gateway;
            // qits
            // controls container creation, so it is always set.
            .addHost("host.docker.internal:host-gateway")
            // Join the shared network so qits reaches the container's ports by DNS name (no -p).
            .network(network)
            // Same timezone as qits (host -> devcontainer -> workspace container), so wall-clock
            // output agrees everywhere. The kernel clock is shared already; TZ is the only delta.
            .env("TZ", timezone());
    // workspace-daemon's dial-home coordinates + identity, as container env
    // (QITS_WORKSPACE_DAEMON_* -> the binary's
    // qits.workspace-daemon.* config). workspace-daemon is the container's process (below) and runs
    // in-container, so
    // it can't call QitsHostResolver — the URL is composed here from the same host/port
    // git/OTLP/MCP
    // use. Labels carry the same identity for host-side reconciliation, but labels aren't env, so
    // we
    // set it explicitly. A container whose workspace-daemon can't reach qits stays alive and idle
    // (the
    // binary
    // never exits on a failed dial), so this is behaviour-neutral (docs/epics/qits-workspace-daemon/).
    container.env(
        "QITS_WORKSPACE_DAEMON_URL",
        "ws://"
            + qitsHostResolver.qitsHost()
            + ":"
            + qitsPort
            + "/workspaces/daemon/"
            + rowId);
    // MCP servers are owned by sibling services, not by the control-socket authority above.
    // Tell the daemon each address outright so a direct qits-workspaces control socket is never
    // mistaken for a gateway. The daemon adds the project/repository/workspace query scope later.
    container.env("QITS_REPOSITORY_MCP_URL", serviceBase(projectsUrl) + "/projects/mcp");
    container.env(
        "QITS_OBSERVABILITY_MCP_URL", serviceBase(observabilityUrl) + "/observability/mcp");
    // The git base the daemon self-clones from, told outright — never derived. The daemon's
    // fallback derives the pre-split address (/artifacts/git off the dial-home authority) and
    // 404s on a platform whose git host is qits-githost: the first real workspace on the
    // 2026-08-15 bare-server platform failed exactly there. /git is the githost's root-level
    // prefix, verbatim through the gateway, so the one authority above routes it too.
    container.env(
        "QITS_WORKSPACE_DAEMON_GIT_BASE_URL",
        gitBase(containerGitUrl));
    // The path ContainerProxyRoute addresses this container at. The proxy forwards a caller's path
    // untouched, so the daemon has to be told which leading part of it is its own address rather
    // than a route it serves — the same arrangement a spawned dev server has with QITS_PUBLIC_BASE,
    // and the reason neither hop has to rewrite anything. Injected from ContainerProxyPath so the
    // literal is spelled once: the route and the container's idea of the route cannot drift.
    container.env("QITS_WORKSPACE_DAEMON_API_BASE_PATH", ContainerProxyPath.base(rowId));
    // The per-workspace half of every web-viewable service's public base. The daemon spawns the
    // dev servers, so it is the daemon that must bake QITS_PUBLIC_BASE (= this base + the declared
    // service id + the declared web-view base-path) into each service's environment — on every
    // spawn, crash-restart included (N3: nothing told the respawned dev server its base, and the
    // verbatim service proxy 404'd the framed view). Same told-never-derived arrangement as the
    // API base path above.
    container.env("QITS_WORKSPACE_DAEMON_SERVICE_PROXY_BASE", ServiceProxyPath.PREFIX + rowId);
    container.env("QITS_WORKSPACE_DAEMON_WORKSPACE_ID", workspaceId);
    // THE EDITOR BELONGS TO NO REPOSITORY, so it is told about none. Every name below is what the
    // in-container daemon self-clones from — repository id, project-scoped name, branch, parent —
    // and the editor's row carries a SENTINEL repository id (EditorWorkspace.REPOSITORY_ID) and no
    // branch at all. Handing the daemon that sentinel would send it to clone `/git/editor`, which is
    // nothing, and the failure would be a provision that never completes rather than a container
    // with an empty /workspace. So the five names are written BLANK, which is the same value a
    // repository the registry could not resolve already produces, and the two lookups behind them
    // are not made: there is nothing to resolve and the sentinel would cost a round trip per ensure
    // to learn that.
    //
    // WHAT THIS ONE CONTAINER NOW REACHES IS AN ACCEPTED CONSEQUENCE, decided deliberately by the
    // epic and recorded here because it is the kind of thing that must not be rediscovered. It holds
    // an ordinary `qits:agent` workspace credential — the same one every workspace container gets,
    // no new client and no new audience — but it is no longer one project's container: everybody
    // opens THIS one, so an unattended agent inside it acts on the whole platform rather than on the
    // project whose page somebody came in through. Its commission is unscoped for the same reason
    // the blanks above are blank (no repository ⇒ no project claim), so it READS every project.
    //
    // Cloning every project's wrapper side by side inside it is a LATER task and is deliberately not
    // done here — which is also why the row's stated Git refs are empty today and why this block is
    // a clean seam rather than a special case: that task sets what is cloned and widens what may be
    // pushed, and nothing else here has to move.
    container.env("QITS_WORKSPACE_DAEMON_REPOSITORY_ID", editor ? "" : repoId);
    container.env("QITS_WORKSPACE_DAEMON_BRANCH", editor || branch == null ? "" : branch);
    container.env("QITS_WORKSPACE_DAEMON_PARENT", editor || parent == null ? "" : parent);
    // The project-scoped name the daemon self-clones under (/git/<projectId>/<name>), so committed
    // relative submodule urls resolve natively in-container. Blank when the repo has no project —
    // the
    // daemon then id-addresses (/git/<repositoryId>), mirroring cloneUrl's fallback.
    Optional<RepositoryAddressResolver.ProjectScopedName> scopedName =
        editor ? Optional.empty() : scopedName(repoId);
    // The owning project id, also as a label so it mirrors the per-workspace volume's qits.project
    // (the volume labels carry it for dangling-volume reconcile; the container carries it for
    // symmetry). Resolved through projectIdFor — the RepositoryLookup fallback is what stopped
    // this env var from shipping empty (D2). Blank only when no registry answers — and for the
    // editor, which owns no project the way it owns no repository.
    String projectId =
        editor
            ? ""
            : scopedName
                .map(RepositoryAddressResolver.ProjectScopedName::projectId)
                .orElseGet(() -> projectIdFor(repoId));
    container.label("qits.project", projectId);
    container.env("QITS_WORKSPACE_DAEMON_PROJECT_ID", projectId);
    container.env(
        "QITS_WORKSPACE_DAEMON_REPO_NAME",
        scopedName.map(RepositoryAddressResolver.ProjectScopedName::name).orElse(""));
    // The bootstrap kill switch the daemon honours when it self-runs the chain on boot (Part 3).
    container.env(
        "QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN", String.valueOf(bootstrapAutorunEnabled));
    // The auto-push kill switch the daemon honours when it pushes committed work on its own
    // (docs/epics/qits-workspace-daemon/ bidirectional auto-sync).
    container.env("QITS_WORKSPACE_DAEMON_AUTO_PUSH_ENABLED", String.valueOf(autoPushEnabled));
    // Service (dev-server) supervision, self-run by the daemon as the boot-sequence tail (Part 4):
    // the auto-start kill switch + the knobs the in-container ServiceSupervisor honours.
    container.env(
        "QITS_WORKSPACE_DAEMON_SERVICES_AUTOSTART", String.valueOf(servicesAutostartEnabled));
    container.env(
        "QITS_WORKSPACE_DAEMON_SERVICE_READY_GRACE_MS", String.valueOf(serviceReadyGraceMs));
    container.env(
        "QITS_WORKSPACE_DAEMON_SERVICE_RESTART_BACKOFF_INITIAL_MS",
        String.valueOf(serviceBackoffInitialMs));
    container.env(
        "QITS_WORKSPACE_DAEMON_SERVICE_RESTART_BACKOFF_MAX_MS",
        String.valueOf(serviceBackoffMaxMs));
    container.env(
        "QITS_WORKSPACE_DAEMON_SERVICE_STOP_GRACE_MS", String.valueOf(serviceStopGraceMs));
    // The bearer the daemon's HTTP API requires. Without it WorkspaceApi does not bind at all —
    // fail-closed, because an omitted env is indistinguishable from a misconfiguration and serving
    // an untrusted checkout anonymously across the docker network would be silent. That is why this
    // is injected rather than the daemon's precondition being relaxed: the isolation already exists
    // and removing it to serve a host-side convenience would be the wrong repo for the decision.
    //
    // One shared value with a default, so a deployment needs no configuration. See the config key's
    // own comment for what it is NOT: it is a handshake constant, not a boundary.
    container.env("QITS_WORKSPACE_DAEMON_API_TOKEN", daemonApiToken);
    // The credential this container holds toward the PLATFORM — the other direction from the token
    // above, which is what the host presents to the daemon. It is an idp client id and secret
    // commissioned for this container alone (WorkspaceService provisions it, CredentialCommissioner
    // mints it), so registry pulls and pushes from inside the workspace authenticate as this
    // workspace rather than as a durable identity shared by everything on the network.
    //
    // Both vars or neither: half a pair is a credential that cannot be presented, and a container
    // launched with one would look configured and fail at the first pull. Absent is the shipped
    // posture and today's behaviour — no issuer wired, no credential on the row, no env here.
    workspaceCredential(rowId)
        .ifPresent(
            credential -> {
              container.env("QITS_COMMISSIONED_CLIENT_ID", credential.clientId());
              container.env("QITS_COMMISSIONED_CLIENT_SECRET", credential.secret());
              // The image's credential helper mints a fresh bearer for each Git authentication.
              // It compares Git's requested authority with this value before it ever exchanges the
              // client secret, so an absolute submodule or an ad-hoc external remote cannot obtain
              // a platform token.
              container.env("GIT_CONFIG_GLOBAL", "/etc/qits-gitconfig");
              container.env("QITS_GIT_AUTH_HOST", gitAuthority(containerGitUrl));
              container.env("QITS_GIT_AUTH_TOKEN_URL", tokenUrl(idpUrl));
              container.env("QITS_GIT_AUTH_AUDIENCE", CONTAINER_TOKEN_AUDIENCE);
              // The same short-lived credential authenticates the daemon's dial-home socket, and —
              // since C4 — asks for the same one platform audience the Git helper above does. Keep
              // the token endpoint and target explicit: deriving either from the Git endpoint would
              // silently put a workspace's control plane behind a different service's policy.
              container.env("QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL", tokenUrl(idpUrl));
              container.env("QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE", CONTAINER_TOKEN_AUDIENCE);
            });
    // THE WEB EDITOR, and only for the one row that IS it. The daemon supervises
    // openvscode-server when it is told to; every other workspace is told nothing and behaves
    // exactly as it did before an editor existed — which is also the daemon's own shipped default,
    // so the absence is not a second way of saying the same thing.
    //
    // Both vars or neither, the same rule the credential block above follows and for a related
    // reason: `enabled` without a port would leave the daemon and the host's proxy free to pick
    // different numbers, and a port without `enabled` would name a listener nothing starts. They are
    // written together or not at all.
    //
    // IT RIDES THE SPEC, so it obeys the spec-hash rule: environment is part of the spec, a changed
    // spec is a Recreate.ifChanged REPLACEMENT, and a resume presents the spec again. That is why
    // both values are stable lookups — the posture off the row's own column, the port off config —
    // and never something a caller passed in. See WorkspacePostures.
    if (editor) {
      container.env("QITS_WORKSPACE_DAEMON_EDITOR_ENABLED", "true");
      container.env("QITS_WORKSPACE_DAEMON_EDITOR_PORT", Integer.toString(editorPort));
    }
    // Resource limits (opt-out): without a memory cap, every JVM in the container sizes its heap
    // against the whole host's RAM and a dev server can OOM the host. Blank config disables a cap.
    memoryLimit.filter(v -> !v.isBlank()).ifPresent(container::memory);
    memorySwapLimit.filter(v -> !v.isBlank()).ifPresent(container::memorySwap);
    pidsLimit.filter(v -> !v.isBlank()).ifPresent(container::pidsLimit);
    cpus.filter(v -> !v.isBlank()).ifPresent(container::cpus);
    container.oomScoreAdj(oomScoreAdj);
    // The commit identity, as container-level env so *every* git process in the container — qits'
    // own verbs, the coding agent, actions, ad-hoc shells — inherits it regardless of cwd or
    // .git/config (identity env beats every git config level).
    gitIdentity.envMap().forEach(container::env);
    // The shared credential volume so an in-container `claude` can read the one-time OAuth login.
    // Mounted read/write on every workspace container (agent and daemon share the container), so
    // any
    // command in the container can read the token off the volume — the accepted trade for the
    // shared-login model
    // (docs/epics/qits-coding-agents/features/2026-07-04_container-agent-sessions.md).
    if (claudeVolume != null && !claudeVolume.isBlank()) {
      container.volume(claudeVolume, claudeMount);
      // Point every in-container `claude` at the shared credential dir regardless of HOME. The
      // image
      // sets HOME=/workspace (container-local), so without this a `claude` that doesn't override
      // HOME
      // (an ad-hoc bash `claude`, or any missed code path) would store its login under
      // /workspace/.claude — invisible to other containers. As a container env it is inherited by
      // every `docker exec`, so cross-container persistence no longer relies on each launcher
      // remembering the HOME overlay.
      container.env("CLAUDE_CONFIG_DIR", claudeMount + "/.claude");
      // Same for Kimi Code (the second harness —
      // docs/epics/qits-coding-agents/features/2026-07-20_kimi-code-harness.md):
      // KIMI_CODE_HOME relocates its entire data root (config.toml, credentials, sessions) onto the
      // volume. Without it an in-container kimi would default to ~/.kimi-code =
      // /workspace/.kimi-code
      // (the image's HOME) — the clone, container-local and invisible to every other container.
      container.env("KIMI_CODE_HOME", claudeMount + "/.kimi-code");
    }
    // Shared build caches (Maven repo + pnpm store), the same named volumes qits' devcontainer
    // mounts — so a dependency fetched by one build (a fixture `./mvnw`, an action, the agent, or
    // qits itself) is reused by every other container. Point the tools at the fixed mount paths via
    // env, inherited by every `docker exec` (HOME is /workspace, so the defaults would otherwise
    // land in the clone and never be shared).
    if (mavenVolume != null && !mavenVolume.isBlank()) {
      container.volume(mavenVolume, MAVEN_MOUNT);
      container.env("MAVEN_OPTS", "-Dmaven.repo.local=" + MAVEN_MOUNT);
    }
    if (pnpmVolume != null && !pnpmVolume.isBlank()) {
      container.volume(pnpmVolume, PNPM_MOUNT);
      container.env("npm_config_store_dir", PNPM_MOUNT + "/store");
    }
    // The registries those caches fill FROM. Environment, not a file: npm ranks a project
    // .npmrc above ~/.npmrc, so every SPA's committed .npmrc — which names the deployment host's
    // port — would outrank anything written into HOME, and npm_config_* outranks both. Maven is
    // told the address only; the -s that makes it usable lives in the image, because the settings
    // file is the image's to own and the address is the deployment's.
    mavenRepositoryUrl
        .filter(url -> !url.isBlank())
        .ifPresent(url -> container.env("QITS_MAVEN_REPOSITORY_URL", url));
    // Maven Central through the platform's pull-through cache, the npm proxy's counterpart. The
    // image's settings file already declares the mirror entry and the profile that switches Central
    // over; this address is the only thing it is missing, and while it is missing the profile stays
    // inert and the build resolves repo1.maven.org. Non-blank ⇒ mirrored, blank ⇒ direct.
    mavenCentralUrl
        .filter(url -> !url.isBlank())
        .ifPresent(url -> container.env("QITS_MAVEN_CENTRAL_URL", url));
    npmProxyUrl.filter(url -> !url.isBlank()).ifPresent(url -> container.env("npm_config_registry", url));
    // NOT `npm_config_@qits:registry`, which is npm's own spelling and what this line used to be:
    // qits-containers refuses that name outright (`Invalid environment key`) because its env keys
    // are POSIX-shaped, and it is right to — `@` and `:` are not an environment variable's
    // business. The container spec therefore carries the ADDRESS under a POSIX name and the
    // workspace image's npm shim spells the scope, which is also the only place that outranks the
    // .npmrc every SPA commits. Renaming this breaks that shim silently: keep the two matched.
    npmRegistryUrl
        .filter(url -> !url.isBlank())
        .ifPresent(url -> container.env("QITS_WORKSPACE_NPM_REGISTRY_URL", url));
    // The per-workspace /workspace volume: the workspace's checkout, persisted across container
    // recreation instead of dying with the writable layer. The first mount populates the empty
    // volume from the image's world-writable /workspace (docker copies the image dir's contents AND
    // permissions), so no permission fix is needed under the arbitrary-uid container user — the
    // same
    // reason the shared cache volumes work. It rides the spec as a row-claimed mount, so the
    // orchestrator creates it as part of the same ensure that starts the container. Flag off ⇒ /workspace stays the ephemeral writable layer (today's
    // behavior). See docs/epics/qits-workspaces/features/2026-07-25_persistent-workspace-volume.md.
    if (persistWorkspace) {
      container.volume(workspaceVolumeName(workspaceId), "/workspace");
    }
    // ADMIN MODE: the host's docker socket, for the few workspaces somebody deliberately created
    // that way. It is the one thing about a workspace container that is not the same for every
    // workspace, and it is read off the row rather than passed in — see WorkspacePostures for why
    // the spec has to be reproducible at every ensure, and Workspace.admin for why the posture is
    // decided once, in the request that created the workspace.
    //
    // A container holding this socket is ROOT-EQUIVALENT ON THE HOST: it can start a container that
    // mounts anything, on the host's behalf. That is the whole point (administration is what an
    // admin workspace is for) and it is exactly why nothing here derives it from a config key, a
    // repository, an image or a branch name. The default is no socket, everywhere, and the only
    // way past it is a row that says otherwise.
    //
    // Nothing else about the container changes with it — same user, same limits, same mounts, same
    // image. The socket is usable despite the host uid because qits-containers joins the socket's
    // own group beside the bind (its README's "The docker socket" section); a workspace-side
    // --group-add would be a privilege assembled here rather than granted there.
    if (adminWorkspace(rowId)) {
      container.hostDockerSocket(true);
    }
    // The container runs ONLY the workspace-daemon, via the image ENTRYPOINT
    // (docker/qits/Dockerfile),
    // so qits puts no command on the spec at all and the image alone boots the control plane.
    // There is deliberately NO `sleep infinity` fallback: a container that can't run the daemon
    // must
    // FAIL to start rather than linger. A daemon-less container never sends HELLO, so qits has no
    // control plane to it — after the epic it would be an unmanaged shadow holding qits' uid,
    // mounts
    // and network. So "no daemon ⇒ no workspace": a stale image (built before this change) exits
    // loudly, surfacing that the image must be rebuilt. `--init` (WorkspaceContainer.toRunArgv)
    // puts
    // tini at PID 1, so the daemon is tini's child (tini reaps zombies + forwards signals); the
    // daemon
    // holds the socket open and is otherwise idle in Part 1. `init` is a spec field now rather
    // than a run flag, and it is what keeps a long-lived daemon from collecting its children.
    //
    // THE IMAGE IS THE LAST THING DECIDED AND THE FIRST THING THE ORCHESTRATOR READS. The editor's
    // is a child of this one — `qits/workspace-editor` is FROM `qits/workspace` plus one directory —
    // so the editor is an ordinary workspace container that happens to carry
    // openvscode-server, and everything above it is identical. Which one a container runs is
    // answered before it exists rather than by a flag inside it, which is why the editor is a second
    // image at all.
    return container.editor(editor).image(editor ? editorImage() : image());
  }

  private static String serviceBase(String configured) {
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException("service URL must not be blank");
    }
    String value = configured.trim();
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private static String tokenUrl(String idpBase) {
    return idpBase.replaceAll("/+$", "") + "/token";
  }

  private static String gitAuthority(String gitBase) {
    try {
      URI uri = URI.create(gitBase);
      if (uri.getScheme() == null || uri.getRawAuthority() == null || uri.getUserInfo() != null) {
        throw new IllegalArgumentException("not an absolute git host URL");
      }
      return uri.getRawAuthority();
    } catch (RuntimeException badUrl) {
      throw new IllegalStateException("qits.githost.url must be an absolute URL", badUrl);
    }
  }

  private static String gitBase(String base) {
    return base.replaceAll("/+$", "") + "/git";
  }

  /**
   * The deterministic container name for a workspace — mirrors {@link
   * ContainerRuntime#containerName}. The short repo prefix keeps the name readable and well under
   * docker's length cap while staying effectively unique per repo.
   */
  private String containerName(String workspaceId, String repoId) {
    String shortRepo = repoId.length() > 8 ? repoId.substring(0, 8) : repoId;
    return "qits-ws-" + workspaceId + "-" + shortRepo;
  }

  /** The configured zone, or qits' own default zone when blank ({@code TZ}-aware via the JVM). */
  private String timezone() {
    return timezone.filter(tz -> !tz.isBlank()).orElseGet(() -> ZoneId.systemDefault().getId());
  }

  /**
   * The host uid the container runs as, so cloned {@code /workspace} files are owned by the user.
   */
  private long hostUid() {
    try {
      Object uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid");
      return ((Number) uid).longValue();
    } catch (Exception e) {
      // Fall back to a sane default; the container just won't match the host uid.
      return 1000L;
    }
  }
}
