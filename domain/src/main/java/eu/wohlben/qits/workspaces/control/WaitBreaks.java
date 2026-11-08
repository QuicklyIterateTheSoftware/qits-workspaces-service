package eu.wohlben.qits.workspaces.control;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * <b>When a wait can no longer be satisfied</b> (qits-1961): the rules that close an OPEN wait
 * BROKEN, read off its subject as a lookup just found it. Pure, beside {@link WaitSubjects#isFinal}:
 * no bean, no database, no clock — the wait's facts, the lookup and the instant are passed in.
 * {@link AgentWaits} asks after every lookup, at the declaration and on each re-check, and only
 * once a target state the lookup found has not closed the wait MATCHED first (qits-1668).
 *
 * <p>A wait breaks when its subject, freshly looked up:
 *
 * <ul>
 *   <li>is <b>final</b> for its kind and not a target ({@link Why#FINAL}): a request FINALIZED,
 *       WITHDRAWN or OBSOLETE; a run in anything but QUEUED and RUNNING; an item DONE or DROPPED; a
 *       branch LIVE or WITHDRAWN. What {@code AgentAttention}'s "ended" case reported to people
 *       before.
 *   <li>is <b>stuck</b> and not a target, unchanged for {@link #STUCK_AFTER} ({@link Why#STUCK}): a
 *       request REJECTED or CONFLICTED, a branch step FAILED or CONFLICTED. The grace is there
 *       because a REJECTED request reopens on its own after a push or a retry (qits-1731).
 *   <li><b>does not exist</b> ({@link Why#NOT_FOUND}): the provider answered 404 ({@link
 *       WaitSubjectResolver.NotFound}). Nothing else a provider says, or fails to say, breaks a
 *       wait: a 5xx or no answer is retried as before.
 *   <li>is a <b>branch the ledger does not follow</b> ({@link Why#UNFOLLOWED}): {@code
 *       branch-deliveries} answers no row (none in the wait's repository, when it names one) {@link
 *       #UNFOLLOWED_AFTER} after the wait was declared — a maintenance branch, a mistyped one
 *       (qits-1952).
 * </ul>
 *
 * <p><b>A branch</b> is read the way its events are ({@link WaitSubjects#branchRows}): the wait's
 * repository's row when it names one; for an {@code everywhere} wait, {@code everywhere} — and since
 * the least advanced step cannot pass a stuck row, also any row stuck for the grace; for a wait that
 * names neither, it breaks only when every row is final or stuck and none is a target. COMMENTS break
 * only on a 404, and OTHER waits never: nothing is looked up for them, and only the ceiling ends
 * them.
 *
 * <p>The {@link Broken#reason} is the platform's one line, {@code <subject> is <state> since HH:mm
 * UTC[ in <request>][: <detail>]}: ids and states, and a request's {@code detail} only quoted.
 */
public final class WaitBreaks {

  /** How long a stuck state must stand before it breaks a wait. */
  public static final Duration STUCK_AFTER = Duration.ofMinutes(30);

  /** How long after its declaration a branch wait may find no delivery row. */
  public static final Duration UNFOLLOWED_AFTER = Duration.ofMinutes(30);

  /** A release request's stuck states: it may reopen, so they break a wait only after the grace. */
  public static final Set<String> RELEASE_REQUEST_STUCK = Set.of("REJECTED", "CONFLICTED");

  /** A branch's stuck steps. */
  public static final Set<String> BRANCH_STUCK = Set.of("FAILED", "CONFLICTED");

  /** At most this many characters of a provider's {@code detail} in a reason. */
  static final int MAX_DETAIL = 160;

  /** Why a wait broke. */
  public enum Why {
    FINAL,
    STUCK,
    NOT_FOUND,
    UNFOLLOWED
  }

  /** A broken wait: why, and the one line that says so. */
  public record Broken(Why why, String reason) {}

  /**
   * The subject as a lookup saw it.
   *
   * @param name what the reason calls it ({@code AgentAttention.nameOf}, a branch with its
   *     repository)
   * @param state its state; null when the lookup said none
   * @param since when that state began; null when unknown
   * @param in the release request carrying it (a branch's row); null otherwise
   * @param detail what the provider adds (a request's {@code detail}, a failed step's run); null
   *     when nothing
   */
  public record Seen(String name, String state, Instant since, String in, String detail) {}

  private WaitBreaks() {}

  /** Whether {@code state} is stuck for {@code kind}: it may move again, but not by itself soon. */
  public static boolean isStuck(WaitSubjects.Kind kind, String state) {
    if (kind == null || state == null) {
      return false;
    }
    return switch (kind) {
      case RELEASE_REQUEST -> RELEASE_REQUEST_STUCK.contains(state);
      case BRANCH -> BRANCH_STUCK.contains(state);
      case CI_RUN, WORK_ITEM, COMMENTS, OTHER -> false;
    };
  }

  /**
   * The final and stuck rules on one subject state: empty when the state is none, a target, or
   * neither final nor stuck long enough. Comments and OTHER have no state that breaks.
   */
  public static Optional<Broken> seen(
      WaitSubjects.Kind kind, List<String> targets, Seen seen, Instant now) {
    if (kind == null || seen == null || seen.state() == null || targets(targets).contains(seen.state())) {
      return Optional.empty();
    }
    if (WaitSubjects.isFinal(kind, seen.state())) {
      return Optional.of(new Broken(Why.FINAL, reason(seen)));
    }
    if (stuckLongEnough(kind, seen, now)) {
      return Optional.of(new Broken(Why.STUCK, reason(seen)));
    }
    return Optional.empty();
  }

  /**
   * The provider knows no such subject (a 404): every kind with a lookup breaks; OTHER, which has
   * none, never does.
   *
   * @param message the resolver's sentence, as stored in {@code subject_error}
   */
  public static Optional<Broken> notFound(WaitSubjects.Kind kind, String name, String message) {
    if (kind == null || kind == WaitSubjects.Kind.OTHER) {
      return Optional.empty();
    }
    String said = message == null ? "" : message.strip();
    if (said.endsWith(".")) {
      said = said.substring(0, said.length() - 1);
    }
    return Optional.of(
        new Broken(Why.NOT_FOUND, name + " does not exist" + (said.isEmpty() ? "" : ": " + said)));
  }

  /**
   * The rules on a branch's lookup. {@code name} is the branch as a reason calls it; {@code
   * createdAt} when the wait was declared, for {@link Why#UNFOLLOWED}.
   */
  public static Optional<Broken> branch(
      WaitSubjects.Subject subject,
      String name,
      List<String> targets,
      WaitSubjectResolver.Branch branch,
      Instant createdAt,
      Instant now) {
    List<String> wanted = targets(targets);
    List<WaitSubjectResolver.Delivery> rows = WaitSubjects.branchRows(subject, branch);
    String repository = subject == null ? null : subject.repository();
    if (rows.isEmpty()) {
      if (createdAt == null || now.isBefore(createdAt.plus(UNFOLLOWED_AFTER))) {
        return Optional.empty();
      }
      return Optional.of(
          new Broken(
              Why.UNFOLLOWED,
              name
                  + " has no delivery"
                  + (repository == null ? "" : " in " + repository)
                  + " "
                  + AgentAttention.duration(Duration.between(createdAt, now))
                  + " after the wait was declared: the delivery ledger does not follow it, so no"
                  + " BranchDeliveryChanged is coming"));
    }
    boolean everywhere = subject != null && "everywhere".equals(subject.statePath());
    if (everywhere && branch.everywhere() != null) {
      String at = branch.everywhere();
      Instant since =
          rows.stream()
              .filter(row -> at.equals(row.step()))
              .map(WaitSubjectResolver.Delivery::changedAt)
              .filter(java.util.Objects::nonNull)
              .reduce((a, b) -> b)
              .orElse(null);
      Optional<Broken> ended =
          seen(WaitSubjects.Kind.BRANCH, wanted, new Seen(name, at, since, null, null), now)
              .filter(b -> b.why() == Why.FINAL);
      if (ended.isPresent()) {
        return ended;
      }
      // The least advanced step cannot pass a stuck row: the one stuck longest breaks the wait.
      for (WaitSubjectResolver.Delivery row : rows) {
        Seen seen = seenOf(name, row);
        if (!wanted.contains(row.step()) && stuckLongEnough(WaitSubjects.Kind.BRANCH, seen, now)) {
          return Optional.of(new Broken(Why.STUCK, reason(seen)));
        }
      }
      return Optional.empty();
    }
    if (!everywhere && (repository != null || rows.size() == 1)) {
      WaitSubjectResolver.Delivery row = rows.get(rows.size() - 1);
      return seen(WaitSubjects.Kind.BRANCH, wanted, seenOf(name, row), now);
    }
    // No single row says the state: every one must have ended or be stuck, and none a target.
    Broken newest = null;
    for (WaitSubjectResolver.Delivery row : rows) {
      Optional<Broken> one = seen(WaitSubjects.Kind.BRANCH, wanted, seenOf(name, row), now);
      if (one.isEmpty()) {
        return Optional.empty();
      }
      newest = one.get();
    }
    return Optional.of(
        new Broken(
            newest.why(),
            newest.reason()
                + (rows.size() > 1
                    ? " (and the delivery in each of its other " + (rows.size() - 1)
                        + " repositories has ended or is stuck too)"
                    : "")));
  }

  /** {@code <subject> is <state> since HH:mm UTC[ in <request>][: <detail>]}. */
  static String reason(Seen seen) {
    return seen.name()
        + " is "
        + AgentWaits.bounded(seen.state(), AgentWaits.MAX_VALUE_SHOWN)
        + (seen.since() == null ? "" : " since " + AgentAttention.HH_MM.format(seen.since()) + " UTC")
        + (seen.in() == null ? "" : " in " + seen.in())
        + (seen.detail() == null || seen.detail().isBlank() ? "" : ": " + seen.detail());
  }

  /** A provider's own text in a reason: one line, quoted, cut short. */
  static String quotedDetail(String detail) {
    return detail == null || detail.isBlank()
        ? null
        : AgentWaits.quoted(AgentWaits.bounded(detail.strip(), MAX_DETAIL));
  }

  private static Seen seenOf(String name, WaitSubjectResolver.Delivery row) {
    return new Seen(
        name + (row.repository() == null ? "" : " in " + row.repository()),
        row.step(),
        row.changedAt(),
        row.qualifiedId(),
        row.runId() == null ? null : "run " + row.runId());
  }

  private static boolean stuckLongEnough(WaitSubjects.Kind kind, Seen seen, Instant now) {
    return isStuck(kind, seen.state())
        && seen.since() != null
        && !now.isBefore(seen.since().plus(STUCK_AFTER));
  }

  private static List<String> targets(List<String> targets) {
    return targets == null ? List.of() : targets;
  }
}
