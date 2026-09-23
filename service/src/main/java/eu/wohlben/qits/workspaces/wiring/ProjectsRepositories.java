package eu.wohlben.qits.workspaces.wiring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The one call qits-workspaces makes to another service: qits-projects' repository registry.
 *
 * <p><b>The path is a cross-repo contract.</b> {@code /projects/api} is qits-projects' own gateway
 * segment, served by that service rather than added by a proxy — which is precisely why the
 * configured base url carries no path and the same address works whether this call goes direct on
 * {@code qits-net} or through the gateway. If qits-projects ever moves its segment, this interface
 * and {@code HttpRepositoryLookupTest.theRequestGoesToTheProjectsSegment} are what notice.
 *
 * <p>The base url comes from {@code quarkus.rest-client.qits-projects.url}, which
 * application.properties derives from the application-owned {@code qits.projects.url}. That
 * indirection is not decoration: it is the same lesson {@code CaptureCorsRoute} learned the hard
 * way — an application-owned key is runtime-phase in both a JVM run and a native binary, and it is
 * the value a deployment actually sets. It also accepts {@code stork://qits-projects} unchanged if
 * this ever moves to real service discovery.
 */
@Path("/projects/api/repositories")
@RegisterRestClient(configKey = "qits-projects")
public interface ProjectsRepositories {

  /**
   * The repository behind {@code repoId}. Answers 404 when it does not exist — which
   * {@link HttpRepositoryLookup} turns into an empty {@link java.util.Optional}, and nothing else
   * does.
   */
  @GET
  @Path("/{repoId}")
  @Produces(MediaType.APPLICATION_JSON)
  GetRepositoryResponse get(
      @PathParam("repoId") String repoId, @HeaderParam("Authorization") String authorization);

  /** qits-projects' {@code RepositoryController.GetRepositoryRequest.Response}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record GetRepositoryResponse(Repository repository) {}

  /**
   * The four fields this context is entitled to. qits-projects' {@code RepositoryDto} also carries
   * {@code backupUrl}, {@code lastBackup} and {@code archetype}; not binding to them is what keeps
   * that service free to change them, and {@code ignoreUnknown} is what makes that true rather than
   * aspirational.
   *
   * <p>{@code projectId} and {@code name} joined for the {@code SCMRelease} this service used to
   * publish and stayed for the workspace daemon, which clones by the public {@code (project, name)}
   * pair — {@code id} is per-platform (a self-seeded repository's is a UUID) and no committed url
   * can name it.
   *
   * <p><b>{@code archetype} was bound here for one release and is unbound again.</b> It answered
   * exactly one question — is this repository a project's wrapper — which is what made a workspace on
   * its main branch the editor's, back when there was one editor per project. The editor is a single
   * container with a column of its own now, so the field has no reader; unbinding it is this
   * context going back to being entitled to less, which is the direction this record is supposed to
   * move in.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Repository(String id, String name, String mainBranch, String projectId) {}
}
