package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * <b>Whether an agent needs a person</b> (epic qits-1681, "Attention"), and why, in one line. Pure:
 * no bean, no database, no clock — the agent row, its OPEN waits, the instant and the thresholds
 * are all passed in. {@link AgentAttentions} stores the answer and announces its changes.
 *
 * <p>Only an ACTIVE agent needs attention. The first rule that holds wins:
 *
 * <ol>
 *   <li>{@link Code#WAIT_SUBJECT_PASSED} — an OPEN wait whose subject's recorded state is one the
 *       wait names (it should have matched), or final for its kind and not one it names (it never
 *       will). Since the state was seen to begin.
 *   <li>{@link Code#BLOCKED_NO_DESK} — an explicit block ({@code EXPLICIT} or {@code BOTH}) standing
 *       for {@code blocked-after}, with no {@code @desk} comment on the item since it began. Since
 *       the block began.
 *   <li>{@link Code#IDLE_NO_WAIT} — RUNNING and waiting for input for {@code idle-after}, with no
 *       OPEN wait, no turn still to be said, no explicit block (a block with no source counts as
 *       one; the derived {@code AGENT_WAITING} block alone is the stall itself, as the nudge reads
 *       it) and an item that is not between phases ({@link AgentService#isBetweenPhases}). Since
 *       it stopped.
 *   <li>{@link Code#WAIT_OVERDUE} — an OPEN wait on a run still QUEUED or RUNNING past its expected
 *       finish (since that finish), or on a release request in a state that is not final and has
 *       not changed for {@code request-overdue-after} (since that threshold passed).
 * </ol>
 *
 * Times in a reason are UTC {@code HH:mm}; durations {@code N min}, or {@code N h M min} from an
 * hour on. Codes 1 and 4 name the wait.
 */
public final class AgentAttention {

  /** Why an agent needs a person. */
  public enum Code {
    WAIT_SUBJECT_PASSED,
    BLOCKED_NO_DESK,
    IDLE_NO_WAIT,
    WAIT_OVERDUE
  }

  /**
   * What an agent needs a person for.
   *
   * @param waitId the wait it is about, for {@link Code#WAIT_SUBJECT_PASSED} and {@link
   *     Code#WAIT_OVERDUE}; null otherwise
   */
  public record Attention(Code code, String reason, Instant since, UUID waitId) {}

  /**
   * The thresholds, {@code qits.workspaces.attention.*}.
   *
   * @param idleAfter how long an agent waits for input with nothing declared before it is idle
   * @param blockedAfter how long an explicit block stands with no {@code @desk} comment
   * @param requestOverdueAfter how long a release request may stay in one unfinished state
   */
  public record Config(Duration idleAfter, Duration blockedAfter, Duration requestOverdueAfter) {}

  /** The block sources that are a block somebody set, rather than one derived from the agent. */
  static final Set<String> EXPLICIT_SOURCES = Set.of("EXPLICIT", "BOTH");

  private static final DateTimeFormatter HH_MM =
      DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

  private AgentAttention() {}

  /** What {@code agent} needs a person for at {@code now}; empty when nothing (or not ACTIVE). */
  public static Optional<Attention> evaluate(
      AgentWorktree agent, List<AgentWait> openWaits, Instant now, Config config) {
    if (agent == null || agent.state != AgentState.ACTIVE) {
      return Optional.empty();
    }
    List<AgentWait> open =
        (openWaits == null ? List.<AgentWait>of() : openWaits)
            .stream()
                .filter(w -> w != null && w.state == AgentWaitState.OPEN)
                .sorted(
                    Comparator.comparing(
                        (AgentWait w) -> w.createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    return subjectPassed(open)
        .or(() -> blockedNoDesk(agent, now, config))
        .or(() -> idleNoWait(agent, open, now, config))
        .or(() -> overdue(open, now, config));
  }

  // --- the four rules -----------------------------------------------------------------------------

  private static Optional<Attention> subjectPassed(List<AgentWait> open) {
    for (AgentWait wait : open) {
      WaitSubjects.Kind kind = WaitSubjects.kindOf(wait.subjectKind);
      String state = wait.subjectState;
      if (kind == null || state == null) {
        continue;
      }
      List<String> targets = targets(wait);
      boolean reached = !targets.isEmpty() && targets.contains(state);
      boolean ended = WaitSubjects.isFinal(kind, state) && !targets.contains(state);
      if (!reached && !ended) {
        continue;
      }
      Instant since = wait.subjectStateAt != null ? wait.subjectStateAt : wait.createdAt;
      String reason =
          "Waits on "
              + nameOf(wait, kind)
              + (waitsForTheEnd(kind, targets) ? " to end" : " to be " + String.join(" or ", targets))
              + "; it is "
              + state
              + (since == null ? "" : " since " + HH_MM.format(since));
      return Optional.of(new Attention(Code.WAIT_SUBJECT_PASSED, reason, since, wait.id));
    }
    return Optional.empty();
  }

  private static Optional<Attention> blockedNoDesk(AgentWorktree agent, Instant now, Config config) {
    if (!explicitlyBlocked(agent) || agent.blockedAt == null) {
      return Optional.empty();
    }
    Duration blocked = Duration.between(agent.blockedAt, now);
    if (blocked.compareTo(config.blockedAfter()) < 0) {
      return Optional.empty();
    }
    if (agent.deskAskedAt != null && !agent.deskAskedAt.isBefore(agent.blockedAt)) {
      return Optional.empty();
    }
    String author = agent.blockAuthor == null || agent.blockAuthor.isBlank() ? null : agent.blockAuthor;
    String reason =
        "Blocked "
            + duration(blocked)
            + (author == null ? "" : " by " + author)
            + ", no @desk comment since";
    return Optional.of(new Attention(Code.BLOCKED_NO_DESK, reason, agent.blockedAt, null));
  }

  private static Optional<Attention> idleNoWait(
      AgentWorktree agent, List<AgentWait> open, Instant now, Config config) {
    if (agent.runState != AgentRunState.RUNNING
        || agent.waitingSince == null
        || !open.isEmpty()
        || agent.pendingText != null
        || exemptingBlock(agent)
        || AgentService.isBetweenPhases(agent.entityStatus)) {
      return Optional.empty();
    }
    Duration idle = Duration.between(agent.waitingSince, now);
    if (idle.compareTo(config.idleAfter()) < 0) {
      return Optional.empty();
    }
    return Optional.of(
        new Attention(
            Code.IDLE_NO_WAIT,
            "Idle " + duration(idle) + " with no wait and no block",
            agent.waitingSince,
            null));
  }

  private static Optional<Attention> overdue(List<AgentWait> open, Instant now, Config config) {
    for (AgentWait wait : open) {
      WaitSubjects.Kind kind = WaitSubjects.kindOf(wait.subjectKind);
      String state = wait.subjectState;
      if (kind == WaitSubjects.Kind.CI_RUN
          && state != null
          && WaitSubjects.CI_RUN_ACTIVE.contains(state)
          && wait.subjectExpectedBy != null
          && now.isAfter(wait.subjectExpectedBy)) {
        return Optional.of(
            new Attention(Code.WAIT_OVERDUE, runOverdue(wait, kind, now), wait.subjectExpectedBy, wait.id));
      }
      if (kind == WaitSubjects.Kind.RELEASE_REQUEST
          && state != null
          && !WaitSubjects.isFinal(kind, state)
          && wait.subjectStateAt != null) {
        Duration unchanged = Duration.between(wait.subjectStateAt, now);
        if (unchanged.compareTo(config.requestOverdueAfter()) >= 0) {
          return Optional.of(
              new Attention(
                  Code.WAIT_OVERDUE,
                  nameOf(wait, kind) + " " + state + ", unchanged for " + duration(unchanged),
                  wait.subjectStateAt.plus(config.requestOverdueAfter()),
                  wait.id));
        }
      }
    }
    return Optional.empty();
  }

  /**
   * A RUNNING run with a known start says how long it ran and how long it was expected to take (the
   * expected finish less that start); otherwise, when it should have finished.
   */
  private static String runOverdue(AgentWait wait, WaitSubjects.Kind kind, Instant now) {
    String named = nameOf(wait, kind);
    String name = named.isEmpty() ? named : Character.toUpperCase(named.charAt(0)) + named.substring(1);
    String state = wait.subjectState;
    Instant since = wait.subjectStateAt;
    String forHowLong = since == null ? "" : " for " + duration(Duration.between(since, now));
    if ("RUNNING".equals(state) && since != null && wait.subjectExpectedBy.isAfter(since)) {
      return name
          + " RUNNING"
          + forHowLong
          + ", expected about "
          + duration(Duration.between(since, wait.subjectExpectedBy));
    }
    return name
        + " "
        + state
        + forHowLong
        + ", past its expected finish at "
        + HH_MM.format(wait.subjectExpectedBy);
  }

  // --- helpers ------------------------------------------------------------------------------------

  /** An {@code EXPLICIT} or {@code BOTH} block: the one {@code blocked_at} times. */
  static boolean explicitlyBlocked(AgentWorktree agent) {
    return Boolean.TRUE.equals(agent.entityBlocked)
        && agent.blockSource != null
        && EXPLICIT_SOURCES.contains(agent.blockSource.toUpperCase(Locale.ROOT));
  }

  /**
   * A block that keeps an idle agent from being idle: an explicit one, or one with no source (read
   * as explicit, as the nudge reads it). The derived {@code AGENT_WAITING} block alone is not.
   */
  private static boolean exemptingBlock(AgentWorktree agent) {
    return Boolean.TRUE.equals(agent.entityBlocked)
        && (agent.blockSource == null
            || agent.blockSource.isBlank()
            || explicitlyBlocked(agent));
  }

  private static List<String> targets(AgentWait wait) {
    List<String> stored = AgentWaits.targetStatesOf(wait.targetStates);
    return stored == null ? List.of() : stored;
  }

  /** "to end" when the wait names no state, or only final ones; else the states it names. */
  private static boolean waitsForTheEnd(WaitSubjects.Kind kind, List<String> targets) {
    return targets.isEmpty() || targets.stream().allMatch(t -> WaitSubjects.isFinal(kind, t));
  }

  /**
   * What a reason calls the subject: a run by its id's first eight characters, anything else by its
   * qualified id, else by the ref the selection names; the wait's label when there is neither.
   */
  static String nameOf(AgentWait wait, WaitSubjects.Kind kind) {
    if (kind == WaitSubjects.Kind.CI_RUN) {
      String id = wait.subjectId != null ? wait.subjectId : wait.subjectRef;
      if (id != null) {
        return "run " + (id.length() > 8 ? id.substring(0, 8) : id);
      }
    } else if (wait.subjectQualifiedId != null) {
      return wait.subjectQualifiedId;
    } else if (wait.subjectRef != null) {
      return wait.subjectRef;
    }
    return wait.label;
  }

  /** {@code N min} below an hour, {@code N h M min} from an hour on; whole minutes, rounded down. */
  static String duration(Duration d) {
    long minutes = Math.max(0, d.toMinutes());
    if (minutes < 60) {
      return minutes + " min";
    }
    return (minutes / 60) + " h " + (minutes % 60) + " min";
  }
}
