package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.jboss.logging.Logger;

/**
 * The narrowing of contract C5 (principal-bound-git-refs-plan.md): when a workspace is created on a
 * branch that another open workspace in the same project may push, that branch leaves the other
 * workspace's list, and the other workspace's live commission is told.
 *
 * <p>The case it exists for: an epic workspace may push the epic branch and every feature and task
 * branch of the epic. When a task gets a workspace of its own, the epic agent must stop pushing that
 * task's branch — the task agent owns it now.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li><b>Exact refs only.</b> Only an entry equal to {@code refs/heads/<new branch>} is removed. A
 *       {@code /*} pattern is left as it is.
 *   <li><b>A workspace keeps its own branch.</b> The entry naming the other workspace's own branch
 *       is never removed: that branch is what it works on, and taking it away would leave it unable
 *       to push its own work.
 *   <li><b>Same project.</b> Git refs have no repository in them, so a list applies to every
 *       repository of the credential's project. A workspace on the same repository is in the same
 *       project; for another repository the registry is asked. A registry that cannot answer leaves
 *       the other list as it is — the scope costs, the creation does not.
 *   <li><b>No widening.</b> When the narrowing workspace closes, nothing is given back. Its branch
 *       is normally merged and deleted by then, and a list that grew again without a person asking
 *       would undo the separation for the next workspace on that branch.
 * </ul>
 *
 * <h2>Where the update goes</h2>
 *
 * <p>The list changes in the creating transaction. A row with a live commission is also marked
 * {@link Workspace#gitRefsPending}; after the transaction commits, the {@code PUT} to qits-idp runs
 * on a thread of this bean's own, so a slow or refusing idp never delays or fails the creation. A
 * {@code PUT} that fails leaves the flag set, and the commission reconcile sends it again ({@link
 * #pushPending}). A row with no commission needs no {@code PUT}: its next commission states the
 * stored list.
 */
@ApplicationScoped
public class GitRefScopes {

  private static final Logger LOG = Logger.getLogger(GitRefScopes.class);

  /** Fired inside the creating transaction; delivered after it commits. */
  public record GitRefsNarrowed(List<Long> rowIds) {}

  @Inject WorkspaceRepository workspaces;

  @Inject RepositoryLookup repositories;

  /** Optional, like everywhere else: no issuer means no commission to update. */
  @Inject Instance<CredentialCommissioner> commissioner;

  @Inject Event<GitRefsNarrowed> narrowed;

  /** One thread: a narrowing is rare, and one at a time keeps the updates in order. */
  private final ExecutorService pushes =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-git-ref-narrowing");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    pushes.shutdownNow();
  }

  /**
   * Narrow every other open workspace that may push {@code created}'s branch. Runs inside the
   * transaction that writes {@code created}; changes rows only.
   *
   * @param repository the registry's view of {@code created}'s repository, already read by the
   *     creation
   */
  void narrowFor(Workspace created, RepositoryLookup.RepositoryView repository) {
    if (created.branch == null) {
      return;
    }
    String ref = GitRefs.of(created.branch);
    List<Long> toUpdate = new ArrayList<>();
    for (Workspace other : workspaces.findActiveWithGitRefs()) {
      if (Objects.equals(other.id, created.id)) {
        continue;
      }
      List<String> refs = GitRefs.read(other.gitRefs);
      if (!refs.contains(ref)) {
        continue;
      }
      if (other.branch != null && ref.equals(GitRefs.of(other.branch))) {
        LOG.debugf(
            "Workspace %s keeps %s: it is that workspace's own branch", other.id, ref);
        continue;
      }
      if (!sameProject(repository, other.repositoryId)) {
        continue;
      }
      other.gitRefs = GitRefs.write(GitRefs.without(refs, ref));
      if (other.commissionedClientId != null) {
        other.gitRefsPending = true;
        toUpdate.add(other.id);
      }
      LOG.infof(
          "Workspace %s may no longer push %s: workspace %s now works on that branch",
          other.id, ref, created.id);
    }
    if (!toUpdate.isEmpty()) {
      narrowed.fire(new GitRefsNarrowed(List.copyOf(toUpdate)));
    }
  }

  /** After the creation committed: send each narrowed list to qits-idp, off the caller's thread. */
  void onNarrowed(@Observes(during = TransactionPhase.AFTER_SUCCESS) GitRefsNarrowed event) {
    try {
      pushes.submit(() -> event.rowIds().forEach(this::push));
    } catch (RejectedExecutionException shuttingDown) {
      LOG.warnf(
          "Could not send narrowed Git refs for workspaces %s now; the reconcile will send them",
          event.rowIds());
    }
  }

  /**
   * Send every pending narrowing again. The commission reconcile calls this; it never throws.
   *
   * @return how many updates landed
   */
  public int pushPending() {
    if (!commissioner.isResolvable()) {
      return 0;
    }
    List<Long> pending;
    try {
      pending = QuarkusTransaction.requiringNew().call(workspaces::pendingGitRefIds);
    } catch (RuntimeException e) {
      LOG.warnf("Could not read the pending Git ref narrowings: %s", e.toString());
      return 0;
    }
    int landed = 0;
    for (Long rowId : pending) {
      if (push(rowId)) {
        landed++;
      }
    }
    return landed;
  }

  /** What one update sends: the client, and the stored list as it was read. */
  private record Update(String clientId, String storedRefs) {}

  /**
   * Send one workspace's stored list to its commission, and clear the flag when it landed — only if
   * the row still holds the same client and the same list, so a newer narrowing is never marked as
   * sent.
   */
  boolean push(Long rowId) {
    if (!commissioner.isResolvable()) {
      return false;
    }
    Optional<Update> update;
    try {
      update =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      workspaces
                          .findActiveById(rowId)
                          .filter(w -> w.gitRefsPending && w.commissionedClientId != null)
                          .map(w -> new Update(w.commissionedClientId, w.gitRefs)));
    } catch (RuntimeException e) {
      LOG.warnf("Could not read workspace %s to send its Git refs: %s", rowId, e.toString());
      return false;
    }
    if (update.isEmpty()) {
      return false;
    }
    String clientId = update.get().clientId();
    String stored = update.get().storedRefs();
    try {
      commissioner.get().updateGitRefs(clientId, GitRefs.read(stored));
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not send the narrowed Git refs of workspace %s to qits-idp; the reconcile will"
              + " send them again: %s",
          rowId, e.toString());
      return false;
    }
    try {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  workspaces
                      .findActiveById(rowId)
                      .filter(
                          w ->
                              clientId.equals(w.commissionedClientId)
                                  && Objects.equals(stored, w.gitRefs))
                      .ifPresent(w -> w.gitRefsPending = false));
    } catch (RuntimeException e) {
      // The update landed; the flag only makes the reconcile send the same list once more.
      LOG.debugf("Could not clear the pending flag of workspace %s: %s", rowId, e.toString());
    }
    return true;
  }

  /**
   * Whether {@code otherRepoId} is in {@code created}'s project. The same repository needs no
   * question. For another one the registry is asked, and "could not ask" reads as "no" — an
   * unnarrowed list is the pre-C5 state, a failed creation is not.
   */
  private boolean sameProject(RepositoryLookup.RepositoryView created, String otherRepoId) {
    if (created.id().equals(otherRepoId)) {
      return true;
    }
    String project = created.projectId();
    if (project == null || project.isBlank()) {
      return false;
    }
    try {
      return repositories
          .find(otherRepoId)
          .map(RepositoryLookup.RepositoryView::projectId)
          .filter(project::equals)
          .isPresent();
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not tell whether repository %s is in project %s; its workspaces keep their Git"
              + " refs: %s",
          otherRepoId, project, e.toString());
      return false;
    }
  }
}
