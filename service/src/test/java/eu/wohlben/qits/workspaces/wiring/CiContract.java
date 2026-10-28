package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.workspaces.control.WaitSubjectResolver;
import eu.wohlben.qits.workspaces.control.WaitSubjects;
import eu.wohlben.qits.workspaces.testing.contracts.ContractRow;
import eu.wohlben.qits.workspaces.testing.contracts.ContractTable;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Request;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>What qits-workspaces asks qits-ci, and why</b> (epic qits-1681) — the table {@code
 * CiConsumerPactTest} runs against pact mock servers and {@code ConsumerPactFilesTest} writes to
 * {@code pacts/qits-workspaces-service_qits-ci-service.json}. One call, {@link
 * HttpWaitSubjectResolver}'s {@code getRun} over the real generated {@link CiRuns} client:
 *
 * <ul>
 *   <li>{@code createAgentWait} — an agent declares a wait on a run ({@code BuildStatusChanged},
 *       {@code BuildSuccessful} or {@code BuildFailed} naming its {@code runId}); once the wait is
 *       committed, its subject is looked up: the run's id, its status and when it should finish. The
 *       scheduler pass makes the same call again for a wait whose lookup failed.
 * </ul>
 *
 * <p>Two states: {@code a running run}, and {@code no run with the given id}, whose 404 is read by
 * its status alone and becomes the wait's {@code subject_error}. The answer's paths bound are the
 * ones the running recording holds a value for; {@code finishedAt} and {@code
 * expectedStepDurationsMillis} are null there, and the code reads them as absent.
 */
public final class CiContract {

  static final String A_RUNNING_RUN = "a running run";
  static final String NO_RUN = "no run with the given id";

  static final String GET_RUN = "getRun";

  static final Trigger CREATE_WAIT = Trigger.operation("createAgentWait");

  private static final String BEARER = "{authorization}";

  public static final ContractTable TABLE =
      new ContractTable(
          GoldenMasters.CI,
          List.of(
              ContractRow.of(
                  CREATE_WAIT,
                  A_RUNNING_RUN,
                  GET_RUN,
                  Request.NONE.header("Authorization", BEARER),
                  List.of("$.id", "$.status", "$.createdAt", "$.startedAt"),
                  (url, params) -> {
                    WaitSubjectResolver.Resolved run =
                        resolverAgainst(url, params)
                            .resolve(WaitSubjects.Kind.CI_RUN, params.get("runId"), null)
                            .orElseThrow();
                    JsonNode recorded = GoldenMasters.json(GoldenMasters.CI, A_RUNNING_RUN, GET_RUN);
                    assertEquals(params.get("runId"), run.subjectId());
                    assertNull(run.subjectQualifiedId());
                    assertEquals(recorded.path("status").asText(), run.state());
                    assertEquals(Instant.parse(recorded.path("startedAt").asText()), run.stateAt());
                    assertNotNull(run.expectedBy(), "a run always has an expected finish");
                  }),
              ContractRow.of(
                  CREATE_WAIT,
                  NO_RUN,
                  GET_RUN,
                  Request.NONE,
                  List.of(),
                  (url, params) -> {
                    WaitSubjectResolver.Unresolvable missing =
                        assertThrows(
                            WaitSubjectResolver.Unresolvable.class,
                            () ->
                                resolverAgainst(url, params)
                                    .resolve(WaitSubjects.Kind.CI_RUN, params.get("runId"), null));
                    assertTrue(missing.getMessage().startsWith("qits-ci knows no run"), missing.getMessage());
                  })));

  private CiContract() {}

  /**
   * {@link HttpWaitSubjectResolver} with the real generated {@link CiRuns} client against the mock
   * server, its bearer the state's {@code authorization} param (none where the state records none).
   */
  static HttpWaitSubjectResolver resolverAgainst(String mockUrl, Map<String, String> params) {
    HttpWaitSubjectResolver resolver = new HttpWaitSubjectResolver();
    resolver.runs =
        QuarkusRestClientBuilder.newBuilder().baseUri(URI.create(mockUrl)).build(CiRuns.class);
    String authorization = params.get("authorization");
    resolver.bearer =
        new IdpProjectsBearer() {
          @Override
          public Optional<String> authorization() {
            return Optional.ofNullable(authorization);
          }
        };
    resolver.overdueFactor = 2;
    resolver.overdueGrace = Duration.ofMinutes(15);
    resolver.unpredictedAfter = Duration.ofHours(2);
    return resolver;
  }
}
