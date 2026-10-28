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
 * qits-projects' work item read ({@code getWork}), the call a declared wait on a work item or its
 * comments makes (epic qits-1681, {@link HttpWaitSubjectResolver}). {@link ProjectsWork} files a
 * ticket and reads nothing; this is the read beside it. The item is named by its uuid or its
 * qualified id.
 */
@Path("/projects/api/work")
@RegisterRestClient(configKey = "qits-projects")
@RegisterForReflection(targets = {ProjectsWorkItems.WorkItem.class})
public interface ProjectsWorkItems {

  @GET
  @Path("/{ref}")
  @Produces(MediaType.APPLICATION_JSON)
  WorkItem get(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization, @PathParam("ref") String ref);

  /** The four fields a wait reads. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record WorkItem(String id, String qualifiedId, String status, Instant updatedAt) {}
}
