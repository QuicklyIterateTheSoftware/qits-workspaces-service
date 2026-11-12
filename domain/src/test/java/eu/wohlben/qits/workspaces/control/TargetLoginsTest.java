package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.TargetLogins.Absence;
import eu.wohlben.qits.workspaces.control.TargetLogins.Login;
import eu.wohlben.qits.workspaces.entity.AgentHarness;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link TargetLogins} (qits-2088): an absence per harness, kept from its first sighting across the
 * probes that confirm it, ended by PRESENT or UNKNOWN; an older report moves nothing; and the
 * reconcile forgets a target no longer listed. Pure: no container.
 */
class TargetLoginsTest {

  private static final UUID LAB = UUID.randomUUID();
  private static final Instant T0 = Instant.parse("2026-11-11T10:42:00Z");

  private static Login lab(String claude, String kimi, Instant at) {
    return new Login(LAB, "lab", claude, kimi, at);
  }

  @Test
  void anAbsenceBeginsAtItsFirstProbeAndEndsWhenSignedInAgain() {
    TargetLogins logins = new TargetLogins();

    assertTrue(logins.observe(lab("ABSENT", "PRESENT", T0)));
    assertEquals(
        new Absence(LAB, "lab", "claude", T0), logins.absence(LAB, AgentHarness.CLAUDE).orElseThrow());
    assertTrue(logins.absence(LAB, AgentHarness.KIMI).isEmpty());
    assertTrue(logins.anyAbsent());

    // A later probe that confirms it is no change, and keeps when it began.
    assertFalse(logins.observe(lab("ABSENT", "PRESENT", T0.plus(Duration.ofMinutes(5)))));
    assertEquals(T0, logins.absence(LAB, AgentHarness.CLAUDE).orElseThrow().since());

    assertTrue(logins.observe(lab("PRESENT", "PRESENT", T0.plus(Duration.ofMinutes(9)))));
    assertTrue(logins.absence(LAB, AgentHarness.CLAUDE).isEmpty());
    assertFalse(logins.anyAbsent());
  }

  @Test
  void unknownIsNoAbsenceAndAnOlderReportMovesNothing() {
    TargetLogins logins = new TargetLogins();
    logins.observe(lab("ABSENT", null, T0));

    assertFalse(logins.observe(lab("PRESENT", null, T0.minusSeconds(60))), "older than held");
    assertTrue(logins.absence(LAB, AgentHarness.CLAUDE).isPresent());

    assertTrue(logins.observe(lab("UNKNOWN", null, T0.plusSeconds(60))));
    assertTrue(logins.absence(LAB, AgentHarness.CLAUDE).isEmpty());
  }

  @Test
  void eachHarnessIsItsOwn() {
    TargetLogins logins = new TargetLogins();
    logins.observe(lab("PRESENT", "ABSENT", T0));

    assertTrue(logins.absence(LAB, AgentHarness.CLAUDE).isEmpty());
    assertEquals("kimi", logins.absence(LAB, AgentHarness.KIMI).orElseThrow().harness());
    assertTrue(logins.absence(UUID.randomUUID(), AgentHarness.KIMI).isEmpty(), "another target");
    assertTrue(logins.absence(null, AgentHarness.CLAUDE).isEmpty());
  }

  @Test
  void theReconcileSeedsAndForgetsATargetNoLongerListed() {
    TargetLogins logins = new TargetLogins();
    UUID other = UUID.randomUUID();

    assertTrue(logins.reconcile(List.of(lab("ABSENT", "ABSENT", T0), new Login(other, null, "PRESENT", null, T0))));
    assertTrue(logins.absence(LAB, AgentHarness.CLAUDE).isPresent());
    assertFalse(logins.reconcile(List.of(lab("ABSENT", "ABSENT", T0.plusSeconds(300)))), "only a signed-in target went");
    assertEquals(T0, logins.absence(LAB, AgentHarness.KIMI).orElseThrow().since());

    assertTrue(logins.reconcile(List.of()));
    assertFalse(logins.anyAbsent());
  }

  @Test
  void aTargetWithNoNameIsCalledByItsIdsFirstEight() {
    TargetLogins logins = new TargetLogins();
    logins.observe(new Login(LAB, null, "ABSENT", null, T0));

    assertEquals(
        LAB.toString().substring(0, 8),
        logins.absence(LAB, AgentHarness.CLAUDE).orElseThrow().targetName());
  }
}
