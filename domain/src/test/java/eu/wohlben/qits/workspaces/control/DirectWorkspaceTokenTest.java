package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Admin and editor workspaces carry their own {@code qits_tok_} (qits-1084).
 *
 * <p>Both stay DIRECT for good, and whenever the deployment has an edge plane their container is
 * minted the same non-expiring workspace token a RUNNER row holds — {@code workspace-admin} for an
 * admin row, an unscoped {@code workspace} one for the editor — in place of the hourly-minting client
 * pair. With no edge plane (no public {@code QITS_DOMAIN}, the suites' pinned posture) the pair is
 * still commissioned, which {@link WorkspaceCredentialCommissioningTest} covers whole; here the
 * fallback is asserted only as the other side of a switch. A row holds one or the other, never both,
 * and every teardown that gives the pair back deletes the token too.
 *
 * <p>The plane is installed per test with {@link QuarkusMock}, as {@code WorkspaceRunnerPlacementTest}
 * installs it; a test that wants the fallback installs none.
 */
@QuarkusTest
public class DirectWorkspaceTokenTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject WorkspaceCredentials credentials;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject ContainerRuntime containers;
  @Inject FakeWorkspaceGitStatus gitStatus;
  @Inject WorkspaceContainerStartedRecorder startedRecorder;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /** Both halves of the issuer wired, so a fallback to the pair is visible as a pair. */
  @BeforeEach
  void wireAnIssuer() {
    commissioner.reset();
    commissioner.wire();
  }

  @AfterEach
  void unwireTheIssuer() {
    commissioner.reset();
  }

  private static void anEdgePlane() {
    QuarkusMock.installMockForType(
        new WorkspaceAddressPlanes() {
          @Override
          public WorkspaceAddressPlane plane() {
            return WorkspaceAddressPlane.of("example.test", List.of("registry.dev.localhost:8080"));
          }
        },
        WorkspaceAddressPlanes.class);
  }

  private static void noEdgePlane() {
    QuarkusMock.installMockForType(
        new WorkspaceAddressPlanes() {
          @Override
          public WorkspaceAddressPlane plane() {
            return WorkspaceAddressPlane.of("", List.of());
          }
        },
        WorkspaceAddressPlanes.class);
  }

  /** An admin row (the last argument of {@code createWorkspace}), DIRECT for good. */
  private Long adminRow() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null, false, false, true);
    return workspaceIds.of(repoId, "feat");
  }

  /** The editor, its container removed so the next ensure provisions — and so commissions — afresh. */
  private Long freshEditor() {
    Long rowId = workspaceService.createEditorWorkspace().id;
    workspaceService.deleteContainer(rowId);
    commissioner.reset();
    commissioner.wire();
    return rowId;
  }

  private Workspace read(Long rowId) {
    return QuarkusTransaction.requiringNew().call(() -> workspaceRepository.findById(rowId));
  }

  @Test
  public void anAdminRowIsMintedAWorkspaceAdminTokenAndHoldsNoPair() throws Exception {
    anEdgePlane();
    Long rowId = adminRow();

    workspaceService.ensureContainer(rowId);

    assertEquals(1, commissioner.tokensMinted().size(), "one token for the one container");
    FakeCredentialCommissioner.MintedToken minted = commissioner.tokensMinted().get(0);
    assertEquals(rowId.longValue(), minted.rowId());
    assertEquals(CredentialCommissioner.ADMIN_CONTEXT_KIND, minted.contextKind());
    assertEquals(FakeRepositoryLookup.PROJECT_ID, minted.projectId(), "scoped to its project");
    assertEquals(List.of("refs/heads/feat"), minted.gitRefs(), "and to its own branch");
    assertEquals(List.of(), commissioner.commissionedFor(), "no client pair was asked for");

    Workspace row = read(rowId);
    assertEquals(WorkspacePlacement.DIRECT, row.placement, "it stays DIRECT");
    assertEquals(minted.token().tokenId(), row.commissionedTokenId);
    assertEquals(minted.token().subject(), row.commissionedTokenSubject);
    assertEquals(minted.token().token(), row.commissionedToken);
    assertNull(row.commissionedClientId, "and holds no pair beside it");
    assertNull(row.commissionedClientSecret);
    assertEquals(minted.token().subject(), row.boundSubject(), "it is bound by the token subject");

    // The lookup the container factory composes the spec from answers the token, and no pair.
    assertEquals(minted.token(), credentials.tokenFor(rowId).orElseThrow());
    assertTrue(credentials.forWorkspace(rowId).isEmpty());
  }

  @Test
  public void theEditorIsMintedAnUnscopedWorkspaceTokenWithThePairsGitRefs() {
    // The pair first, with no edge plane: what the editor's commission states today.
    noEdgePlane();
    Long rowId = freshEditor();
    workspaceService.ensureContainer(rowId);
    List<String> pairRefs = commissioner.gitRefsFor(rowId);
    assertEquals(CredentialCommissioner.CONTEXT_KIND, commissioner.contextKindFor(rowId));
    assertEquals(List.of(), commissioner.tokensMinted(), "no plane, no token");

    // Then an edge: the next container holds a token of the editor's kind, stating the same refs.
    anEdgePlane();
    workspaceService.deleteContainer(rowId);
    workspaceService.ensureContainer(rowId);

    assertEquals(1, commissioner.tokensMinted().size());
    FakeCredentialCommissioner.MintedToken minted = commissioner.tokensMinted().get(0);
    assertEquals(CredentialCommissioner.CONTEXT_KIND, minted.contextKind(), "never the admin kind");
    assertNull(minted.projectId(), "the editor names no repository, so no project: unscoped");
    assertEquals(pairRefs, minted.gitRefs(), "the same Git refs the editor's pair stated");
    Workspace row = read(rowId);
    assertEquals(minted.token().tokenId(), row.commissionedTokenId);
    assertNull(row.commissionedClientId, "the pair went back with its container");
  }

  @Test
  public void withNoEdgePlaneTheAdminRowFallsBackToThePairAndHoldsNoToken() throws Exception {
    noEdgePlane();
    Long rowId = adminRow();

    workspaceService.ensureContainer(rowId);

    assertTrue(containers.exists(containerOf(rowId)), "the launch is normal");
    assertEquals(List.of(), commissioner.tokensMinted(), "no token without an edge");
    WorkspaceCredential pair = credentials.forWorkspace(rowId).orElseThrow();
    assertEquals(CredentialCommissioner.ADMIN_CONTEXT_KIND, commissioner.contextKindFor(rowId));
    Workspace row = read(rowId);
    assertEquals(pair.clientId(), row.commissionedClientId);
    assertFalse(row.holdsToken(), "and no token beside it");
    assertEquals(pair.clientId(), row.boundSubject(), "it is bound by its client, as before");
    assertTrue(credentials.tokenFor(rowId).isEmpty());
  }

  @Test
  public void aRowSwitchesFromThePairToATokenAndBackAtItsNextContainer() throws Exception {
    noEdgePlane();
    Long rowId = adminRow();
    workspaceService.ensureContainer(rowId);
    String client = read(rowId).commissionedClientId;

    anEdgePlane();
    workspaceService.deleteContainer(rowId);
    workspaceService.ensureContainer(rowId);

    Workspace tokenRow = read(rowId);
    assertTrue(tokenRow.holdsToken());
    assertNull(tokenRow.commissionedClientId, "a token row holds no pair");
    assertTrue(commissioner.decommissioned().contains(client), "the old pair went back");
    assertFalse(commissioner.liveClientIds().contains(client));
    String tokenId = tokenRow.commissionedTokenId;

    noEdgePlane();
    workspaceService.deleteContainer(rowId);
    workspaceService.ensureContainer(rowId);

    Workspace pairRow = read(rowId);
    assertFalse(pairRow.holdsToken(), "a pair row holds no token");
    assertNull(pairRow.commissionedTokenSubject);
    assertNull(pairRow.commissionedToken);
    assertTrue(pairRow.commissionedClientId != null, "it holds a fresh pair");
    assertTrue(commissioner.tokensDeleted().contains(tokenId), "and the token went back");
    assertFalse(commissioner.liveTokenIds().contains(tokenId));
  }

  @Test
  public void aStoppedTokenContainerStartedAgainKeepsItsToken() throws Exception {
    anEdgePlane();
    Long rowId = adminRow();
    workspaceService.ensureContainer(rowId);
    String tokenId = read(rowId).commissionedTokenId;

    workspaceService.stopContainer(rowId);
    workspaceService.ensureContainer(rowId);

    assertEquals(tokenId, read(rowId).commissionedTokenId, "a resume re-presents the same spec");
    assertEquals(1, commissioner.tokensMinted().size(), "and mints nothing new");
    assertEquals(List.of(), commissioner.tokensDeleted());
  }

  @Test
  public void deletingTheContainerDeletesTheTokenWhileTheRowStaysActive() throws Exception {
    anEdgePlane();
    Long rowId = adminRow();
    workspaceService.ensureContainer(rowId);
    String tokenId = read(rowId).commissionedTokenId;

    workspaceService.deleteContainer(rowId);

    assertEquals(List.of(tokenId), commissioner.tokensDeleted());
    assertEquals(List.of(), commissioner.liveTokenIds());
    Workspace row = read(rowId);
    assertFalse(row.holdsToken(), "the columns are cleared with the deletion");
    assertNull(row.commissionedTokenSubject);
    assertNull(row.commissionedToken);
  }

  @Test
  public void discardingDeletesTheToken() throws Exception {
    anEdgePlane();
    Long rowId = adminRow();
    workspaceService.ensureContainer(rowId);
    String tokenId = read(rowId).commissionedTokenId;
    gitStatus.report(rowId, true); // a discard needs an explicit clean

    workspaceService.discardWorkspace(rowId);

    assertEquals(List.of(tokenId), commissioner.tokensDeleted());
    assertEquals(List.of(), commissioner.liveTokenIds());
    assertFalse(read(rowId).holdsToken());
  }

  @Test
  public void aRecreateMintsAFreshTokenAndDeletesTheOldOne() throws Exception {
    anEdgePlane();
    Long rowId = adminRow();
    workspaceService.ensureContainer(rowId);
    Workspace before = read(rowId);
    gitStatus.report(rowId, true); // recreate admits only an explicit-clean daemon report
    startedRecorder.clear();

    workspaceService.beginRecreateContainer(rowId);
    assertTrue(
        startedRecorder.awaitCount(before.repositoryId, "feat", 1, 10_000),
        "recreate re-provisions the container");

    String second = read(rowId).commissionedTokenId;
    assertNotEquals(before.commissionedTokenId, second, "a new container is a new token");
    assertEquals(List.of(before.commissionedTokenId), commissioner.tokensDeleted());
    assertEquals(List.of(second), commissioner.liveTokenIds());
  }

  @Test
  public void aFailedMintFailsTheProvisionAndStartsNoContainer() throws Exception {
    anEdgePlane();
    Long rowId = adminRow();
    commissioner.failTokens("qits-idp is unreachable");

    RuntimeException failure =
        assertThrows(RuntimeException.class, () -> workspaceService.ensureContainer(rowId));

    assertTrue(failure.getMessage().contains("unreachable"), failure.getMessage());
    assertFalse(containers.exists(containerOf(rowId)), "no container for a row with no identity");
    assertEquals(List.of(), commissioner.commissionedFor(), "and no fallback to the pair");
  }

  private String containerOf(Long rowId) {
    Workspace row = read(rowId);
    return containers.containerName(row.workspaceId, row.repositoryId);
  }
}
