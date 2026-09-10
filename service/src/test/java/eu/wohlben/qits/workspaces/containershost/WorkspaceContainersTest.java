package eu.wohlben.qits.workspaces.containershost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.PolicyType;
import eu.wohlben.qits.containers.client.ContainersWire.PullPolicy;
import eu.wohlben.qits.containers.client.ContainersWire.Recreate;
import eu.wohlben.qits.containers.client.ContainersWire.Security;
import eu.wohlben.qits.containers.client.ContainersWire.SharedMount;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeMount;
import eu.wohlben.qits.workspaces.control.ContainerRuntime;
import eu.wohlben.qits.workspaces.control.ProxyOrigin;
import eu.wohlben.qits.workspaces.control.TestWorkspaceContainerFactory;
import eu.wohlben.qits.workspaces.control.WorkspaceContainerFactory;
import eu.wohlben.qits.workspaces.error.InternalServerErrorException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The whole of what qits-workspaces asks qits-containers for, against a stub that answers on a real
 * socket.
 *
 * <p><b>Plain objects and no CDI</b>, the way qits-ci proves its own launcher: the adapter is
 * constructed by hand with a client pointed at {@link StubContainersServer}, so what is under test
 * is this class's reading of the four answers rather than an application's wiring. The
 * {@code @QuarkusTest}s cannot see any of it — {@code FakeContainerRuntime} replaces this adapter
 * wholesale there, which is exactly why this file has to exist.
 *
 * <p><b>The request is asserted literally.</b> Several fields on the spec are load-bearing enough
 * that one lost in a refactor is a behaviour change nobody would notice until a workspace
 * misbehaved: the absent cap-drop (a dev environment must keep {@code su} and {@code chown}), the
 * {@code init} that stops a long-lived daemon collecting zombies, the shared-versus-claimed split
 * across the four volumes, and the explicit name that keeps {@code docker ps} readable.
 */
class WorkspaceContainersTest {

  private static final String OWNER = "dev-qits-workspaces";
  private static final String REPO = "repo12345678abc";

  /** A live envelope body, as the service answers about a place that is up. */
  private static final String RUNNING =
      envelope("qits-ws-work-repo1234", "RUNNING");

  private StubContainersServer stub;

  @BeforeEach
  void start() throws IOException {
    stub = new StubContainersServer();
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  private static String envelope(String name, String observed) {
    return "{\"id\":\"6f1b3d2e-0000-4000-8000-000000000001\",\"containerName\":\""
        + name
        + "\",\"state\":{\"desired\":\"RUNNING\",\"observed\":\""
        + observed
        + "\"},\"endpoint\":null,\"specHash\":\"h1\",\"created\":false,\"detail\":null}";
  }

  private WorkspaceContainers adapter() {
    return adapter(TestWorkspaceContainerFactory.persistent());
  }

  private WorkspaceContainers adapter(WorkspaceContainerFactory factory) {
    return adapter(factory, Optional.empty());
  }

  private WorkspaceContainers adapter(
      WorkspaceContainerFactory factory, Optional<Duration> idleStopAfter) {
    WorkspaceContainers containers = new WorkspaceContainers();
    containers.containerFactory = factory;
    containers.owner = OWNER;
    // Blank is the shipped value: nothing is idle-stopped and every workspace keeps EXPLICIT.
    containers.editorIdleStopAfter = idleStopAfter;
    // One second, so the retry pause clamps to it (the adapter never pauses past its own window) and
    // a test about holding through a 401 costs a second rather than five.
    containers.launchPatience = Duration.ofSeconds(1);
    containers.containers =
        new ContainersClient(
            stub.url(), Duration.ofSeconds(5), Duration.ofSeconds(5), Optional::empty);
    return containers;
  }

  // --- the spec ---------------------------------------------------------------------------------

  @Test
  void theEnsureRequestCarriesTheWholeWorkspaceSpec() {
    EnsureRequest request = adapter().ensureRequest(REPO, "work", 1L, "main", "0parent");
    Spec spec = request.spec();

    assertEquals(TestWorkspaceContainerFactory.IMAGE, spec.image());
    // The image's qits-workspace-daemon ENTRYPOINT is the whole process: no entrypoint override and
    // no command, so a container that cannot run the daemon fails to start rather than lingering.
    assertNull(spec.entrypoint());
    assertNull(spec.args());
    assertEquals("qits-net", spec.network());
    // The name this service composed travels as the explicit name, so `docker ps` reads as it did
    // and the ref and the name are the same string wherever the name is already a legal ref.
    assertEquals("qits-ws-work-repo1234", spec.explicitName());
    assertEquals(List.of("host.docker.internal:host-gateway"), spec.addHosts());
    assertTrue(spec.user().matches("\\d+"), spec.user());
    // tini as PID 1: the daemon spawns bootstrap steps, dev servers and agent sessions, and a PID 1
    // that reaps nothing would collect their orphans for as long as the workspace runs.
    assertEquals(Boolean.TRUE, spec.init());
    // The pin is a version, so what is local under that name IS the release — the inspect-then-pull
    // this service used to do itself, enforced one hop out.
    assertEquals(PullPolicy.MISSING, spec.pullPolicy());
    // An ordinary workspace container never gets the host's docker socket — only a workspace whose
    // row says admin does, and this one's does not.
    assertFalse(spec.hostDockerSocket());
    assertNull(spec.aliases());

    // The qits.* identity labels, as human hints. They select nothing — the registry answers "whose
    // container is this" — and they are what a person reading `docker inspect` has to go on.
    assertEquals(REPO, spec.extraLabels().get("qits.repository"));
    assertEquals("work", spec.extraLabels().get("qits.workspace"));
    assertEquals("main", spec.extraLabels().get("qits.branch"));
    assertEquals("0parent", spec.extraLabels().get("qits.parent"));
    assertEquals("proj-1", spec.extraLabels().get("qits.project"));

    // The daemon's dial-home coordinates reach it as environment, because it needs all of it before
    // a socket exists. The order is the factory's insertion order, which is what keeps this
    // assertable at all.
    assertEquals(
        "ws://qits:8080/workspaces/daemon/1", spec.env().get("QITS_WORKSPACE_DAEMON_URL"));
    assertEquals(
        "/workspaces/container/1/", spec.env().get("QITS_WORKSPACE_DAEMON_API_BASE_PATH"));
    assertEquals("work", spec.env().get("QITS_WORKSPACE_DAEMON_WORKSPACE_ID"));
    assertEquals(REPO, spec.env().get("QITS_WORKSPACE_DAEMON_REPOSITORY_ID"));
    assertEquals("main", spec.env().get("QITS_WORKSPACE_DAEMON_BRANCH"));
    assertEquals("/claude-home/.claude", spec.env().get("CLAUDE_CONFIG_DIR"));
    assertEquals("-Dmaven.repo.local=/caches/m2", spec.env().get("MAVEN_OPTS"));
    // The Maven Central pull-through travels on the SPEC, which is the half that matters here:
    // environment is part of it, so adding this variable is a Recreate.ifChanged replacement for
    // every container already running — acceptable because /workspace is a volume, and only correct
    // because the value is a constant off config that reads the same at every ensure.
    assertEquals(
        TestWorkspaceContainerFactory.MAVEN_CENTRAL_URL, spec.env().get("QITS_MAVEN_CENTRAL_URL"));

    // EXPLICIT: a workspace lives until somebody says otherwise and only a delete ends it. It is
    // also what makes a changed spec recreatable rather than a SPEC_CONFLICT.
    assertEquals(PolicyType.EXPLICIT, request.policy().type());
    assertNull(request.policy().idleAfterSeconds());
    assertNull(request.policy().maxAgeSeconds());
    // The release train moves the image pin, so a workspace whose spec no longer matches what is
    // running must be replaced rather than silently left on the old image with a 200 saying so.
    assertEquals(Recreate.ifChanged, request.recreate());
  }

  @Test
  void aWorkspaceWithADocumentCarriesItOnTheSpecAndChangesNothingElse() {
    Spec none = adapter().ensureRequest(REPO, "work", 1L, "main", "0parent").spec();
    Spec configured =
        adapter(TestWorkspaceContainerFactory.configured())
            .ensureRequest(REPO, "work", 1L, "main", "0parent")
            .spec();

    // Nothing where there is no document: a container created before this shipped, or one whose
    // fetch failed, is told nothing at all and runs on the harness library's shipped constants.
    // Silence is what the library reads as "no document"; an empty value would be a second way of
    // saying it and a path naming a file nothing wrote would fail the daemon at boot.
    assertNull(none.env().get("QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION"));
    assertNull(none.env().get("QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION_PATH"));

    // And the document byte for byte where there is one, beside the path the daemon materializes it
    // at. It travels on the SPEC — the only channel the orchestrator's wire has, which admits named
    // volumes and the docker socket and no host path at all — so it obeys the spec-hash rule: this
    // is a Recreate.ifChanged replacement for every container already running, which is why the
    // value is read off the row rather than fetched per call. A fetch here would carry a fresh
    // generatedAt and replace every workspace's container at every ensure.
    assertEquals(
        TestWorkspaceContainerFactory.AGENT_CONFIGURATION_DOCUMENT,
        configured.env().get("QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION"));
    assertEquals(
        TestWorkspaceContainerFactory.AGENT_CONFIGURATION_PATH,
        configured.env().get("QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION_PATH"));

    // …and that is the WHOLE difference, asserted the way the two postures' are: the configured
    // spec with the two variables taken back out is the plain one.
    java.util.Map<String, String> env = new java.util.LinkedHashMap<>(configured.env());
    env.remove("QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION");
    env.remove("QITS_WORKSPACE_DAEMON_AGENT_CONFIGURATION_PATH");
    assertEquals(
        none,
        new Spec(
            configured.image(),
            configured.entrypoint(),
            configured.args(),
            env,
            configured.extraLabels(),
            configured.network(),
            configured.aliases(),
            configured.addHosts(),
            configured.volumeMounts(),
            configured.sharedMounts(),
            configured.hostDockerSocket(),
            configured.security(),
            configured.pullPolicy(),
            configured.explicitName(),
            configured.user(),
            configured.init()));
  }

  @Test
  void theAdminPostureAddsTheSocketAndChangesNothingElse() {
    Spec ordinary = adapter().ensureRequest(REPO, "work", 1L, "main", "0parent").spec();
    Spec admin =
        adapter(TestWorkspaceContainerFactory.admin())
            .ensureRequest(REPO, "work", 1L, "main", "0parent")
            .spec();

    assertFalse(ordinary.hostDockerSocket());
    assertTrue(admin.hostDockerSocket());

    // …and that is the WHOLE difference. An admin workspace is an ordinary workspace holding the
    // host's docker socket: same image, same user, same limits, same mounts, same environment. A
    // posture that quietly relaxed something else would be a privilege nobody asked for riding
    // along with the one somebody did — so the claim is made as the spec with the socket taken back
    // out, which fails on any other field that moved.
    Spec adminWithoutTheSocket =
        new Spec(
            admin.image(),
            admin.entrypoint(),
            admin.args(),
            admin.env(),
            admin.extraLabels(),
            admin.network(),
            admin.aliases(),
            admin.addHosts(),
            admin.volumeMounts(),
            admin.sharedMounts(),
            false,
            admin.security(),
            admin.pullPolicy(),
            admin.explicitName(),
            admin.user(),
            admin.init());
    assertEquals(ordinary, adminWithoutTheSocket);
  }

  @Test
  void theEditorPostureChangesTheImageAndTheDaemonsEditorEnvironment() {
    Spec ordinary = adapter().ensureRequest(REPO, "main", 1L, "main", null).spec();
    Spec editor =
        adapter(TestWorkspaceContainerFactory.editor())
            .ensureRequest(REPO, "main", 1L, "main", null)
            .spec();

    // The editor image is a SECOND pin on a second repository path, not a suffix on the first: the
    // two images are released by two repositories on two calvers.
    assertEquals(TestWorkspaceContainerFactory.IMAGE, ordinary.image());
    assertEquals(TestWorkspaceContainerFactory.EDITOR_IMAGE, editor.image());

    // Both vars or neither, the rule the commissioned credential block follows: `enabled` without a
    // port leaves the daemon and the host free to pick different numbers, and a port without
    // `enabled` names a listener nothing starts.
    assertNull(ordinary.env().get("QITS_WORKSPACE_DAEMON_EDITOR_ENABLED"));
    assertNull(ordinary.env().get("QITS_WORKSPACE_DAEMON_EDITOR_PORT"));
    assertEquals("true", editor.env().get("QITS_WORKSPACE_DAEMON_EDITOR_ENABLED"));
    assertEquals("13339", editor.env().get("QITS_WORKSPACE_DAEMON_EDITOR_PORT"));

    // …and that is the WHOLE difference, asserted the way the admin posture's is: the editor spec
    // with the image and the two variables put back to the plain workspace's. Same user, same
    // limits, same mounts, same labels, same socket answer. The editor image is the workspace image
    // plus one directory, so a container that differed anywhere else would be a second decision
    // riding along with the one somebody made.
    java.util.Map<String, String> env = new java.util.LinkedHashMap<>(editor.env());
    env.remove("QITS_WORKSPACE_DAEMON_EDITOR_ENABLED");
    env.remove("QITS_WORKSPACE_DAEMON_EDITOR_PORT");
    Spec editorAsAPlainWorkspace =
        new Spec(
            ordinary.image(),
            editor.entrypoint(),
            editor.args(),
            env,
            editor.extraLabels(),
            editor.network(),
            editor.aliases(),
            editor.addHosts(),
            editor.volumeMounts(),
            editor.sharedMounts(),
            editor.hostDockerSocket(),
            editor.security(),
            editor.pullPolicy(),
            editor.explicitName(),
            editor.user(),
            editor.init());
    assertEquals(ordinary, editorAsAPlainWorkspace);
  }

  @Test
  void theEditorSpecIsTheSameOnEveryEnsure() {
    // The spec-hash rule from the adapter's side: the orchestrator has no start verb, so a resume
    // presents the SAME request again under Recreate.ifChanged. Two ensures with the same arguments
    // must therefore be equal requests — image, environment and policy alike — or every resume of an
    // editor workspace would replace the container it meant to start.
    WorkspaceContainers adapter = adapter(TestWorkspaceContainerFactory.editor());

    EnsureRequest first = adapter.ensureRequest(REPO, "main", 1L, "main", null);
    EnsureRequest second = adapter.ensureRequest(REPO, "main", 1L, "main", null);

    assertEquals(first, second);
  }

  // --- the editor's lifetime --------------------------------------------------------------------

  @Test
  void theSwitchIsOffAndEveryWorkspaceKeepsTheExplicitLifetime() {
    // The shipped posture. An editor workspace with no deadline configured is EXPLICIT like every
    // other workspace, which is exactly the behaviour that predates this key.
    EnsureRequest editor =
        adapter(TestWorkspaceContainerFactory.editor()).ensureRequest(REPO, "main", 1L, "main", null);

    assertEquals(PolicyType.EXPLICIT, editor.policy().type());
    assertNull(editor.policy().idleAfterSeconds());
  }

  @Test
  void theDeadlineReachesTheEDITORSCONTAINERANDNOOTHER() {
    // The whole of what the switch does. A workspace is somewhere a person works and an agent runs
    // unattended, so nothing but a person may end one; the editor's workspace is the one that is
    // opened, read and left.
    Optional<Duration> idle = Optional.of(Duration.ofMinutes(30));

    EnsureRequest editor =
        adapter(TestWorkspaceContainerFactory.editor(), idle)
            .ensureRequest(REPO, "main", 1L, "main", null);
    assertEquals(PolicyType.IDLE_STOP, editor.policy().type());
    assertEquals(Long.valueOf(1800L), editor.policy().idleAfterSeconds());

    EnsureRequest plain =
        adapter(TestWorkspaceContainerFactory.persistent(), idle)
            .ensureRequest(REPO, "work", 1L, "feature/x", "main");
    assertEquals(PolicyType.EXPLICIT, plain.policy().type());
    assertNull(plain.policy().idleAfterSeconds());
  }

  @Test
  void theDeadlineIsNotPartOfTheSPEC() {
    // Recreate.ifChanged compares the SPEC, and a policy is not part of one — which is why turning
    // the switch on does not replace a container that is already running. Asserted as the two specs
    // being equal while the two policies are not.
    EnsureRequest off =
        adapter(TestWorkspaceContainerFactory.editor()).ensureRequest(REPO, "main", 1L, "main", null);
    EnsureRequest on =
        adapter(TestWorkspaceContainerFactory.editor(), Optional.of(Duration.ofMinutes(30)))
            .ensureRequest(REPO, "main", 1L, "main", null);

    assertEquals(off.spec(), on.spec());
    assertEquals(PolicyType.EXPLICIT, off.policy().type());
    assertEquals(PolicyType.IDLE_STOP, on.policy().type());
  }

  @Test
  void theSandboxKeepsTheCapabilitiesAStepContainerDeliberatelyLoses() {
    Spec spec = adapter().ensureRequest(REPO, "work", 1L, "main", null).spec();

    // NOT qits-ci's sandbox, and the difference is the whole point: a step container runs a
    // repository's script and drops everything it can, while this is a development environment a
    // person works in — it has to be able to su, chown its own checkout and install a toolchain.
    // What bounds it instead is the resource caps beside them. The swap total is docker's
    // --memory-swap and INCLUDES the memory cap, so 4g/8g is 4G of RAM plus 4G of swap — headroom
    // no other platform container gets, granted here by this service and not by qits-containers.
    assertEquals(new Security(false, false, "4g", "8g", null, null, 600), spec.security());
  }

  @Test
  void aBlankSwapKeyFallsBackToTheHardCap() {
    Spec spec =
        adapter(TestWorkspaceContainerFactory.noSwap())
            .ensureRequest(REPO, "work", 1L, "main", null)
            .spec();

    // "The key is blank" must mean "no swap". A null on the wire would leave the value to the
    // runtime — unlimited swap — so the adapter sends the memory cap for both, the shape this
    // service always sent before the swap key existed.
    assertEquals(new Security(false, false, "4g", "4g", null, null, 600), spec.security());
  }

  @Test
  void theThreeSharedVolumesAreTheePlatformsAndTheWorkspaceVolumeIsClaimed() {
    Spec spec = adapter().ensureRequest(REPO, "work", 1L, "main", null).spec();

    // The platform's: the orchestrator creates exactly these three at its own boot, no row claims
    // them and no delete may ever take them.
    assertEquals(
        List.of(
            new SharedMount("qits_shared_dot_claude", "/claude-home"),
            new SharedMount("qits_shared_m2", "/caches/m2"),
            new SharedMount("qits_shared_pnpm", "/caches/pnpm")),
        spec.sharedMounts());
    // This workload's own: created with the container by the same ensure, and the reason a recreate
    // reattaches the same checkout instead of re-cloning.
    assertEquals(
        List.of(new VolumeMount("qits_workspace_work", "/workspace")), spec.volumeMounts());
  }

  @Test
  void aCommissionedCredentialRidesTheSpecAsEnvAndChangesTheSpecHash() {
    Spec plain = adapter().ensureRequest(REPO, "work", 1L, "main", null).spec();
    Spec commissioned =
        adapter(TestWorkspaceContainerFactory.commissioned("ws-1-a", "s3cr3t"))
            .ensureRequest(REPO, "work", 1L, "main", null)
            .spec();

    // The pair the workspace authenticates to the platform with, on the spec the orchestrator
    // stores — which is also why it must be derived from the row at every ensure and not handed in
    // on the provision path alone: the two specs below are DIFFERENT specs, and Recreate.ifChanged
    // replaces a container whose spec moved.
    assertFalse(plain.env().containsKey("QITS_COMMISSIONED_CLIENT_ID"), plain.env().toString());
    assertEquals("ws-1-a", commissioned.env().get("QITS_COMMISSIONED_CLIENT_ID"));
    assertEquals("s3cr3t", commissioned.env().get("QITS_COMMISSIONED_CLIENT_SECRET"));
  }

  @Test
  void theKillSwitchTakesTheClaimedVolumeAndNothingElse() {
    Spec spec =
        adapter(TestWorkspaceContainerFactory.ephemeralWorkspace())
            .ensureRequest(REPO, "work", 1L, "main", null)
            .spec();

    // persist-workspace off: /workspace reverts to the container's own writable layer, so there is
    // no claimed volume at all. The three shared mounts are untouched by that flag.
    assertEquals(List.of(), spec.volumeMounts());
    assertEquals(3, spec.sharedMounts().size());
  }

  // --- the ref ----------------------------------------------------------------------------------

  @Test
  void aNameThatIsAlreadyALegalRefIsTheRef() {
    // Every ordinary workspace: the name IS the place, qits-ci's stance, and the common case stays
    // readable.
    assertEquals("qits-ws-main-abc12345", WorkspaceContainers.refOf("qits-ws-main-abc12345"));
  }

  @Test
  void aNameOutsideTheRefCharsetIsNormalizedAndDisambiguated() {
    // A branch slug carries [A-Za-z0-9_-] and the ref charset is [a-z0-9-], so uppercase and
    // underscores have to fold. Folding alone would map two workspaces onto one place, so a name
    // that had to change carries a hash of its whole self.
    String upper = WorkspaceContainers.refOf("qits-ws-Login_V2-abc12345");
    String lower = WorkspaceContainers.refOf("qits-ws-login-v2-abc12345");

    assertTrue(upper.startsWith("qits-ws-login-v2-abc12345-"), upper);
    assertTrue(upper.matches("[a-z0-9][a-z0-9-]*"), upper);
    // The two would collide on the normalized form alone; they do not.
    assertFalse(upper.equals(lower), "normalization must not merge two distinct workspaces");
    // Deterministic, which is what lets rm and stop address what run put there.
    assertEquals(upper, WorkspaceContainers.refOf("qits-ws-Login_V2-abc12345"));
  }

  // --- launching ---------------------------------------------------------------------------------

  @Test
  void aLaunchIsOnePutAtTheWorkspacesOwnPlace() {
    stub.script(201, RUNNING);

    String name = adapter().run(REPO, "work", 1L, "main", null);

    assertEquals("qits-ws-work-repo1234", name);
    StubContainersServer.Received put = stub.last();
    assertEquals("PUT", put.method());
    assertEquals(
        "/containers/api/containers/" + OWNER + "/workspace/qits-ws-work-repo1234", put.path());
  }

  @Test
  void aLaunchHoldsThroughAnIdpCutoversFourOhOne() {
    // The 2026-08-12 lesson: a token minted by an idp the deploy train has just replaced reads as a
    // statement about the request and is one about the moment. Each attempt asks the TokenSource
    // again, which is the only way a post-cutover token is ever picked up.
    stub.script(401, "{\"code\":\"UNAUTHORIZED\",\"message\":\"no\"}").script(201, RUNNING);

    assertEquals("qits-ws-work-repo1234", adapter().run(REPO, "work", 1L, "main", null));
    assertEquals(2, stub.received().size());
  }

  @Test
  void aLaunchHoldsThroughAnOrchestratorThatSaidNothing() {
    // Retrying an unanswered ensure is safe for a reason a bare `docker run` never had: it is a PUT
    // per (owner, workload, ref), so the second attempt addresses the same place and a container the
    // first created but could not report is adopted rather than duplicated.
    stub.scriptSilence().script(201, RUNNING);

    assertEquals("qits-ws-work-repo1234", adapter().run(REPO, "work", 1L, "main", null));
    assertEquals(2, stub.received().size());
  }

  @Test
  void aRefusalAboutTheRequestIsOneAttempt() {
    // No window makes an unpublished image appear, so this is one attempt and one loud failure —
    // and the code travels into the message, because it is what tells an operator whether to push an
    // image or to fix a pin.
    stub.script(409, "{\"code\":\"IMAGE_MISSING\",\"message\":\"nothing published that\"}");

    InternalServerErrorException failure =
        assertThrows(
            InternalServerErrorException.class,
            () -> adapter().run(REPO, "work", 1L, "main", null));

    assertEquals(1, stub.received().size());
    assertTrue(failure.getMessage().contains("IMAGE_MISSING"), failure.getMessage());
  }

  @Test
  void aTwoHundredWhoseContainerIsNotThereIsAFailedLaunch() {
    // The wire contract is explicit that an ensure whose container did not start is a TRUE answer
    // rather than a failed request: the row exists, it says MISSING, and it carries what docker
    // said. Reading it as started would leave a workspace RUNNING with nothing behind it.
    stub.script(
        200,
        "{\"id\":\"6f1b3d2e-0000-4000-8000-000000000001\",\"containerName\":\"qits-ws-work-repo1234\","
            + "\"state\":{\"desired\":\"RUNNING\",\"observed\":\"MISSING\"},\"endpoint\":null,"
            + "\"specHash\":\"h1\",\"created\":true,\"detail\":\"[docker refused to start it]\"}");

    InternalServerErrorException failure =
        assertThrows(
            InternalServerErrorException.class,
            () -> adapter().run(REPO, "work", 1L, "main", null));

    // Not retried either: something answered about this very container.
    assertEquals(1, stub.received().size());
    assertTrue(failure.getMessage().contains("docker refused to start it"), failure.getMessage());
  }

  // --- the rest of the lifecycle -----------------------------------------------------------------

  @Test
  void startIsOneEnsureAtThePlaceTheWorkspaceAlreadyOccupies() {
    // Nothing is removed first, and that is the contract rather than an economy. The registry reads
    // the unchanged spec, sees a container that is merely stopped, and starts THAT container where
    // it stands — so its id and its writable layer survive, and the checkout with them. A delete
    // beforehand would not only throw the layer away, it would be refused: a container name stays
    // unique across settled rows for the prune horizon, so a fresh ensure under the same name after
    // a row delete conflicts.
    stub.script(200, RUNNING);

    adapter().start(REPO, "work", 1L, "main", null);

    List<StubContainersServer.Received> seen = stub.received();
    assertEquals(1, seen.size());
    assertEquals("PUT", seen.getFirst().method());
    assertEquals(
        "/containers/api/containers/" + OWNER + "/workspace/qits-ws-work-repo1234",
        seen.getFirst().path());
  }

  @Test
  void rmNeverTakesTheWorkspaceVolumeWithIt() {
    // The per-workspace volume outlives the container by design — a recreate reattaches the same
    // checkout — and the one operation that means to drop it says so by name.
    stub.script(200, "{\"id\":null,\"containerName\":\"c\",\"existed\":true,\"logTail\":null,"
        + "\"detail\":null}");

    adapter().rm("qits-ws-work-repo1234");

    StubContainersServer.Received delete = stub.last();
    assertEquals("DELETE", delete.method());
    assertEquals(
        "/containers/api/containers/" + OWNER + "/workspace/qits-ws-work-repo1234", delete.path());
    assertEquals("volumes=false&logs=false", delete.query());
  }

  @Test
  void removingTheWorkspaceVolumeGoesThroughTheStandaloneVolumeDoor() {
    // The volume can outlive or precede a container entirely — a branch abandoned before it was ever
    // provisioned — so asking for it on a container's delete would only work on the one path where
    // both exist at once.
    stub.script(200, "{\"id\":null,\"owner\":\"" + OWNER + "\",\"name\":\"qits_workspace_work\","
        + "\"desired\":\"ABSENT\",\"existed\":true,\"detail\":null}");

    adapter().removeWorkspaceVolume("work");

    StubContainersServer.Received delete = stub.last();
    assertEquals("DELETE", delete.method());
    assertEquals("/containers/api/volumes/" + OWNER + "/qits_workspace_work", delete.path());
  }

  @Test
  void aStoppedContainerStillExistsAndIsNotRunning() {
    // The distinction the whole start-in-place path rests on: a deliberate stop leaves the place
    // there, so `exists` must stay true while `isRunning` goes false.
    stub.fallback(200, envelope("qits-ws-work-repo1234", "EXITED"));

    WorkspaceContainers containers = adapter();
    assertTrue(containers.exists("qits-ws-work-repo1234"));
    assertFalse(containers.isRunning("qits-ws-work-repo1234"));
  }

  @Test
  void aPlaceWithNoRowNeitherExistsNorRuns() {
    stub.fallback(404, "{\"code\":\"NOT_FOUND\",\"message\":\"no such place\"}");

    WorkspaceContainers containers = adapter();
    assertFalse(containers.exists("qits-ws-work-repo1234"));
    assertFalse(containers.isRunning("qits-ws-work-repo1234"));
  }

  @Test
  void anUnreachableOrchestratorReadsAsAbsentRatherThanAsRunning() {
    // The safe direction rather than the honest one: `exists` false sends the caller to run, which
    // is an idempotent PUT at the same place. The opposite default would report a workspace as
    // running while the orchestrator was down, and the proxy would dial a container nobody could
    // confirm.
    stub.fallback(503, "{\"code\":\"UNAVAILABLE\",\"message\":\"down\"}");

    WorkspaceContainers containers = adapter();
    assertFalse(containers.exists("qits-ws-work-repo1234"));
    assertFalse(containers.isRunning("qits-ws-work-repo1234"));
  }

  @Test
  void theWorkspaceListingIsOneCallMatchedOnNames() {
    // One call and not one per workspace: this backs the workspace list endpoint, which a browser
    // polls. The listing carries no labels, so the workspace id is read back out of the name.
    stub.script(
        200,
        "{\"containers\":["
            + envelope("qits-ws-work-repo1234", "RUNNING")
            + ","
            + envelope("qits-ws-paused-repo1234", "EXITED")
            + ","
            + envelope("qits-ws-other-99999999", "RUNNING")
            + ","
            + envelope("qits-ct-someone-else", "RUNNING")
            + "]}");

    List<ContainerRuntime.ContainerInfo> found = adapter().listWorkspaceContainers(REPO);

    assertEquals(1, stub.received().size());
    assertEquals("GET", stub.last().method());
    // Another repository's container and a place that is not a workspace at all are both out.
    assertEquals(List.of("work", "paused"), found.stream().map(ContainerRuntime.ContainerInfo::workspaceId).toList());
    assertTrue(found.get(0).running());
    // A deliberately stopped container is listed and reads as not running — the reconcile needs to
    // see it, and only genuinely-running ones may count as RUNNING.
    assertFalse(found.get(1).running());
    // branch and parent lived on labels the listing does not answer with, and nothing reads them.
    assertNull(found.get(0).branch());
    assertNull(found.get(0).parent());
  }

  @Test
  void aFailedListingClaimsNothingIsRunning() {
    stub.fallback(503, "{\"code\":\"UNAVAILABLE\",\"message\":\"down\"}");

    assertEquals(List.of(), adapter().listWorkspaceContainers(REPO));
  }

  @Test
  void theProxyTargetIsTheContainersOwnNameOnTheSharedNetwork() {
    // Pure: no round trip, and no component of a request ever selects a host or a port. The
    // bridge-ip mode that needed an inspect is gone with the key that selected it.
    assertEquals(
        new ProxyOrigin("qits-ws-work-repo1234", 13338),
        adapter().resolveTarget("qits-ws-work-repo1234", 13338));
    assertEquals(0, stub.received().size());
  }

  // --- what the orchestrator has no verb for -----------------------------------------------------

  @Test
  void theVerbsWithNoProductionCallerRefuseRatherThanAnswer() {
    // Reaching one of these is a new caller rather than a state, so it must fail where it is written
    // instead of quietly answering something. The interface keeps them because the two
    // FakeContainerRuntimes are their real implementors.
    WorkspaceContainers containers = adapter();
    assertThrows(
        UnsupportedOperationException.class, () -> containers.exec("c", null, null, "git", "log"));
    assertThrows(
        UnsupportedOperationException.class, () -> containers.execArgv("c", false, null, null));
    assertThrows(UnsupportedOperationException.class, () -> containers.restart("c"));
    assertThrows(UnsupportedOperationException.class, containers::listWorkspaceVolumes);
    assertEquals(0, stub.received().size());
  }
}
