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
 * qualified id. Since qits-2596 a project agent's wait reads it too ({@link HttpWorkItemLookup}):
 * its archetype and project, to hold the wait only for an EPIC or a TICKET of the agent's project.
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

  /**
   * The fields a wait reads: a subject's id, qualified id, status and since when; and, for a project
   * agent's wait (qits-2596), the item's archetype and project.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record WorkItem(
      String id,
      String qualifiedId,
      String status,
      Instant updatedAt,
      String archetype,
      String projectId) {}
}
