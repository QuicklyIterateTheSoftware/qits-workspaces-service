package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.CredentialCommissioner;
import eu.wohlben.qits.workspaces.control.GitRefScopes;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * The workspace rule covers BOTH workspace kinds, {@code workspace} and an admin row's {@code
 * workspace-admin} (qits-628 follow-up): an admin credential is reaped when orphaned and kept while
 * claimed, exactly as a regular one.
 *
 * <p><b>Two more arms on the same pass, for the workspace runners</b> (qits-847), judged by the runner
 * table the way qits-ci's reconciler judges its own. A {@code workspaces-runner} client belongs to
 * the runner its {@code contextId} names, and is decommissioned when that row is gone or names a
 * different client; a row with no client yet spares every client of its runner, because that is a
 * registration between the commission and the write. A {@code workspaces-runner-registration} token
 * (the second listing, {@code GET /idp/api/tokens}) is deleted when no row's {@code
 * registration_token_id} names it: the runner is gone, has registered (the register door clears the
 * id) or holds a newer token. A token younger than {@link #TOKEN_GRACE} is spared, because a create
 * and a rotation commission it before the row names it. Both arms keep the stance above: a listing,
 * or a runner table, that could not be read reaps nothing.
 *
 * <p><b>And a third, for the workspace tokens a RUNNER row holds</b> (qits-625, qits-802). A {@code
 * workspace} token in the token listing is kept when an ACTIVE row names that exact {@code tokenId}
 * as its {@code commissioned_token_id}, and deleted otherwise: its row resolved, its container was
 * deleted or recreated (a newer token replaced it), or a crash lost it between the mint and the
 * write. A token younger than {@link #TOKEN_GRACE} is spared for that last window, the one a start
 * holds open between the mint and the row write.
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

  /** The runner rows the two runner arms judge against. */
  @Inject WorkspaceRunnerRepository runners;

  /** The second listing, a runner's registration tokens, and their deletion. */
  @Inject IdpRunnerCommissioner runnerCommissioner;

  /**
   * How young a registration token has to be to be spared whatever the runner table says. A create
   * and a rotation commission the token first and write the row after; ten minutes is that moment
   * with a great deal of room, and still far inside the hourly pass. qits-ci's value.
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
   *
   * <p>It first sends again every narrowed Git ref list that did not reach qits-idp when the
   * narrowing happened ({@link GitRefScopes#pushPending}). That half never throws either.
   */
  int reconcile() {
    if (!commissioner.isResolvable()) {
      return 0;
    }
    gitRefScopes.pushPending();
    Instant now = Instant.now();
    return reapClients() + reapRegistrationTokens(now) + reapWorkspaceTokens(now);
  }

  /** The {@code workspace} arm of the token listing; see the class javadoc. */
  int reapWorkspaceTokens(Instant now) {
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
      LOG.warnf("Workspace token reconcile did not complete: %s", e.toString());
      return 0;
    }
  }

  /** The client listing: the {@code workspace} arm and the {@code workspaces-runner} arm. */
  private int reapClients() {
    try {
      List<CredentialCommissioner.Commission> held = commissioner.get().list();
      if (held.isEmpty()) {
        return 0;
      }
      Set<String> claimed = Set.copyOf(claimedClientIds());
      // Read only when the listing holds a runner's client, and null when it could not be read.
      Map<String, RunnerCredentials> runnerRows =
          held.stream().anyMatch(c -> IdpRunnerCommissioner.RUNNER_KIND.equals(c.contextKind()))
              ? runnerCredentials()
              : Map.of();
      int reaped = 0;
      for (CredentialCommissioner.Commission commission : held) {
        if (IdpRunnerCommissioner.RUNNER_KIND.equals(commission.contextKind())) {
          reaped += reapRunnerClient(commission, runnerRows);
          continue;
        }
        // Both workspace kinds, regular and admin (qits-628 follow-up), by the one rule.
        if (!CredentialCommissioner.isWorkspaceKind(commission.contextKind())) {
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

  /** One {@code workspaces-runner} client against its runner's row; see the class javadoc. */
  private int reapRunnerClient(
      CredentialCommissioner.Commission commission, Map<String, RunnerCredentials> runnerRows) {
    if (runnerRows == null || commission.clientId() == null) {
      return 0;
    }
    RunnerCredentials row = runnerRows.get(commission.contextId());
    if (row != null && (row.clientId() == null || row.clientId().equals(commission.clientId()))) {
      return 0;
    }
    LOG.infof(
        "Decommissioning runner client %s of runner %s, which %s",
        commission.clientId(),
        commission.contextId(),
        row == null ? "is gone" : "is registered as " + row.clientId());
    commissioner.get().decommission(commission.clientId());
    return 1;
  }

  /** The token listing: the {@code workspaces-runner-registration} arm. */
  int reapRegistrationTokens(Instant now) {
    try {
      Optional<List<IdpRunnerCommissioner.LiveToken>> live = runnerCommissioner.liveTokens();
      if (live.isEmpty()
          || live.get().stream()
              .noneMatch(t -> IdpRunnerCommissioner.REGISTRATION_KIND.equals(t.contextKind()))) {
        return 0;
      }
      Map<String, RunnerCredentials> runnerRows = runnerCredentials();
      if (runnerRows == null) {
        return 0;
      }
      Set<String> referenced = new java.util.HashSet<>();
      runnerRows.values().stream()
          .map(RunnerCredentials::registrationTokenId)
          .filter(java.util.Objects::nonNull)
          .forEach(referenced::add);
      int reaped = 0;
      for (IdpRunnerCommissioner.LiveToken token : live.get()) {
        if (!IdpRunnerCommissioner.REGISTRATION_KIND.equals(token.contextKind())
            || referenced.contains(token.tokenId())) {
          continue;
        }
        if (token.createdAt() != null && token.createdAt().isAfter(now.minus(TOKEN_GRACE))) {
          continue;
        }
        LOG.infof(
            "Deleting registration token %s of runner %s: no runner row names it",
            token.tokenId(), token.contextId());
        runnerCommissioner.deleteToken(token.tokenId());
        reaped++;
      }
      return reaped;
    } catch (RuntimeException e) {
      LOG.warnf("Registration token reconcile did not complete: %s", e.toString());
      return 0;
    }
  }

  /** What one runner row says it holds at qits-idp. {@code clientId} null is unregistered. */
  record RunnerCredentials(String clientId, String registrationTokenId) {}

  /**
   * Every runner's credentials, keyed by the runner id as qits-idp spells a context id, or null when
   * the table could not be read, which reaps nothing.
   */
  private Map<String, RunnerCredentials> runnerCredentials() {
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                Map<String, RunnerCredentials> rows = new HashMap<>();
                for (WorkspaceRunner runner : runners.listAll()) {
                  rows.put(
                      runner.id.toString(),
                      new RunnerCredentials(runner.clientId, runner.registrationTokenId));
                }
                return rows;
              });
    } catch (RuntimeException e) {
      LOG.warnf("Could not read the runners, so no runner credential is reaped: %s", e.toString());
      return null;
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

  /** The workspace token ids ACTIVE rows hold, in a transaction of its own, as above. */
  List<String> claimedTokenIds() {
    return QuarkusTransaction.requiringNew().call(workspaces::liveCommissionedTokenIds);
  }
}
