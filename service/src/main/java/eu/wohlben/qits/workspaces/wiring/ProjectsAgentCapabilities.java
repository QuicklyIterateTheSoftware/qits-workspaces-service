package eu.wohlben.qits.workspaces.wiring;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The ingest door on qits-projects: what the harnesses in one workspace container can be configured
 * with, as that container's daemon reported it.
 *
 * <p><b>The sibling of {@link ProjectsAgentConfiguration}, on the same client and the same
 * contract.</b> {@code /projects/api} is qits-projects' own gateway segment served by that service,
 * so the configured base url carries no path and one address works direct on {@code qits-net} or
 * through the gateway. The route is {@code AgentCapabilityController}'s, at {@code qits:admin} +
 * {@code qits:system}; this service presents the second, on the {@code projects} oidc client the
 * repository registry and the configuration fetch already use.
 *
 * <p><b>It takes a {@code String}, and that is the point rather than an economy.</b> The body is the
 * daemon's {@code GET /agents/available} answer passed through unchanged — the ingest door was built
 * for exactly that and ignores the two members it has no use for. Binding it to records here would
 * put a copy of qits-projects' contract in this repository, and a field added on either side would
 * have to be released here before a catalogue could see it.
 */
@Path("/projects/api/agent-capabilities")
@RegisterRestClient(configKey = "qits-projects")
public interface ProjectsAgentCapabilities {

  /** Record one container's report. Answers {@code {"recorded": n}}; nobody waits on the number. */
  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  String report(@HeaderParam("Authorization") String authorization, String report);
}
