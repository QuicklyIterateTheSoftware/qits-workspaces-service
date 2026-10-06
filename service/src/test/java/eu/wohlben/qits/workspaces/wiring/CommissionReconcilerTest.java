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
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

  /**
   * A repository with a live workspace whose container holds a commissioned credential — an admin
   * one: the commissioned pair is the direct path's credential, and that path is admin and editor
   * only (qits-780).
   */
  private String liveWorkspace(String workspaceId) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createWorkspace(
        repoId, workspaceId, "master", workspaceId, null, false, false, true);
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
    workspaceService.createWorkspace(
        repoId,
        "epic-e",
        "master",
        "epic/e",
        null,
        false,
        false,
        true,
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

  /**
   * The workspace token arm (qits-625, qits-802): an orphaned token (no ACTIVE row names it) and a
   * superseded one (its row holds a newer token) are deleted; the token a live row holds, a token of
   * another kind and a token younger than the grace are kept.
   */
  @Test
  public void anOrphanedAndASupersededTokenAreReapedAndALiveOneIsKept() {
    Long row = runnerRowHolding("tok-live");
    Instant old = Instant.now().minus(Duration.ofHours(1));
    commissioner.plantToken("tok-live", CredentialCommissioner.CONTEXT_KIND, row.toString(), old);
    commissioner.plantToken(
        "tok-superseded", CredentialCommissioner.CONTEXT_KIND, row.toString(), old);
    commissioner.plantToken("tok-orphan", CredentialCommissioner.CONTEXT_KIND, "999999", old);
    commissioner.plantToken(
        "tok-young", CredentialCommissioner.CONTEXT_KIND, "999998", Instant.now());
    commissioner.plantToken("tok-registration", "workspaces-runner-registration", "r", old);
    try {
      assertEquals(2, reconciler.reapWorkspaceTokens(Instant.now()));

      assertEquals(
          java.util.Set.of("tok-superseded", "tok-orphan"),
          java.util.Set.copyOf(commissioner.tokensDeleted()));
      assertEquals(
          java.util.Set.of("tok-live", "tok-young", "tok-registration"),
          java.util.Set.copyOf(commissioner.liveTokenIds()));
    } finally {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  workspaceRepository
                      .findByIdOptional(row)
                      .ifPresent(w -> w.status = WorkspaceStatus.ABANDONED));
    }
  }

  /** An ACTIVE RUNNER row holding the workspace token {@code tokenId}, written directly. */
  private Long runnerRowHolding(String tokenId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Workspace w = new Workspace();
              String label = "w" + UUID.randomUUID().toString().substring(0, 8);
              w.workspaceId = label;
              w.repositoryId = "repo-" + label;
              w.branch = label;
              w.status = WorkspaceStatus.ACTIVE;
              w.placement = WorkspacePlacement.RUNNER;
              w.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
              w.commissionedTokenId = tokenId;
              w.commissionedTokenSubject = "tok-workspace-" + label;
              w.commissionedToken = "qits_tok_" + label;
              workspaceRepository.persist(w);
              workspaceRepository.flush();
              return w.id;
            });
  }

  private boolean pending(Long rowId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workspaceRepository.findActiveById(rowId).orElseThrow().gitRefsPending);
  }
}
