package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.error.DomainException;
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
 * Admin workspaces carry their own {@code qits_tok_} (qits-1084), and nothing else.
 *
 * <p>An admin row stays DIRECT for good, and its container is minted the same non-expiring
 * workspace token a RUNNER row holds — {@code workspace-admin} — whenever the deployment has an edge
 * plane. With no edge plane (no public {@code QITS_DOMAIN}, the suites' pinned posture) and an
 * issuer wired, the provision is refused: a token only works through the edge, and there is no other
 * credential to give. With no issuer wired it launches with no credential, as every DIRECT
 * container in the rest of the suite does. Every teardown deletes the token.
 *
 * <p>The plane is installed per test with {@link QuarkusMock}, as {@code WorkspaceTargetPlacementTest}
 * installs it; a test that wants no edge installs one that refuses.
 *
 * <p>The editor row the refusal's ticket also names is gone (qits-1152, {@code V20}): an ACTIVE
 * DIRECT row is admin by constraint ({@code ck_workspace_direct_only_admin}), and {@link
 * WorkspacePlacements#requireDirectAllowed} refuses any other before it provisions, so the admin row
 * is the one DIRECT provision there is to test.
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
  @Inject WorkspaceContainerStartedRecorder startedRecorder;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /** An issuer wired, so a DIRECT provision with no edge plane is one to refuse. */
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
    assertEquals(List.of(), minted.gitRefs(), "and may push nothing: agents push (qits-1152)");

    Workspace row = read(rowId);
    assertEquals(WorkspacePlacement.DIRECT, row.placement, "it stays DIRECT");
    assertEquals(minted.token().tokenId(), row.commissionedTokenId);
    assertEquals(minted.token().subject(), row.commissionedTokenSubject);
    assertEquals(minted.token().token(), row.commissionedToken);
    assertNull(row.commissionedClientId, "the legacy client columns are never written");
    assertNull(row.commissionedClientSecret);
    assertEquals(minted.token().subject(), row.boundSubject(), "it is bound by the token subject");

    // The lookup the container factory composes the spec from answers the token.
    assertEquals(minted.token(), credentials.tokenFor(rowId).orElseThrow());
  }

  @Test
  public void withNoEdgePlaneAnAdminProvisionIsRefusedAndNothingIsStored() throws Exception {
    noEdgePlane();
    Long rowId = adminRow();

    DomainException refused =
        assertThrows(DomainException.class, () -> workspaceService.ensureContainer(rowId));

    assertEquals(503, refused.statusCode());
    assertEquals(EdgePlaneUnconfigured.CODE, refused.code());
    assertTrue(refused.getMessage().contains("QITS_DOMAIN"), refused.getMessage());
    assertTrue(refused.getMessage().contains("public edge"), refused.getMessage());
    assertFalse(containers.exists(containerOf(rowId)), "no container is started");
    assertEquals(List.of(), commissioner.tokensMinted(), "no commission reached the issuer");
    assertEquals(List.of(), commissioner.agentTokensMinted());
    assertEquals(List.of(), commissioner.liveClientIds(), "and no client was commissioned");
    Workspace row = read(rowId);
    assertFalse(row.holdsToken(), "the row holds no token");
    assertNull(row.commissionedTokenSubject);
    assertNull(row.commissionedToken);
    assertNull(row.commissionedClientId, "and no client");
    assertNull(row.commissionedClientSecret);
    assertNull(row.boundSubject(), "so it binds nobody");
    assertEquals(WorkspaceRuntimeStatus.FAILED, row.runtimeStatus, "the row says it failed");
    assertTrue(row.runtimeError.contains(EdgePlaneUnconfigured.CODE), row.runtimeError);
    assertTrue(credentials.tokenFor(rowId).isEmpty());
  }

  @Test
  public void anEdgeThatGoesAwayRefusesTheNextProvisionAndLeavesNoToken() throws Exception {
    anEdgePlane();
    Long rowId = adminRow();
    workspaceService.ensureContainer(rowId);
    String tokenId = read(rowId).commissionedTokenId;

    noEdgePlane();
    workspaceService.deleteContainer(rowId);
    assertThrows(DomainException.class, () -> workspaceService.ensureContainer(rowId));

    assertEquals(1, commissioner.tokensMinted().size(), "the refused provision minted nothing");
    assertEquals(List.of(tokenId), commissioner.tokensDeleted());
    assertEquals(List.of(), commissioner.liveTokenIds());
    assertFalse(read(rowId).holdsToken());
    assertFalse(containers.exists(containerOf(rowId)));
  }

  @Test
  public void withNoEdgePlaneAndNoIssuerTheAdminRowLaunchesWithNoCredential() throws Exception {
    commissioner.reset();
    noEdgePlane();
    Long rowId = adminRow();

    workspaceService.ensureContainer(rowId);

    assertTrue(containers.exists(containerOf(rowId)), "nothing to refuse without an issuer");
    assertEquals(List.of(), commissioner.tokensMinted());
    Workspace row = read(rowId);
    assertFalse(row.holdsToken());
    assertNull(row.commissionedClientId);
    assertNull(row.commissionedClientSecret);
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
    // The first start's event is delivered asynchronously: drain it before clearing, or it can
    // land after the clear and pass for the recreate's own start before the new token is written.
    assertTrue(
        startedRecorder.awaitCount(before.repositoryId, "feat", 1, 10_000),
        "the first start is delivered");
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
    assertFalse(read(rowId).holdsToken(), "and nothing stored");
  }

  private String containerOf(Long rowId) {
    Workspace row = read(rowId);
    return containers.containerName(row.workspaceId, row.repositoryId);
  }
}
