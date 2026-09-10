package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.AgentConfigurationDocument;
import eu.wohlben.qits.workspaces.control.AgentConfigurationSource;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * The {@link AgentConfigurationSource} this context ships: qits-projects' container door over HTTP,
 * through {@link ProjectsAgentConfiguration}.
 *
 * <p><b>Addressing is {@code qits.projects.url}</b> — the peer address this service already holds,
 * declared as a {@code serviceAddress} in {@code .config/qits/configuration.yml} rather than written
 * down as a literal anywhere. The path under it is composed here, because a peer's route grammar is
 * that peer's to change and an address with a path in it is this repository holding an opinion it
 * cannot keep current.
 *
 * <p><b>Empty means one thing only: no address is configured.</b> Every actual failure throws, and
 * none of them is an empty answer — there is no "no such document" either, since a surface
 * qits-projects has never been told about reads as its shipped default there, so a 200 always
 * carries the whole document. What can happen is that the service could not be
 * asked, refused, or answered something that is not a document — and the caller ({@code
 * WorkspaceService.fetchAgentConfigurationFor}) records the message on the workspace row and creates
 * the container anyway. That is why the messages name the address and the status: they are read by
 * whoever is wondering why a container is running on shipped defaults, not by a stack-trace reader.
 *
 * <p><b>A 404 is a failure here and not an absence</b>, which is the one place this class reads a
 * status differently from {@link HttpRepositoryLookup}. During the rollout it is the ordinary
 * answer — the door ships in a qits-projects release this service does not wait for — and reading it
 * as "no configuration exists" would write a null document and no error, which is exactly the silent
 * fallback the recorded one exists to prevent.
 *
 * <p>{@link DefaultBean} for {@link HttpRepositoryLookup}'s reason: the suite's fake wins over it by
 * being an ordinary bean, and without the annotation the two are an ambiguous dependency that fails
 * the build for every test at once.
 */
@ApplicationScoped
@DefaultBean
public class HttpAgentConfigurationSource implements AgentConfigurationSource {

  /**
   * The configured address, read only to decide whether this service is wired at all and to name it
   * in failures. The client itself is addressed by {@code quarkus.rest-client.qits-projects.url},
   * which application.properties derives from this same key.
   */
  @ConfigProperty(name = "qits.projects.url")
  Optional<String> baseUrl;

  @Inject @RestClient ProjectsAgentConfiguration configuration;

  @Inject IdpProjectsBearer projectsBearer;

  @Override
  public Optional<AgentConfigurationDocument> fetch() {
    String address = baseUrl.filter(url -> !url.isBlank()).orElse(null);
    if (address == null) {
      // Nowhere to ask, which is a configuration and not a failure — the second spelling of absent
      // the port documents. Only reachable in dev/test: a production build has already refused to
      // start without this key (HttpRepositoryLookup.assertConfigured), and HttpRepositoryLookup
      // short-circuits on it for the same reason.
      return Optional.empty();
    }
    String body;
    try {
      body = configuration.document(projectsBearer.authorization().orElse(null));
    } catch (WebApplicationException http) {
      throw new IllegalStateException(
          "qits-projects answered "
              + http.getResponse().getStatus()
              + " at "
              + address
              + "/projects/api/agent-configuration",
          http);
    } catch (RuntimeException transportFailure) {
      throw new IllegalStateException(
          "qits-projects unreachable at " + address + " while reading the agent configuration",
          transportFailure);
    }
    return Optional.of(AgentConfigurationDocument.of(body));
  }
}
