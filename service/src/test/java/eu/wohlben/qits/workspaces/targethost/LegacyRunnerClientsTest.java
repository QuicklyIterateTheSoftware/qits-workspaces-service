package eu.wohlben.qits.workspaces.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeCredentialCommissioner;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The retirement's boot one-shot (epic qits-1481): every client and token of the two retired
 * runner kinds is given back, nothing of any other kind is touched, and a second run finds nothing.
 */
@QuarkusTest
public class LegacyRunnerClientsTest {

  @Inject LegacyRunnerClients legacy;
  @Inject FakeCredentialCommissioner commissioner;

  @AfterEach
  void reset() {
    commissioner.reset();
  }

  @Test
  public void givesBackEveryRetiredRunnerCredentialAndNothingElseOnce() {
    commissioner.wire();
    commissioner.plant("runner-client", "workspaces-runner", "8d45fd1d-0000-4000-8000-000000000001");
    commissioner.plant("ws-client", "workspace", "42");
    commissioner.plantToken("reg-token", "workspaces-runner-registration", "r-1", Instant.now());
    commissioner.plantToken("ws-token", "workspace", "42", Instant.now());

    assertEquals(2, legacy.giveBack());

    assertEquals(List.of("runner-client"), commissioner.decommissioned());
    assertTrue(commissioner.tokensDeleted().contains("reg-token"));
    assertFalse(commissioner.tokensDeleted().contains("ws-token"), "a workspace token stays");
    assertTrue(commissioner.liveClientIds().contains("ws-client"), "a workspace client stays");

    assertEquals(0, legacy.giveBack(), "idempotent: nothing of the retired kinds is left");
  }

  @Test
  public void anIssuerThatListsNothingGivesBackNothing() {
    // Not wired: the listing answers empty, as an unreadable one does.
    commissioner.plant("runner-client", "workspaces-runner", "r-2");

    assertEquals(0, legacy.giveBack());
    assertEquals(List.of(), commissioner.decommissioned());
  }
}
