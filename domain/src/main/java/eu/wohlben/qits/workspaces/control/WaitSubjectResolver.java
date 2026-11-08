package eu.wohlben.qits.workspaces.control;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Looks up what a declared wait waits on (epic qits-1681, "Resolution at open"): a release request,
 * a work item or a branch's deliveries (qits-1961) in qits-projects, a run in qits-ci. {@link
 * AgentWaits} asks it once a wait is committed, and again on a scheduler pass while the wait is OPEN
 * — every {@code AgentWaits.RECHECK_EVERY} since qits-1961, not only while it is unresolved.
 *
 * <p>A port, because the subjects live in other contexts and the transport is their REST doors in
 * {@code service} ({@code wiring/HttpWaitSubjectResolver}). Injected as {@code Instance<>}:
 * <b>absent, nothing is resolved</b> — a wait still records its kind and ref from the selection, and
 * its state from the events it listens to; only the subject's id, its state before the first event
 * and a run's expected finish stay unknown, and nothing breaks it but its ceiling.
 */
public interface WaitSubjectResolver {

  /**
   * What a lookup found.
   *
   * @param subjectId the subject's uuid; null for a branch
   * @param subjectQualifiedId its qualified id; null for a run and a branch
   * @param state its state (request {@code state}, run {@code status}, item {@code status}); null
   *     for {@link WaitSubjects.Kind#COMMENTS}, which have none, and for a branch, whose state
   *     depends on what the wait reads ({@link WaitSubjects#branchReading})
   * @param stateAt when that state began, as the provider says; null when it does not say
   * @param expectedBy runs only: when the run should have finished; null otherwise
   * @param detail requests only (qits-1961): the provider's one-line {@code detail} on its state
   *     (a rejection's reason); null otherwise
   * @param branch branches only (qits-1961): the branch's deliveries; null otherwise
   */
  record Resolved(
      String subjectId,
      String subjectQualifiedId,
      String state,
      Instant stateAt,
      Instant expectedBy,
      String detail,
      Branch branch) {

    /** A lookup of anything but a branch, with no detail. */
    public Resolved(
        String subjectId, String subjectQualifiedId, String state, Instant stateAt, Instant expectedBy) {
      this(subjectId, subjectQualifiedId, state, stateAt, expectedBy, null, null);
    }

    /** A branch's lookup: its deliveries, and nothing else. */
    public static Resolved ofBranch(Branch branch) {
      return new Resolved(null, null, null, null, null, null, branch);
    }
  }

  /**
   * A branch's deliveries in its project (qits-1961, qits-projects' {@code getBranchDeliveries}).
   * A branch the ledger never followed has no rows and no {@code everywhere}.
   *
   * @param everywhere the least advanced step over the repositories it still ships to; null when no
   *     row counts
   * @param deliveries one row per repository the branch was pushed to or delivered in
   */
  record Branch(String everywhere, List<Delivery> deliveries) {
    public Branch {
      deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
    }
  }

  /**
   * One repository's last delivery step.
   *
   * @param repository the repository's name, as {@code BranchDeliveryChanged} carries it
   * @param step PUSHED, JOINED, MOVED, FAILED, CONFLICTED, WITHDRAWN, RELEASED or LIVE
   * @param qualifiedId the request carrying the branch; null on PUSHED
   * @param runId FAILED only: the red run
   * @param changedAt when the step was written
   */
  record Delivery(String repository, String step, String qualifiedId, String runId, Instant changedAt) {}

  /**
   * A lookup that failed: the subject does not exist, the provider refused or did not answer.
   * Its message is one short sentence, stored as the wait's {@code subject_error} for the CLI.
   */
  class Unresolvable extends RuntimeException {
    public Unresolvable(String message) {
      super(message);
    }

    public Unresolvable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * The provider answered that it knows no such subject — a 404, and nothing else (qits-1961). The
   * one failure that breaks a wait: a 5xx, another status or no answer at all is only retried.
   */
  final class NotFound extends Unresolvable {
    public NotFound(String message) {
      super(message);
    }

    public NotFound(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Look up the subject {@code ref} of {@code kind}.
   *
   * @param projectId the waiting agent's project, or the desk's (a release request and a branch are
   *     read under it); may be null when qits-projects could not name it
   * @return empty when this resolver does not look up that kind: the wait stays unresolved and
   *     nothing is written
   * @throws NotFound when the provider knows no such subject
   * @throws Unresolvable when the lookup failed otherwise
   */
  Optional<Resolved> resolve(WaitSubjects.Kind kind, String ref, String projectId);
}
