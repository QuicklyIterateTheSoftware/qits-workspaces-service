package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.AgentWaitingReporter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * The shipped {@link AgentWaitingReporter}: {@code POST /projects/api/work/{id}/agent-waiting}
 * through {@link ProjectsAgentWaiting}, with the bearer {@link HttpRepositoryLookup} sends ({@link
 * IdpProjectsBearer}).
 *
 * <p><b>A qits-projects older than the door is tolerated, and said once.</b> It answers the route
 * with 404 or 405, and so would every relay until it is upgraded; a WARN per flip would be a log
 * line per agent turn for something nobody here can fix. So the first one is logged at INFO and the
 * rest at DEBUG. A 404 is also how a newer qits-projects answers a work item it does not know, which
 * reads the same from here and is as little worth a WARN — the row names an item that is gone.
 *
 * <p>Any other refusal, and any transport failure, throws: the caller (the daemon registry's relay)
 * logs it and keeps its last relayed value unchanged, so the next frame of the same state tries
 * again.
 */
@ApplicationScoped
public class HttpAgentWaitingReporter implements AgentWaitingReporter {

  private static final Logger LOG = Logger.getLogger(HttpAgentWaitingReporter.class);

  @Inject @RestClient ProjectsAgentWaiting door;

  @Inject IdpProjectsBearer projectsBearer;

  /** Whether the "qits-projects has no such door" line has been logged at INFO yet. */
  private final AtomicBoolean absenceLogged = new AtomicBoolean();

  @Override
  public void report(String workId, boolean waiting, String cause, String sessionId, long at) {
    try {
      door.report(
          workId,
          projectsBearer.authorization().orElse(null),
          new ProjectsAgentWaiting.AgentWaiting(waiting, cause, sessionId, at));
    } catch (WebApplicationException http) {
      int status = http.getResponse().getStatus();
      if (status != 404 && status != 405) {
        throw new IllegalStateException(
            "qits-projects answered " + status + " to work item " + workId + "'s agent-waiting report",
            http);
      }
      if (absenceLogged.compareAndSet(false, true)) {
        LOG.infof(
            "qits-projects answered %s to work item %s's agent-waiting report: either it predates"
                + " the door or it does not know the item. Not retried; further answers like it are"
                + " logged at DEBUG",
            Integer.valueOf(status), workId);
      } else {
        LOG.debugf(
            "qits-projects answered %s to work item %s's agent-waiting report",
            Integer.valueOf(status), workId);
      }
    }
  }
}
