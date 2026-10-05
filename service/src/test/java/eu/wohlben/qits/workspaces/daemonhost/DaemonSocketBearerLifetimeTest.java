package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.security.Principal;
import org.junit.jupiter.api.Test;

/**
 * {@link DaemonSocketBearerLifetime} (qits-812): the bearer's expiry is dropped for a token subject
 * on the daemon control socket and nowhere else — not for a commissioned client on the same path
 * (the DIRECT socket keeps today's lifetime), not for the tunnel's dial-back, not for the API.
 */
class DaemonSocketBearerLifetimeTest {

  private static final Long EXP = 1_900_000_000L;

  private static SecurityIdentity identity(String subject) {
    Principal principal = () -> subject;
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(principal)
        .addRole("qits:agent")
        .addAttribute(DaemonSocketBearerLifetime.EXPIRE_TIME, EXP)
        .addAttribute("kept", "yes")
        .build();
  }

  @Test
  void aTokenSubjectOnTheControlSocketLosesItsExpiryAndKeepsTheRest() {
    SecurityIdentity held =
        DaemonSocketBearerLifetime.forPath(identity("tok-workspace-7"), "/workspaces/daemon/7");

    assertNull(held.getAttribute(DaemonSocketBearerLifetime.EXPIRE_TIME));
    assertEquals("yes", held.getAttribute("kept"));
    assertEquals("tok-workspace-7", held.getPrincipal().getName());
    assertTrue(held.hasRole("qits:agent"));
  }

  @Test
  void aClientSubjectOnTheSamePathKeepsItsExpiry() {
    SecurityIdentity direct = identity("dyn-workspace-7-abc");

    assertSame(direct, DaemonSocketBearerLifetime.forPath(direct, "/workspaces/daemon/7"));
    assertEquals(EXP, direct.getAttribute(DaemonSocketBearerLifetime.EXPIRE_TIME));
  }

  @Test
  void theDialBackAndTheApiKeepTheirExpiry() {
    SecurityIdentity runner = identity("tok-workspace-7");

    assertSame(runner, DaemonSocketBearerLifetime.forPath(runner, "/workspaces/daemon/stream/x"));
    assertSame(runner, DaemonSocketBearerLifetime.forPath(runner, "/workspaces/daemon/stream"));
    assertSame(runner, DaemonSocketBearerLifetime.forPath(runner, "/workspaces/api/workspaces"));
    assertSame(runner, DaemonSocketBearerLifetime.forPath(runner, "/workspaces/daemon/7/more"));
    assertSame(runner, DaemonSocketBearerLifetime.forPath(runner, null));
  }
}
