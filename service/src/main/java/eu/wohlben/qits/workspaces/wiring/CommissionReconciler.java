package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.CredentialCommissioner;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Gives back the commissioned credentials no container holds any more.
 *
 * <p><b>This is the structural answer to leaked credentials, and it is why there is no TTL.</b> A
 * commission lives as long as its container, so nothing expires on its own — and every teardown seam
 * that hands one back is best-effort by design, because none of them may stand in the way of a
 * removal that has already happened. A crash between the commission and the container is the same
 * story. What all of those leave is a credential qits-idp still honours and nothing can use, and the
 * only way to see one is to ask the issuer what it is holding.
 *
 * <p><b>The workspace tokens</b> (qits-625, qits-802, epic qits-1481). A {@code workspace} token in
 * the token listing is kept when an ACTIVE row names that exact {@code tokenId} as its {@code
 * commissioned_token_id} for a container — a DIRECT row, a TARGET row with a live lease, or a TARGET
 * row a retired runner still runs ({@code WorkspaceRepository#liveCommissionedTokenIds}) — or when an
 * agent that is not REMOVED holds it (qits-1152), and deleted otherwise: its row resolved, its lease
 * ended, its container was deleted or recreated (a newer token replaced it), or a crash lost it
 * between the mint and the lease. A token younger than {@link #TOKEN_GRACE} is spared for that last
 * window, the one a start holds open between the mint and the lease.
 *
 * <p><b>It only ever deletes what the issuer just listed.</b> A listing that could not be read comes
 * back empty, so a qits-idp blip reaps nothing rather than everything; and the kind filter means a
 * token this service one day mints for something else is not swept by the workspace rule. The rule
 * covers BOTH workspace kinds, {@code workspace} and an admin row's {@code workspace-admin} (qits-628
 * follow-up): an admin token is reaped when orphaned and kept while claimed, exactly as a regular one.
 *
 * <p>There is no client arm. This service commissions no idp client any more (qits-876), and the
 * arm that gave back orphaned {@code workspace} clients was retired once it had none left to reap
 * (qits-1683). The retired runners' own client and registration kinds are {@code
 * targethost/LegacyRunnerClients}' to give back, once, at boot.
 *
 * <p>At boot and hourly. Boot catches the crash that lost a decommission; the interval bounds how
 * long anything else lives. Both are best-effort in full: this must never fail a startup and never
 * throw out of a scheduled method.
 */
@ApplicationScoped
public class CommissionReconciler {

  private static final Logger LOG = Logger.getLogger(CommissionReconciler.class);

  /**
   * Optional exactly as it is in {@code WorkspaceService}: absent, or wired with no issuer, means
   * there is nothing out there to reconcile.
   */
  @Inject Instance<CredentialCommissioner> commissioner;

  @Inject WorkspaceRepository workspaces;

  /** The agents, whose own tokens are claimed while they are not REMOVED (qits-1152). */
  @Inject AgentWorktreeRepository agentRows;

  /**
   * How young a workspace token has to be to be spared whatever the rows say: a start mints it first
   * and asks for the lease after. Ten minutes is that moment with a great deal of room, and still far
   * inside the hourly pass.
   */
  static final Duration TOKEN_GRACE = Duration.ofMinutes(10);

  void reconcileAtBoot(@Observes StartupEvent event) {
    reconcile();
  }

  @Scheduled(
      every = "{qits.workspace.commission.reconcile-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void reconcileOnSchedule() {
    reconcile();
  }

  /**
   * One pass. Returns how many credentials it gave back — the number the tests read.
   */
  int reconcile() {
    if (!commissioner.isResolvable()) {
      return 0;
    }
    Instant now = Instant.now();
    return reapWorkspaceTokens(now);
  }

  /** The {@code workspace} arm of the token listing; see the class javadoc. */
  int reapWorkspaceTokens(Instant now) {
    // An agent's token (context agent-<id>) is of the workspace kind too, and claimed by its agent
    // row while that is not REMOVED: see claimedTokenIds.
    try {
      List<CredentialCommissioner.TokenCommission> held =
          commissioner.get().listTokens().stream()
              .filter(t -> CredentialCommissioner.isWorkspaceKind(t.contextKind()))
              .toList();
      if (held.isEmpty()) {
        return 0;
      }
      Set<String> claimed = Set.copyOf(claimedTokenIds());
      int reaped = 0;
      for (CredentialCommissioner.TokenCommission token : held) {
        if (token.tokenId() == null || claimed.contains(token.tokenId())) {
          continue;
        }
        if (token.createdAt() != null && token.createdAt().isAfter(now.minus(TOKEN_GRACE))) {
          continue;
        }
        LOG.infof(
            "Deleting workspace token %s: no live workspace container holds it (context %s)",
            token.tokenId(), token.contextId());
        commissioner.get().deleteToken(token.tokenId());
        reaped++;
      }
      return reaped;
    } catch (RuntimeException e) {
      // A reconcile is housekeeping. It runs again at the next interval, and a failure here must
      // cost neither a startup nor the scheduler's thread.
      LOG.warnf("Workspace token reconcile did not complete: %s", e.toString());
      return 0;
    }
  }

  /**
   * The workspace token ids ACTIVE rows hold, and the agent token ids ACTIVE and OBSOLETE agents
   * hold (qits-1152), in a transaction of its own — this runs on a scheduler or a startup thread,
   * where none stands. Opened explicitly rather than with {@code @Transactional}, which a call from
   * inside this bean would not go through an interceptor to reach.
   */
  List<String> claimedTokenIds() {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<String> claimed = new ArrayList<>(workspaces.liveCommissionedTokenIds());
              claimed.addAll(agentRows.liveCommissionedTokenIds());
              return claimed;
            });
  }
}
