package eu.wohlben.qits.workspaces.wiring;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The container's door on qits-projects: the whole resolved agent configuration a workspace
 * container is created with.
 *
 * <p><b>The path is a cross-repo contract</b>, exactly as {@link ProjectsRepositories}' is. {@code
 * /projects/api} is qits-projects' own gateway segment, served by that service rather than added by
 * a proxy, which is why the configured base url carries no path and the same address works direct on
 * {@code qits-net} or through the gateway. The route is {@code
 * AgentConfigurationController}'s, at {@code qits:admin} + {@code qits:system} — this service
 * presents the second, on the same {@code projects} client the repository registry is read with.
 *
 * <p><b>It answers a {@code String} on purpose.</b> The body is stored on the workspace row and
 * handed to the container byte for byte, so binding it to records here would mean this repository
 * holding a third copy of a contract qits-projects owns and the shared harness library reads — and a
 * field added on either side would have to be released here before a container could see it.
 * Whatever validation is honest for a fetcher to do is {@code AgentConfigurationDocument}'s, and it
 * is deliberately shallow.
 */
@Path("/projects/api/agent-configuration")
@RegisterRestClient(configKey = "qits-projects")
public interface ProjectsAgentConfiguration {

  /** The whole document, as of now. A snapshot: there is no long poll and no event behind it. */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  String document(@HeaderParam("Authorization") String authorization);
}
