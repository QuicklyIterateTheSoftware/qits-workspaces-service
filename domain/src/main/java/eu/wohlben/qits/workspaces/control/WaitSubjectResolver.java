package eu.wohlben.qits.workspaces.control;

import java.time.Instant;
import java.util.Optional;

/**
 * Looks up what a declared wait waits on (epic qits-1681, "Resolution at open"): a release request
 * or a work item in qits-projects, a run in qits-ci. {@link AgentWaits} asks it once an agent's wait
 * is committed, and again on a scheduler pass while the wait is OPEN and unresolved.
 *
 * <p>A port, because the subjects live in other contexts and the transport is their REST doors in
 * {@code service} ({@code wiring/HttpWaitSubjectResolver}). Injected as {@code Instance<>}:
 * <b>absent, nothing is resolved</b> — a wait still records its kind and ref from the selection, and
 * its state from the events it listens to; only the subject's id, its state before the first event
 * and a run's expected finish stay unknown.
 */
public interface WaitSubjectResolver {

  /**
   * What a lookup found.
   *
   * @param subjectId the subject's uuid
   * @param subjectQualifiedId its qualified id; null for a run
   * @param state its state (request {@code state}, run {@code status}, item {@code status}); null
   *     for {@link WaitSubjects.Kind#COMMENTS}, which have none
   * @param stateAt when that state began, as the provider says; null when it does not say
   * @param expectedBy runs only: when the run should have finished; null otherwise
   */
  record Resolved(
      String subjectId, String subjectQualifiedId, String state, Instant stateAt, Instant expectedBy) {}

  /**
   * A lookup that failed: the subject does not exist, the provider refused or did not answer.
   * Its message is one short sentence, stored as the wait's {@code subject_error} for the CLI.
   */
  final class Unresolvable extends RuntimeException {
    public Unresolvable(String message) {
      super(message);
    }

    public Unresolvable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Look up the subject {@code ref} of {@code kind}.
   *
   * @param projectId the waiting agent's project (a release request is read under it); may be null
   *     when qits-projects could not name it
   * @return empty when this resolver does not look up that kind: the wait stays unresolved and
   *     nothing is written
   * @throws Unresolvable when the lookup failed
   */
  Optional<Resolved> resolve(WaitSubjects.Kind kind, String ref, String projectId);
}
