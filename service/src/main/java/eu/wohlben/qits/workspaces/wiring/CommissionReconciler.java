package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.CredentialCommissioner;
import eu.wohlben.qits.workspaces.control.GitRefScopes;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
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
 * <p><b>The claim is the row, and it is compared by client id rather than by workspace.</b> A
 * credential is kept when an ACTIVE workspace names that exact id — so a recreate, which mints a new
 * pair over the old one, makes the previous client an orphan the instant it is replaced, and a
 * delete-container (which clears the columns while the row stays ACTIVE) does the same.
 *
 * <p><b>It only ever deletes what the issuer just listed.</b> A listing that could not be read comes
 * back empty, so a qits-idp blip reaps nothing rather than everything; and the kind filter means a
 * credential this service one day commissions for something else is not swept by the workspace rule.
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

  /** Sends the Git ref narrowings whose first update did not reach qits-idp. */
  @Inject GitRefScopes gitRefScopes;

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
   *
   * <p>It first sends again every narrowed Git ref list that did not reach qits-idp when the
   * narrowing happened ({@link GitRefScopes#pushPending}). That half never throws either.
   */
  int reconcile() {
    if (!commissioner.isResolvable()) {
      return 0;
    }
    gitRefScopes.pushPending();
    try {
      List<CredentialCommissioner.Commission> held = commissioner.get().list();
      if (held.isEmpty()) {
        return 0;
      }
      Set<String> claimed = Set.copyOf(claimedClientIds());
      int reaped = 0;
      for (CredentialCommissioner.Commission commission : held) {
        if (!CredentialCommissioner.CONTEXT_KIND.equals(commission.contextKind())) {
          continue;
        }
        if (commission.clientId() == null || claimed.contains(commission.clientId())) {
          continue;
        }
        LOG.infof(
            "Decommissioning %s: no live workspace container claims it (context %s)",
            commission.clientId(), commission.contextId());
        commissioner.get().decommission(commission.clientId());
        reaped++;
      }
      return reaped;
    } catch (RuntimeException e) {
      // A reconcile is housekeeping. It runs again at the next interval, and a failure here must
      // cost neither a startup nor the scheduler's thread.
      LOG.warnf("Commission reconcile did not complete: %s", e.toString());
      return 0;
    }
  }

  /**
   * The client ids live workspaces claim, in a transaction of its own — this runs on a scheduler or
   * a startup thread, where none stands. Opened explicitly rather than with {@code @Transactional},
   * which a call from inside this bean would not go through an interceptor to reach.
   */
  List<String> claimedClientIds() {
    return QuarkusTransaction.requiringNew().call(workspaces::liveCommissionedClientIds);
  }
}
