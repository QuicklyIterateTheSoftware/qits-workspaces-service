package eu.wohlben.qits.workspaces.wiring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * qits-projects' branch delivery ledger read ({@code getBranchDeliveries}), the call a declared wait
 * on a branch makes (qits-1961, {@link HttpWaitSubjectResolver}): where the branch stands in each
 * repository of its project. An unknown branch answers 200 with no deliveries and {@code
 * everywhere} null — a branch the ledger does not follow looks exactly like that. The {@code
 * project} narrows it to the wait's project (a branch with rows in several projects is a 400
 * without it); a null one is left off the query. {@code repository} is never sent: the wait reads
 * its repository's row out of the whole answer, and {@code everywhere} is not narrowed by it anyway.
 */
@Path("/projects/api/branch-deliveries")
@RegisterRestClient(configKey = "qits-projects")
@RegisterForReflection(
    targets = {ProjectsBranchDeliveries.Deliveries.class, ProjectsBranchDeliveries.Delivery.class})
public interface ProjectsBranchDeliveries {

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  Deliveries get(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @QueryParam("branch") String branch,
      @QueryParam("project") String project);

  /** {@code {branch, everywhere, deliveries: […]}}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Deliveries(String branch, String everywhere, List<Delivery> deliveries) {}

  /** The fields of one repository's row a wait reads; the rest stays qits-projects' to change. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Delivery(
      String repository, String step, String qualifiedId, String runId, Instant changedAt) {}
}
