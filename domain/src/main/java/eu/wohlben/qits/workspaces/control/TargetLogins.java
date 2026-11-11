package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.AgentHarness;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>Which execution targets lost a harness's sign-in</b> (qits-2088), as qits-system last said:
 * its {@code TargetLoginChanged} as it happens and its target listing on a reconcile, both fed in
 * by {@code targethost/TargetLoginEvents}. {@link AgentAttentions} asks it for the agent's target
 * and harness, and {@link AgentAttention} turns an absence into {@link
 * AgentAttention.Code#TARGET_LOGIN_ABSENT}.
 *
 * <p><b>In memory, on purpose.</b> The state is qits-system's, and the listing carries all of it, so
 * a restart loses nothing a reconcile does not give back — the first pass runs at boot. No table,
 * no migration. What a restart does lose is when a target was first seen ABSENT: the seed takes the
 * listing's {@code checkedAt}, the newest probe, rather than the start of the absence.
 *
 * <p><b>Only {@code ABSENT} is an absence.</b> {@code PRESENT} clears it, and so does {@code
 * UNKNOWN} (nothing probed yet, or the probe could not tell): a target is reported only while
 * qits-system positively says the sign-in is gone. An absence keeps the instant it began across
 * the probes that confirm it, so its {@code since} does not move with each one.
 */
@ApplicationScoped
public class TargetLogins {

  /** The value qits-system reports for a harness signed out on a target. */
  static final String ABSENT = "ABSENT";

  /**
   * One target's sign-in as qits-system reported it.
   *
   * @param claude {@code PRESENT}, {@code ABSENT}, {@code UNKNOWN} or null
   * @param kimi the same, for the kimi harness
   * @param checkedAt the probe's instant, or null when it was not given
   */
  public record Login(UUID targetId, String targetName, String claude, String kimi, Instant checkedAt) {}

  /**
   * A harness signed out on a target.
   *
   * @param harness the harness's word as a reason says it: {@code claude} or {@code kimi}
   * @param since when it was first seen ABSENT
   */
  public record Absence(UUID targetId, String targetName, String harness, Instant since) {}

  /** What is held per target: its name, its last probe, and since when each harness is ABSENT. */
  private record Held(String name, Instant checkedAt, Instant claudeAbsentSince, Instant kimiAbsentSince) {}

  private final Map<UUID, Held> byTarget = new HashMap<>();

  /**
   * One target's report ({@code TargetLoginChanged}). A report older than the probe already held
   * moves nothing.
   *
   * @return whether an absence began, ended, or the target's name changed while one stands
   */
  public synchronized boolean observe(Login login) {
    if (login == null || login.targetId() == null) {
      return false;
    }
    Held before = byTarget.get(login.targetId());
    if (before != null
        && before.checkedAt() != null
        && login.checkedAt() != null
        && login.checkedAt().isBefore(before.checkedAt())) {
      return false;
    }
    Held after = next(before, login, Instant.now());
    byTarget.put(login.targetId(), after);
    return differs(before, after);
  }

  /**
   * Every target qits-system lists ({@code GET /system/api/targets}): each listed one is observed,
   * and a target held but no longer listed is forgotten.
   *
   * @return whether any absence began or ended
   */
  public synchronized boolean reconcile(Collection<Login> listed) {
    List<Login> logins = listed == null ? List.of() : List.copyOf(listed);
    boolean changed = false;
    Map<UUID, Held> kept = new HashMap<>();
    for (Login login : logins) {
      if (login == null || login.targetId() == null) {
        continue;
      }
      Held before = byTarget.get(login.targetId());
      Held after = next(before, login, Instant.now());
      kept.put(login.targetId(), after);
      changed |= differs(before, after);
    }
    for (Map.Entry<UUID, Held> gone : byTarget.entrySet()) {
      if (!kept.containsKey(gone.getKey())) {
        changed |= differs(gone.getValue(), null);
      }
    }
    byTarget.clear();
    byTarget.putAll(kept);
    return changed;
  }

  /** Whether any target has a harness signed out: when none has, nobody needs looking up. */
  public synchronized boolean anyAbsent() {
    return byTarget.values().stream()
        .anyMatch(h -> h.claudeAbsentSince() != null || h.kimiAbsentSince() != null);
  }

  /** {@code harness}'s absence on {@code targetId}; empty while it is signed in, or not known. */
  public synchronized Optional<Absence> absence(UUID targetId, AgentHarness harness) {
    if (targetId == null) {
      return Optional.empty();
    }
    Held held = byTarget.get(targetId);
    if (held == null) {
      return Optional.empty();
    }
    AgentHarness which = harness == null ? AgentHarness.CLAUDE : harness;
    Instant since = which == AgentHarness.KIMI ? held.kimiAbsentSince() : held.claudeAbsentSince();
    if (since == null) {
      return Optional.empty();
    }
    String name =
        held.name() == null || held.name().isBlank()
            ? targetId.toString().substring(0, 8)
            : held.name();
    return Optional.of(new Absence(targetId, name, which.name().toLowerCase(Locale.ROOT), since));
  }

  /** Everything held is forgotten (tests). */
  public synchronized void clear() {
    byTarget.clear();
  }

  private static Held next(Held before, Login login, Instant now) {
    Instant at = login.checkedAt() != null ? login.checkedAt() : now;
    return new Held(
        login.targetName() != null ? login.targetName() : before == null ? null : before.name(),
        login.checkedAt() != null ? login.checkedAt() : before == null ? null : before.checkedAt(),
        absentSince(login.claude(), before == null ? null : before.claudeAbsentSince(), at),
        absentSince(login.kimi(), before == null ? null : before.kimiAbsentSince(), at));
  }

  /** ABSENT keeps the instant it began, or begins now; anything else is no absence. */
  private static Instant absentSince(String value, Instant held, Instant at) {
    if (value == null || !ABSENT.equals(value.trim().toUpperCase(Locale.ROOT))) {
      return null;
    }
    return held != null ? held : at;
  }

  private static boolean differs(Held before, Held after) {
    boolean claudeBefore = before != null && before.claudeAbsentSince() != null;
    boolean kimiBefore = before != null && before.kimiAbsentSince() != null;
    boolean claudeAfter = after != null && after.claudeAbsentSince() != null;
    boolean kimiAfter = after != null && after.kimiAbsentSince() != null;
    if (claudeBefore != claudeAfter || kimiBefore != kimiAfter) {
      return true;
    }
    return (claudeAfter || kimiAfter) && !Objects.equals(before.name(), after.name());
  }
}
