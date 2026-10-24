package eu.wohlben.qits.workspaces.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import eu.wohlben.qits.workspaces.control.RunnerLaunchSpec;
import eu.wohlben.qits.workspaces.control.TargetPlacement;
import eu.wohlben.qits.workspaces.testing.contracts.ContractRow;
import eu.wohlben.qits.workspaces.testing.contracts.ContractTable;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Request;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import eu.wohlben.qits.workspaces.wiring.IdpProjectsBearer;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>What qits-workspaces asks qits-system, and why</b> (epic qits-1481, qits-1532) — the table
 * {@code SystemConsumerPactTest} runs against pact mock servers and {@code ConsumerPactFilesTest}
 * writes to {@code pacts/qits-workspaces-service_qits-system-service.json}. Every call is {@link
 * TargetLeasePlacement}'s, over the real generated client:
 *
 * <ul>
 *   <li>{@code createLease} — a TARGET row's start ({@code ensureWorkspaceContainer}, and the
 *       scheduler's {@code createSlot} through it), with {@code getLease} right after to apply a
 *       grant that raced the row's write;
 *   <li>{@code listTargets} — the same start, for a row a retired runner held: its adopting
 *       lease's {@code pinTargetId};
 *   <li>{@code endLease} — {@code stopWorkspaceContainer} (and delete-container, recreate, a
 *       resolution, an EXITED or FAILED lease);
 *   <li>{@code purgeWorkVolume} — {@code deleteWorkspaceContainer} (and recreate, and a resolved
 *       row's ENDED lease);
 *   <li>{@code listLeases} — {@code TargetLeaseEvents}' reconcile.
 * </ul>
 *
 * <p>Every row is live since {@code qits-system-golden-masters} 2026.1023.213531, the first to
 * record the states qits-system keeps for this tenant: {@code a queued workspace lease}, {@code a
 * running workspace lease}, {@code an unused workspace work volume} and {@code a declared target}.
 */
public final class SystemContract {

  // qits-system's states recorded for the WORKSPACE tenant, under this service's own bearer.
  static final String A_QUEUED_LEASE = "a queued workspace lease";
  static final String A_RUNNING_LEASE = "a running workspace lease";
  static final String AN_UNUSED_WORK_VOLUME = "an unused workspace work volume";
  static final String A_DECLARED_TARGET = "a declared target";

  static final Trigger START = Trigger.operation("ensureWorkspaceContainer");
  static final Trigger STOP = Trigger.operation("stopWorkspaceContainer");
  static final Trigger DELETE = Trigger.operation("deleteWorkspaceContainer");
  static final Trigger RECONCILE = Trigger.schedule("TargetLeaseEvents.reconcile");

  /** The lease answer's fields this service reads ({@code SystemLeases.LeaseView}). */
  static final List<String> LEASE =
      List.of("$.id", "$.ownerRef", "$.state", "$.targetId", "$.exitCode", "$.detail", "$.requestedAt");

  /** The row id the create below asks for. */
  static final long ROW_ID = 17L;

  private static final String BEARER = "{authorization}";

  public static final ContractTable TABLE =
      new ContractTable(
          GoldenMasters.SYSTEM,
          List.of(
              ContractRow.of(
                      START,
                      A_QUEUED_LEASE,
                      "createLease",
                      Request.json(createBody()).header("Authorization", BEARER),
                      LEASE,
                      (url, params) -> {
                        TargetPlacement.Lease lease = placementAgainst(url, params).create(request());
                        assertNotNull(lease.id());
                        assertNotNull(lease.state());
                      }),
              ContractRow.of(
                      START,
                      A_QUEUED_LEASE,
                      "getLease",
                      Request.NONE.header("Authorization", BEARER),
                      LEASE,
                      (url, params) -> {
                        Optional<TargetPlacement.Lease> lease =
                            placementAgainst(url, params).get(leaseId(params));
                        assertTrue(lease.isPresent());
                        assertEquals(TargetPlacement.LeaseState.QUEUED, lease.get().state());
                      }),
              ContractRow.of(
                      RECONCILE,
                      A_QUEUED_LEASE,
                      "listLeases",
                      Request.NONE.header("Authorization", BEARER),
                      List.of(
                          "$[*].id",
                          "$[*].ownerRef",
                          "$[*].state",
                          "$[*].targetId",
                          "$[*].exitCode",
                          "$[*].detail",
                          "$[*].requestedAt"),
                      (url, params) ->
                          assertFalse(placementAgainst(url, params).list().isEmpty())),
              ContractRow.of(
                      STOP,
                      A_QUEUED_LEASE,
                      "endLease",
                      Request.NONE.header("Authorization", BEARER),
                      List.of("$.id", "$.state"),
                      (url, params) ->
                          assertEquals(
                              TargetPlacement.LeaseState.ENDED,
                              placementAgainst(url, params)
                                  .end(leaseId(params))
                                  .orElseThrow()
                                  .state())),
              ContractRow.of(
                      RECONCILE,
                      A_RUNNING_LEASE,
                      "getLease",
                      Request.NONE.header("Authorization", BEARER),
                      LEASE,
                      (url, params) -> {
                        TargetPlacement.Lease lease =
                            placementAgainst(url, params).get(leaseId(params)).orElseThrow();
                        assertEquals(TargetPlacement.LeaseState.RUNNING, lease.state());
                        assertNotNull(lease.targetId());
                      }),
              ContractRow.of(
                      RECONCILE,
                      A_RUNNING_LEASE,
                      "listLeases",
                      Request.NONE.header("Authorization", BEARER),
                      List.of(
                          "$[*].id",
                          "$[*].ownerRef",
                          "$[*].state",
                          "$[*].targetId",
                          "$[*].exitCode",
                          "$[*].detail",
                          "$[*].requestedAt"),
                      (url, params) ->
                          assertFalse(placementAgainst(url, params).list().isEmpty())),
              ContractRow.of(
                      STOP,
                      A_RUNNING_LEASE,
                      "endLease",
                      Request.NONE.header("Authorization", BEARER),
                      List.of("$.id", "$.state"),
                      (url, params) ->
                          assertEquals(
                              TargetPlacement.LeaseState.ENDING,
                              placementAgainst(url, params)
                                  .end(leaseId(params))
                                  .orElseThrow()
                                  .state())),
              ContractRow.of(
                      DELETE,
                      AN_UNUSED_WORK_VOLUME,
                      "purgeWorkVolume",
                      Request.NONE.header("Authorization", BEARER),
                      List.of(),
                      (url, params) ->
                          assertTrue(
                              placementAgainst(url, params)
                                  .purgeWorkVolume(params.get("workKey")))),
              ContractRow.of(
                      START,
                      A_DECLARED_TARGET,
                      "listTargets",
                      Request.NONE,
                      List.of("$[*].id", "$[*].name", "$[*].hostname", "$[*].engineId"),
                      (url, params) -> {
                        List<TargetPlacement.Target> targets =
                            placementAgainst(url, params).targets();
                        assertFalse(targets.isEmpty());
                        assertNotNull(targets.get(0).name());
                      })));

  private SystemContract() {}

  /** What a start sends for row {@link #ROW_ID}: a WORKSPACE lease on its work volume. */
  static DslPart createBody() {
    PactDslJsonBody body =
        new PactDslJsonBody()
            .stringValue("slotType", "WORKSPACE")
            .stringMatcher("ownerRef", "^[0-9]+$", Long.toString(ROW_ID))
            .stringMatcher("workKey", "^ws-[0-9]+$", "ws-" + ROW_ID)
            .integerType("priority", 0);
    PactDslJsonBody spec = body.object("spec");
    spec.stringMatcher(
        "image", "^.+/qits/workspace[:@].+$", "registry.qits.wohlben.eu/qits/workspace:2026.1");
    spec.object("env").stringType("QITS_TOKEN", "qits_tok_lease").closeObject();
    spec.stringValue("user", "1000");
    spec.object("limits")
        .stringType("memory", "4g")
        .stringType("memorySwap", "8g")
        .integerType("pidsLimit", 4096L)
        .stringType("cpus", "2")
        .integerType("oomScoreAdj", 600)
        .closeObject();
    return spec.closeObject();
  }

  /** The domain's request the body above is the wire form of. */
  static TargetPlacement.LeaseRequest request() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_TOKEN", "qits_tok_lease");
    return new TargetPlacement.LeaseRequest(
        ROW_ID,
        null,
        new RunnerLaunchSpec(
            "registry.qits.wohlben.eu/qits/workspace:2026.1",
            env,
            "1000",
            new RunnerLaunchSpec.Limits("4g", "8g", "4096", "2", 600)),
        null,
        null);
  }

  private static UUID leaseId(Map<String, String> params) {
    return UUID.fromString(params.get("leaseId"));
  }

  /**
   * {@link TargetLeasePlacement} over the real generated client against the mock server, its bearer
   * the state's {@code authorization} param (none where the state records none).
   */
  static TargetLeasePlacement placementAgainst(String mockUrl, Map<String, String> params) {
    TargetLeasePlacement placement = new TargetLeasePlacement();
    placement.system =
        QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(mockUrl))
            .build(SystemLeases.class);
    String authorization = params.get("authorization");
    placement.bearer =
        new IdpProjectsBearer() {
          @Override
          public Optional<String> authorization() {
            return Optional.ofNullable(authorization);
          }
        };
    return placement;
  }
}
