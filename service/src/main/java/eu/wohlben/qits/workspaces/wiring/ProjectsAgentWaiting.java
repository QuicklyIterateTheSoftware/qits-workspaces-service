package eu.wohlben.qits.workspaces.wiring;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * qits-projects' agent-waiting door (qits-895): the dispatched agent on a work item is, or is no
 * longer, waiting for its user. {@link HttpAgentWaitingReporter} is the one caller.
 *
 * <p>Same service, same {@code configKey} and same {@code /projects/api} segment as {@link
 * ProjectsRepositories} — see that interface for why the base url carries no path. The door is
 * {@code qits:system}, which is the role the {@code qits} named client's bearer holds.
 */
@Path("/projects/api/work")
@RegisterRestClient(configKey = "qits-projects")
public interface ProjectsAgentWaiting {

  /**
   * Answers 204. A 404 is a work item qits-projects does not know — or, from a qits-projects older
   * than the door, the route itself; a 405 is that older one too.
   *
   * @param workId the work item's entity id, {@code Workspace.workId}
   */
  @POST
  @Path("/{id}/agent-waiting")
  @Consumes(MediaType.APPLICATION_JSON)
  void report(
      @PathParam("id") String workId,
      @HeaderParam("Authorization") String authorization,
      AgentWaiting body);

  /**
   * The body, exactly as qits-projects reads it.
   *
   * @param waiting whether the dispatched agent is waiting for its user
   * @param cause the hook event that said so
   * @param sessionId the agent session the report is about
   * @param at when the daemon saw it, epoch milliseconds
   */
  record AgentWaiting(boolean waiting, String cause, String sessionId, long at) {}
}
