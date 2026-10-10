package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.WorkFiler;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * {@link WorkFiler} over {@code POST /projects/api/work}, with this service's machine bearer. A
 * captured page is filed as an IMPROVEMENT ticket: what it is is a person's to decide when they
 * triage it. {@code @DefaultBean}, so a test double wins without ambiguity.
 */
@ApplicationScoped
@DefaultBean
public class HttpWorkFiler implements WorkFiler {

  static final String TICKET = "TICKET";

  static final String TICKET_TYPE = "IMPROVEMENT";

  @Inject @RestClient ProjectsWork door;

  @Inject IdpProjectsBearer projectsBearer;

  @Override
  public Filed file(String projectId, String title, String impetus) {
    ProjectsWork.CreatedWork created =
        door.create(
            projectsBearer.authorization().orElse(null),
            new ProjectsWork.CreateWork(TICKET, projectId, title, impetus, TICKET_TYPE));
    if (created == null || created.id() == null || created.id().isBlank()) {
      throw new IllegalStateException("qits-projects filed the ticket without naming it");
    }
    return new Filed(created.id(), created.qualifiedId());
  }
}
