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
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * qits-projects' release-request read ({@code getProjectReleaseRequest}), the one call a declared
 * wait on a release request makes (epic qits-1681, {@link HttpWaitSubjectResolver}). The request is
 * named by its uuid or its qualified id, under its project.
 */
@Path("/projects/api/projects")
@RegisterRestClient(configKey = "qits-projects")
@RegisterForReflection(
    targets = {ProjectsReleaseRequests.RequestAnswer.class, ProjectsReleaseRequests.Request.class})
public interface ProjectsReleaseRequests {

  @GET
  @Path("/{projectId}/release-requests/{ref}")
  @Produces(MediaType.APPLICATION_JSON)
  RequestAnswer get(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @PathParam("projectId") String projectId,
      @PathParam("ref") String ref);

  /** {@code {request: …}}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record RequestAnswer(Request request) {}

  /** The four fields a wait reads; the rest of the request stays qits-projects' to change. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Request(String id, String qualifiedId, String state, Instant updatedAt) {}
}
