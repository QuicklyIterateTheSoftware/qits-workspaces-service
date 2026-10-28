package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.CredentialCommissioner;
import eu.wohlben.qits.workspaces.control.FakeCredentialCommissioner;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reconcile: qits-idp is asked what it is holding for this service, and everything no live
 * workspace container claims is given back.
 *
 * <p>This is the structural answer to leaked credentials — the reason there is no TTL on a
 * workspace token and the reason every teardown seam is allowed to be best-effort. What it must get right
 * is the two directions of "spare": a live workspace's credential survives every pass, and anything
 * else does not.
 */
@QuarkusTest
public class CommissionReconcilerTest {

  @Inject CommissionReconciler reconciler;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject WorkspaceRepository workspaceRepository;

  @BeforeEach
  void wireAnIssuer() {
    commissioner.reset();
    commissioner.wire();
  }

  @AfterEach
  void unwireTheIssuer() {
    commissioner.reset();
  }

  @Test
  public void withNoIssuerWiredThereIsNothingToReconcile() {
    commissioner.plantToken(
        "tok-stray",
        CredentialCommissioner.CONTEXT_KIND,
        "999",
        Instant.now().minus(Duration.ofHours(1)));
    commissioner.reset(); // back to the shipped posture

    assertEquals(0, reconciler.reconcile());
    assertEquals(List.of(), commissioner.tokensDeleted());
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
    // An admin-kind token nothing names is the workspace rule's too (qits-628 follow-up).
    commissioner.plantToken(
        "tok-admin-orphan", CredentialCommissioner.ADMIN_CONTEXT_KIND, "999997", old);
    commissioner.plantToken(
        "tok-young", CredentialCommissioner.CONTEXT_KIND, "999998", Instant.now());
    commissioner.plantToken("tok-registration", "workspaces-runner-registration", "r", old);
    try {
      assertEquals(3, reconciler.reapWorkspaceTokens(Instant.now()));

      assertEquals(
          java.util.Set.of("tok-superseded", "tok-orphan", "tok-admin-orphan"),
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

  /**
   * A DIRECT admin row's {@code workspace-admin} token (qits-1084) is judged by the same rule: kept
   * while the row names it, reaped once it is superseded. The row is written straight to the store,
   * which is also the schema saying a DIRECT row may hold a token.
   */
  @Test
  public void aDirectAdminRowsTokenIsKeptWhileClaimedAndASupersededOneIsReaped() {
    Long row = directAdminRowHolding("tok-admin-live");
    Instant old = Instant.now().minus(Duration.ofHours(1));
    commissioner.plantToken(
        "tok-admin-live", CredentialCommissioner.ADMIN_CONTEXT_KIND, row.toString(), old);
    commissioner.plantToken(
        "tok-admin-superseded", CredentialCommissioner.ADMIN_CONTEXT_KIND, row.toString(), old);
    try {
      assertEquals(1, reconciler.reapWorkspaceTokens(Instant.now()));

      assertEquals(List.of("tok-admin-superseded"), commissioner.tokensDeleted());
      assertEquals(List.of("tok-admin-live"), commissioner.liveTokenIds());
    } finally {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  workspaceRepository
                      .findByIdOptional(row)
                      .ifPresent(w -> w.status = WorkspaceStatus.ABANDONED));
    }
  }

  /**
   * A TARGET row's token with no live lease behind it (epic qits-1481): a stopped row whose lease
   * ended, which a crash left holding it. Nothing runs with it, so it is reaped.
   */
  @Test
  public void aStoppedTargetRowsTokenWithNoLeaseIsReaped() {
    Long row = runnerRowHolding("tok-no-lease");
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Workspace w = workspaceRepository.findById(row);
              w.leaseId = null;
              w.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
            });
    commissioner.plantToken(
        "tok-no-lease",
        CredentialCommissioner.CONTEXT_KIND,
        row.toString(),
        Instant.now().minus(Duration.ofHours(1)));

    assertEquals(1, reconciler.reapWorkspaceTokens(Instant.now()));
    assertEquals(List.of("tok-no-lease"), commissioner.tokensDeleted());
  }

  /** An ACTIVE DIRECT admin row holding the workspace token {@code tokenId}. */
  private Long directAdminRowHolding(String tokenId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Workspace w = new Workspace();
              String label = "a" + UUID.randomUUID().toString().substring(0, 8);
              w.workspaceId = label;
              w.repositoryId = "repo-" + label;
              w.branch = label;
              w.status = WorkspaceStatus.ACTIVE;
              w.admin = true;
              w.placement = WorkspacePlacement.DIRECT;
              w.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
              w.commissionedTokenId = tokenId;
              w.commissionedTokenSubject = "tok-workspace-admin-" + label;
              w.commissionedToken = "qits_tok_" + label;
              workspaceRepository.persist(w);
              workspaceRepository.flush();
              return w.id;
            });
  }

  /** An ACTIVE TARGET row holding the workspace token {@code tokenId} of its live lease. */
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
              w.placement = WorkspacePlacement.TARGET;
              w.runtimeStatus = WorkspaceRuntimeStatus.QUEUED;
              w.leaseId = UUID.randomUUID();
              w.commissionedTokenId = tokenId;
              w.commissionedTokenSubject = "tok-workspace-" + label;
              w.commissionedToken = "qits_tok_" + label;
              workspaceRepository.persist(w);
              workspaceRepository.flush();
              return w.id;
            });
  }
}
