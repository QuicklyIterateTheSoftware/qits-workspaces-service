package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.AgentCapabilitySink;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The {@link AgentCapabilitySink} this context ships: qits-projects' ingest door over HTTP, through
 * {@link ProjectsAgentCapabilities}.
 *
 * <p><b>An HTTP hop and not an in-process write, because the catalogue is not here.</b> The store
 * lives in qits-projects — that service's own relay writes to it in process, and this one cannot;
 * the round trip is the module boundary, not an accident of layering. Addressing is {@code
 * qits.projects.url}, the peer address declared as a {@code serviceAddress} in {@code
 * .config/qits/configuration.yml}, and the path under it is composed by the client interface for
 * {@link HttpAgentConfigurationSource}'s reason.
 *
 * <p><b>The status split is what the caller needs, and it is the only reading done here.</b> A
 * <b>4xx</b> is the door refusing this report — an unknown harness, a body it will not read, a
 * credential it will not accept — and asking again changes nothing, so it is terminal and the relay
 * says so out loud. Everything else (a 5xx, a peer that is restarting, a connection that failed) is
 * qits-projects being temporarily unavailable, which is worth asking again about: the report is
 * still true and the container is still there. Nothing throws out of here — the caller is a
 * background window nobody is waiting on.
 *
 * <p>{@link DefaultBean} for {@link HttpAgentConfigurationSource}'s reason: a suite's fake wins over
 * it by being an ordinary bean, and without the annotation the two are an ambiguous dependency.
 */
@ApplicationScoped
@DefaultBean
public class HttpAgentCapabilitySink implements AgentCapabilitySink {

  /** Read only to decide whether this service is wired at all, and to name it in a failure. */
  @ConfigProperty(name = "qits.projects.url")
  Optional<String> baseUrl;

  @Inject @org.eclipse.microprofile.rest.client.inject.RestClient ProjectsAgentCapabilities door;

  @Inject IdpProjectsBearer projectsBearer;

  @Override
  public Ingest ingest(String reportBody) {
    String address = baseUrl.filter(url -> !url.isBlank()).orElse(null);
    if (address == null) {
      // Nowhere to write, which is a configuration rather than a failure — and only reachable in
      // dev/test, since a production build refuses to start without this key.
      return Ingest.notConfigured("no qits.projects.url is configured");
    }
    try {
      door.report(projectsBearer.authorization().orElse(null), reportBody);
      return Ingest.recorded("qits-projects recorded the report");
    } catch (WebApplicationException http) {
      int status = http.getResponse().getStatus();
      String detail =
          "qits-projects answered " + status + " at " + address + "/projects/api/agent-capabilities";
      return status >= 400 && status < 500 ? Ingest.refused(detail) : Ingest.unreachable(detail);
    } catch (RuntimeException transportFailure) {
      return Ingest.unreachable(
          "qits-projects unreachable at " + address + " while recording a capability report: "
              + transportFailure);
    }
  }
}
