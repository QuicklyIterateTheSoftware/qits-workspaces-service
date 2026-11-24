package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.WorkItemLookup;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * {@link WorkItemLookup} over qits-projects' {@code getWork} ({@link ProjectsWorkItems}), the client
 * a wait's work-item lookup already uses ({@link HttpWaitSubjectResolver}), with the {@code qits}
 * oidc client's bearer (qits-2596). A 404 is no such item; any other failure throws, and the door
 * answers it as the error it is. {@code @DefaultBean}, so a suite's double wins without ambiguity.
 */
@ApplicationScoped
@DefaultBean
public class HttpWorkItemLookup implements WorkItemLookup {

  @Inject @RestClient ProjectsWorkItems workItems;

  @Inject IdpProjectsBearer bearer;

  @Override
  public Optional<WorkItem> find(String ref) {
    ProjectsWorkItems.WorkItem item;
    try {
      item = workItems.get(bearer.authorization().orElse(null), ref);
    } catch (WebApplicationException http) {
      int status = http.getResponse() == null ? 0 : http.getResponse().getStatus();
      if (status == 404) {
        return Optional.empty();
      }
      throw new IllegalStateException(
          "qits-projects answered " + status + " for the work item " + ref + ".", http);
    }
    if (item == null || item.id() == null) {
      return Optional.empty();
    }
    return Optional.of(
        new WorkItem(item.id(), item.qualifiedId(), item.archetype(), item.projectId()));
  }
}
