package eu.wohlben.qits.workspaces.wiring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * qits-ci's run read ({@code getRun}), the call a declared wait on a CI run makes (epic qits-1681,
 * {@link HttpWaitSubjectResolver}). The address is {@code qits.ci.url} (scheme, host and port, no
 * path; qits-ci serves its own {@code /ci/api} segment), the bearer this service's own {@code qits}
 * oidc client's, as for qits-system.
 */
@Path("/ci/api/runs")
@RegisterRestClient(configKey = "qits-ci")
@RegisterForReflection(targets = {CiRuns.Run.class})
public interface CiRuns {

  @GET
  @Path("/{runId}")
  @Produces(MediaType.APPLICATION_JSON)
  Run get(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @PathParam("runId") String runId);

  /**
   * The fields a wait reads of qits-ci's {@code CiRunDto}: the status, its three instants, and the
   * predicted duration of each step ({@code null} when qits-ci has no prediction).
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Run(
      String id,
      String status,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt,
      List<Long> expectedStepDurationsMillis) {}
}
