package eu.wohlben.qits.workspaces.wiring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** qits-projects' work door, for the one call a capture makes: file a ticket (qits-1152, D25). */
@Path("/projects/api/work")
@RegisterRestClient(configKey = "qits-projects")
public interface ProjectsWork {

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  CreatedWork create(@HeaderParam("Authorization") String authorization, CreateWork body);

  /** A TICKET, as qits-projects' {@code createWork} takes it. */
  record CreateWork(
      String archetype, String project, String title, String impetus, String ticketType) {}

  /** The two fields this service reads off the answer. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CreatedWork(String id, String qualifiedId) {}
}
