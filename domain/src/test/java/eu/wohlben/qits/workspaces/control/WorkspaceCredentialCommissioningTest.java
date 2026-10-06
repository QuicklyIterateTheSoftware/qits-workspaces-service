package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The per-workspace platform credential, over the lifecycle it mirrors: it is commissioned when a
 * container is provisioned and given back when one is torn down — <b>the container's lifetime, not
 * the row's</b>, which is why a delete-container revokes it and a resume does not renew it.
 *
 * <p>Every case drives {@link WorkspaceService} against the fake container runtime and reads the
 * result back through {@link WorkspaceCredentials}, the same lookup {@link
 * WorkspaceContainerFactory} composes a container's environment from. That is deliberate: what a
 * container ends up carrying is decided by what is on the row at ensure time, so asserting the row
 * through that lookup asserts the thing the environment is made of. The environment itself — those
 * two variables from that pair — is {@code WorkspaceContainerFactoryTest}'s.
 */
@QuarkusTest
public class WorkspaceCredentialCommissioningTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject WorkspaceCredentials credentials;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject ContainerRuntime containers;
  @Inject FakeWorkspaceGitStatus gitStatus;
  @Inject WorkspaceContainerStartedRecorder startedRecorder;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /**
   * Wired for these cases and unwired again afterwards. The double is a bean for the whole module,
   * so leaving it armed would commission for every other test class's containers too.
   */
  @BeforeEach
  void wireAnIssuer() {
    commissioner.reset();
    commissioner.wire();
  }

  @AfterEach
  void unwireTheIssuer() {
    commissioner.reset();
  }

  private String clonedRepo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    return repoId;
  }

  private Optional<WorkspaceCredential> credentialOf(String repoId, String workspaceId) {
    return credentials.forWorkspace(workspaceIds.of(repoId, workspaceId));
  }

  @Test
  public void aFreshProvisionCommissionsAPairAndPutsItOnTheWorkspace() throws Exception {
    String repoId = clonedRepo();
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "feat", "master", "feat", null));

    // Creating a workspace commissions nothing: there is no container yet, and the credential's
    // lifetime is the container's.
    assertTrue(credentialOf(repoId, "feat").isEmpty(), "creation commissions nothing");
    assertEquals(List.of(), commissioner.commissionedFor());

    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));

    WorkspaceCredential credential =
        credentialOf(repoId, "feat").orElseThrow(() -> new AssertionError("no credential"));
    assertFalse(credential.clientId().isBlank(), "the row carries the commissioned client id");
    assertFalse(credential.secret().isBlank(), "and its secret, so the spec is reproducible");
    assertEquals(
        List.of(credential.clientId()),
        commissioner.liveClientIds(),
        "the issuer holds exactly the one credential the workspace claims");

    // AND IT IS SCOPED TO THE REPOSITORY'S PROJECT. A workspace belongs to a repository, which
    // belongs to a project, and that is the scope a resource service judges this container's token
    // on — so the launch has to resolve it here rather than leave the credential covering every
    // project the way it did before. It is the same project the container is told about.
    assertEquals(
        FakeRepositoryLookup.PROJECT_ID,
        commissioner.scopeFor(workspaceIds.of(repoId, "feat")),
        "the commission says which project this credential is for");
  }

  @Test
  public void aStoppedContainerThatIsStartedAgainKeepsItsCredential() throws Exception {
    String repoId = clonedRepo();
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "feat", "master", "feat", null));
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    WorkspaceCredential first = credentialOf(repoId, "feat").orElseThrow();

    workspaceService.stopContainer(workspaceIds.of(repoId, "feat"));
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));

    // A resume presents the SAME spec — anything else is a spec change, and the orchestrator
    // replaces a container whose spec moved, taking its writable layer with it.
    assertEquals(first, credentialOf(repoId, "feat").orElseThrow(), "the resume keeps the pair");
    assertEquals(1, commissioner.commissionedFor().size(), "a resume commissions nothing new");
    assertEquals(List.of(), commissioner.decommissioned(), "and hands nothing back");
  }

  @Test
  public void aRecreateCommissionsAFreshPairAndHandsTheOldOneBack() throws Exception {
    String repoId = clonedRepo();
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "feat", "master", "feat", null));
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    WorkspaceCredential first = credentialOf(repoId, "feat").orElseThrow();
    // Recreate admits only an explicit-clean daemon report.
    gitStatus.report(workspaceIds.of(repoId, "feat"), true);
    startedRecorder.clear();

    workspaceService.beginRecreateContainer(workspaceIds.of(repoId, "feat"));
    assertTrue(
        startedRecorder.awaitCount(repoId, "feat", 1, 10_000), "recreate re-provisions the container");

    WorkspaceCredential second = credentialOf(repoId, "feat").orElseThrow();
    assertNotEquals(first, second, "a new container is a new credential");
    assertEquals(
        List.of(first.clientId()), commissioner.decommissioned(), "the old pair goes back");
    assertEquals(
        List.of(second.clientId()),
        commissioner.liveClientIds(),
        "and the issuer is left holding only the live one");
  }

  @Test
  public void deletingTheContainerHandsTheCredentialBackWhileTheRowStaysActive() throws Exception {
    String repoId = clonedRepo();
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "feat", "master", "feat", null));
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    WorkspaceCredential first = credentialOf(repoId, "feat").orElseThrow();

    workspaceService.deleteContainer(workspaceIds.of(repoId, "feat"));

    // No WorkspaceResolved fires here — the workspace is still ACTIVE — which is exactly why an
    // observer on that event could not have covered this path.
    assertEquals(List.of(first.clientId()), commissioner.decommissioned());
    assertTrue(credentialOf(repoId, "feat").isEmpty(), "the row no longer claims a credential");
    assertEquals(List.of(), commissioner.liveClientIds());

    // ...and the next ensure commissions a fresh one for the container it provisions.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    WorkspaceCredential second = credentialOf(repoId, "feat").orElseThrow();
    assertNotEquals(first, second);
  }

  @Test
  public void abandoningAWorkspaceHandsTheCredentialBack() throws Exception {
    String repoId = clonedRepo();
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "feat", "master", "feat", null));
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    WorkspaceCredential credential = credentialOf(repoId, "feat").orElseThrow();
    gitStatus.report(workspaceIds.of(repoId, "feat"), true); // a discard needs an explicit clean

    workspaceService.discardWorkspace(workspaceIds.of(repoId, "feat"));

    assertEquals(List.of(credential.clientId()), commissioner.decommissioned());
    assertEquals(List.of(), commissioner.liveClientIds());
  }

  @Test
  public void theBranchGoneAbandonHandsTheCredentialBack() throws Exception {
    String repoId = clonedRepo();
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "feat", "master", "feat", null));
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    WorkspaceCredential credential = credentialOf(repoId, "feat").orElseThrow();

    // The second termination path: container and durable branch both gone, so the work exists
    // nowhere and the next ensure abandons the workspace instead of re-provisioning it.
    containers.rm(containers.containerName("feat", repoId));
    TestGit.exec(Path.of(dataDir, repoId, "origin").toFile(), "git", "branch", "-D", "--", "feat");

    assertThrows(
        NotFoundException.class,
        () -> workspaceService.ensureContainer(workspaceIds.of(repoId, "feat")));

    assertEquals(List.of(credential.clientId()), commissioner.decommissioned());
    assertEquals(List.of(), commissioner.liveClientIds());
  }

  @Test
  public void withNoIssuerWiredNothingIsCommissionedAndNoWorkspaceCarriesACredential()
      throws Exception {
    commissioner.reset(); // back to the shipped posture: the port answers empty
    String repoId = clonedRepo();
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "feat", "master", "feat", null));

    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));

    assertTrue(containers.exists(containers.containerName("feat", repoId)), "the launch is normal");
    assertTrue(credentialOf(repoId, "feat").isEmpty(), "and carries no credential");
    assertEquals(List.of(), commissioner.liveClientIds(), "nothing was minted");
  }

  @Test
  public void aCommissioningFailureFailsTheProvisionAndStartsNoContainer() throws Exception {
    String repoId = clonedRepo();
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "feat", "master", "feat", null));
    commissioner.failCommissioning("qits-idp is unreachable");

    // Loudly, and before anything runs: a workspace must never launch half-credentialed, and
    // reporting it at the launch is the only place a person is looking.
    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () -> workspaceService.ensureContainer(workspaceIds.of(repoId, "feat")));
    assertTrue(failure.getMessage().contains("unreachable"), failure.getMessage());
    assertFalse(
        containers.exists(containers.containerName("feat", repoId)),
        "no container is started for a workspace that has no identity");
  }
}
