package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.CredentialCommissioner;
import eu.wohlben.qits.workspaces.control.FakeCredentialCommissioner;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceCredentials;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.control.WorkspaceSubject;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reconcile: qits-idp is asked what it is holding for this service, and everything no live
 * workspace container claims is given back.
 *
 * <p>This is the structural answer to leaked credentials — the reason there is no TTL on a
 * commission and the reason every teardown seam is allowed to be best-effort. What it must get right
 * is the two directions of "spare": a live workspace's credential survives every pass, and anything
 * else does not.
 */
@QuarkusTest
public class CommissionReconcilerTest {

  @Inject CommissionReconciler reconciler;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceCredentials credentials;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaceRepository;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @BeforeEach
  void wireAnIssuer() {
    commissioner.reset();
    commissioner.wire();
  }

  @AfterEach
  void unwireTheIssuer() {
    commissioner.reset();
  }

  /** A repository with a live workspace whose container holds a commissioned credential. */
  private String liveWorkspace(String workspaceId) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    workspaceService.createWorkspace(repoId, workspaceId, "master", workspaceId, null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, workspaceId));
    return repoId;
  }

  @Test
  public void aStrayIsReapedAndALiveWorkspacesCredentialIsSpared() throws Exception {
    String repoId = liveWorkspace("feat");
    String claimed =
        credentials.forWorkspace(workspaceIds.of(repoId, "feat")).orElseThrow().clientId();
    // What a crash between a decommission and its row write leaves behind: a credential the issuer
    // still honours that no workspace claims.
    commissioner.plant("ws-999-stray", CredentialCommissioner.CONTEXT_KIND, "999");

    assertEquals(1, reconciler.reconcile(), "the stray is given back");

    assertEquals(List.of("ws-999-stray"), commissioner.decommissioned());
    assertEquals(List.of(claimed), commissioner.liveClientIds(), "the live workspace keeps its own");
  }

  @Test
  public void aSecondPassOverALiveWorkspaceReapsNothing() throws Exception {
    String repoId = liveWorkspace("feat");
    String claimed =
        credentials.forWorkspace(workspaceIds.of(repoId, "feat")).orElseThrow().clientId();

    assertEquals(0, reconciler.reconcile());
    assertEquals(0, reconciler.reconcile(), "an hourly pass is not a slow revocation");
    assertEquals(List.of(claimed), commissioner.liveClientIds());
  }

  @Test
  public void aCredentialCommissionedForAnotherKindIsNotTheWorkspaceRulesToSweep() throws Exception {
    liveWorkspace("feat");
    // Nothing commissions these today. The filter is what keeps the next thing this service
    // commissions from being swept by a rule that was never about it.
    commissioner.plant("run-3", "run", "3");

    assertEquals(0, reconciler.reconcile());
    assertTrue(commissioner.liveClientIds().contains("run-3"));
  }

  @Test
  public void aDeletedContainersCredentialIsGoneBeforeTheReconcileEverSeesIt() throws Exception {
    String repoId = liveWorkspace("feat");
    String claimed =
        credentials.forWorkspace(workspaceIds.of(repoId, "feat")).orElseThrow().clientId();

    // The teardown seam hands it back itself; the reconcile is the backstop, not the mechanism.
    workspaceService.deleteContainer(workspaceIds.of(repoId, "feat"));

    assertEquals(List.of(claimed), commissioner.decommissioned());
    assertEquals(0, reconciler.reconcile(), "nothing is left for the reconcile to find");
  }

  @Test
  public void withNoIssuerWiredThereIsNothingToReconcile() throws Exception {
    liveWorkspace("feat");
    commissioner.reset(); // back to the shipped posture

    assertEquals(0, reconciler.reconcile());
    assertEquals(List.of(), commissioner.decommissioned());
  }

  @Test
  public void aNarrowingTheIdpDidNotTakeIsSentAgainByTheReconcile() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    workspaceService.createWorkspace(
        repoId,
        "epic-e",
        "master",
        "epic/e",
        null,
        false,
        false,
        false,
        WorkspaceSubject.none(),
        List.of("refs/heads/epic/e", "refs/heads/task/e/a"));
    Long epic = workspaceIds.of(repoId, "epic-e");
    workspaceService.ensureContainer(epic);
    String client = credentials.forWorkspace(epic).orElseThrow().clientId();
    commissioner.failGitRefUpdates(true);

    workspaceService.createWorkspace(repoId, "task-e-a", "epic/e", "task/e/a");

    long deadline = System.currentTimeMillis() + 10_000;
    while (commissioner.gitRefUpdates().isEmpty() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(1, commissioner.gitRefUpdates().size(), "the first update was attempted");
    assertTrue(pending(epic), "and did not land");

    commissioner.failGitRefUpdates(false);
    assertEquals(0, reconciler.reconcile(), "nothing to reap");

    assertEquals(
        new FakeCredentialCommissioner.GitRefUpdate(client, List.of("refs/heads/epic/e")),
        commissioner.gitRefUpdates().get(1),
        "the reconcile sent the narrowed list again");
    assertFalse(pending(epic));
  }

  private boolean pending(Long rowId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workspaceRepository.findActiveById(rowId).orElseThrow().gitRefsPending);
  }
}
