package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.GitRefScopes;
import eu.wohlben.qits.workspaces.control.StubInstance;
import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import eu.wohlben.qits.workspaces.wiring.IdpStub.Answer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reconcile's two runner arms, against a stub qits-idp over real HTTP and the real runner table:
 * a {@code workspaces-runner} client no runner row holds is decommissioned, a {@code
 * workspaces-runner-registration} token no row names is deleted, and each keeps what a row does
 * hold. The {@code workspace} arm is {@link CommissionReconcilerTest}'s, untouched.
 *
 * <p>The reconciler is built by hand around the injected repositories, so both listings are this
 * test's stub rather than the suite's unwired fake.
 */
@QuarkusTest
public class CommissionReconcilerRunnerArmsTest {

  /** Older than the grace, so only the runner table decides. */
  private static final String LONG_AGO = "2026-01-01T00:00:00Z";

  @Inject WorkspaceRunners workspaceRunners;
  @Inject WorkspaceRunnerRepository runnerRepository;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject GitRefScopes gitRefScopes;

  private IdpStub idp;
  private final List<UUID> created = new ArrayList<>();

  @BeforeEach
  void startIdp() throws Exception {
    idp = new IdpStub();
  }

  @AfterEach
  void cleanUp() {
    idp.close();
    QuarkusTransaction.requiringNew().run(() -> created.forEach(runnerRepository::deleteById));
    created.clear();
  }

  private CommissionReconciler reconciler() {
    CommissionReconciler reconciler = new CommissionReconciler();
    reconciler.commissioner = StubInstance.of(idp.credentialCommissioner());
    reconciler.workspaces = workspaceRepository;
    reconciler.gitRefScopes = gitRefScopes;
    reconciler.runners = runnerRepository;
    reconciler.runnerCommissioner = idp.runnerCommissioner();
    return reconciler;
  }

  /** A runner whose register door has answered with {@code clientId}. */
  private WorkspaceRunner registered(String clientId) {
    WorkspaceRunner runner = unregistered("t-spent-" + clientId);
    return workspaceRunners.markRegistered(runner.id, clientId, null);
  }

  /** A runner holding the registration token {@code tokenId}. */
  private WorkspaceRunner unregistered(String tokenId) {
    UUID id = UUID.randomUUID();
    created.add(id);
    return workspaceRunners.create(
        id, "r-" + id.toString().substring(0, 8), null, 1, tokenId, "sub-" + tokenId);
  }

  private static String client(String clientId, String kind, Object contextId) {
    return "{\"clientId\":\"" + clientId + "\",\"contextKind\":\"" + kind + "\",\"contextId\":\""
        + contextId + "\"}";
  }

  private static String token(String tokenId, String kind, Object contextId, String createdAt) {
    return "{\"tokenId\":\"" + tokenId + "\",\"subject\":\"sub-" + tokenId + "\",\"contextKind\":\""
        + kind + "\",\"contextId\":\"" + contextId + "\",\"createdAt\":\"" + createdAt + "\"}";
  }

  @Test
  public void aRunnerClientNoRowHoldsIsDecommissionedAndTheHeldOneKept() {
    WorkspaceRunner holding = registered("wr-held");
    WorkspaceRunner registering = unregistered("t-reg");
    idp.on(
        "GET /api/clients",
        new Answer(
            200,
            "["
                + String.join(
                    ",",
                    client("wr-held", "workspaces-runner", holding.id),
                    // A replaced client of the same runner: the row names another.
                    client("wr-replaced", "workspaces-runner", holding.id),
                    // A deleted runner's client whose give-back never reached qits-idp.
                    client("wr-orphan", "workspaces-runner", UUID.randomUUID()),
                    // A registration between the commission and the row write.
                    client("wr-in-flight", "workspaces-runner", registering.id),
                    // Nothing the runner arm is about.
                    client("run-3", "run", "3"))
                + "]"));
    idp.on("GET /api/tokens", new Answer(200, "[]"));
    idp.on("DELETE /api/clients/wr-replaced", new Answer(204, null));
    idp.on("DELETE /api/clients/wr-orphan", new Answer(204, null));

    assertEquals(2, reconciler().reconcile());

    assertEquals(
        List.of("DELETE /api/clients/wr-replaced", "DELETE /api/clients/wr-orphan"),
        idp.lines("DELETE"));
  }

  @Test
  public void aRegistrationTokenNoRowNamesIsDeletedAndTheNamedOneKept() {
    WorkspaceRunner waiting = unregistered("t-live");
    WorkspaceRunner registeredRunner = registered("wr-1");
    idp.on("GET /api/clients", new Answer(200, "[]"));
    idp.on(
        "GET /api/tokens",
        new Answer(
            200,
            "["
                + String.join(
                    ",",
                    token("t-live", "workspaces-runner-registration", waiting.id, LONG_AGO),
                    // Rotated away: the row names t-live now.
                    token("t-rotated", "workspaces-runner-registration", waiting.id, LONG_AGO),
                    // Spent: the runner registered and its row names no token.
                    token(
                        "t-spent",
                        "workspaces-runner-registration",
                        registeredRunner.id,
                        LONG_AGO),
                    // A create between the commission and the row write.
                    token(
                        "t-young",
                        "workspaces-runner-registration",
                        UUID.randomUUID(),
                        Instant.now().toString()),
                    // Another kind is not this arm's (the per-workspace token, qits-625).
                    token("t-workspace", "workspace", "7", LONG_AGO))
                + "]"));
    idp.on("DELETE /api/tokens/t-rotated", new Answer(204, null));
    idp.on("DELETE /api/tokens/t-spent", new Answer(204, null));

    assertEquals(2, reconciler().reconcile());

    assertEquals(
        List.of("DELETE /api/tokens/t-rotated", "DELETE /api/tokens/t-spent"),
        idp.lines("DELETE"));
  }

  @Test
  public void aFailedListingReapsNothing() {
    unregistered("t-live");
    idp.on("GET /api/clients", new Answer(500, null));
    idp.on("GET /api/tokens", new Answer(503, null));

    assertEquals(0, reconciler().reconcile());

    assertEquals(List.of("GET /api/clients", "GET /api/tokens"), idp.lines());
  }

  @Test
  public void anEmptyListingReapsNothing() {
    registered("wr-1");
    idp.on("GET /api/clients", new Answer(200, "[]"));
    idp.on("GET /api/tokens", new Answer(200, "[]"));

    assertEquals(0, reconciler().reconcile());
    assertEquals(List.of(), idp.lines("DELETE"));
  }
}
