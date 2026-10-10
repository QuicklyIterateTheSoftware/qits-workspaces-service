package eu.wohlben.qits.workspaces.wiring;

import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import eu.wohlben.qits.workspaces.testing.contracts.ConsumerPacts;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * <b>The agent-waiting report, {@code POST /projects/api/work/{id}/agent-waiting}</b> — the one call
 * to qits-projects that {@link ProjectsContract} does not hold yet, because qits-projects records no
 * answer for it. {@link HttpAgentWaitingReporter} reads the status alone, so the interaction is
 * status-only; the body is this service's own.
 *
 * <p><b>The trigger is a daemon frame, not a door.</b> The workspace daemon tells this service over
 * its control socket that the dispatched agent waits (a Claude hook), and {@code
 * WorkspaceDaemonRegistry} relays it. No bus event and no openapi operation enters the path, so the
 * trigger is the frame's own name.
 *
 * <p>Disabled until qits-projects records the state; then add the interaction to {@link
 * ProjectsContract} so it is written into the committed pact, and delete this class.
 */
@QuarkusTest
@Disabled(
    "needs provider state 'a ticket with a dispatched agent' for reportWorkAgentWaiting in"
        + " qits-projects-service")
public class ProjectsAgentWaitingPactTest {

  static final String STATE = "a ticket with a dispatched agent";
  static final String OPERATION = "reportWorkAgentWaiting";

  @Test
  @Disabled(
      "needs provider state 'a ticket with a dispatched agent' for reportWorkAgentWaiting in"
          + " qits-projects-service")
  public void theReportIsWhatQitsProjectsTakes() {
    Map<String, String> params = GoldenMasters.params(GoldenMasters.PROJECTS, STATE);
    PactDslJsonBody body =
        new PactDslJsonBody()
            .booleanType("waiting", true)
            .stringType("cause", "Stop")
            .stringType("sessionId", "s-1")
            .integerType("at", 1234L);
    ConsumerPacts.verify(
        ConsumerPacts.pact(
            GoldenMasters.PROJECTS,
            builder ->
                GoldenMasters.interaction(
                    builder,
                    GoldenMasters.PROJECTS,
                    STATE,
                    OPERATION,
                    Trigger.event("workspace-daemon:agent-waiting"),
                    body,
                    List.of())),
        url -> reporterAgainst(url).report(params.get("ticketId"), true, "Stop", "s-1", 1234L));
  }

  private static HttpAgentWaitingReporter reporterAgainst(String baseUrl) {
    HttpAgentWaitingReporter reporter = new HttpAgentWaitingReporter();
    reporter.projectsBearer =
        new IdpProjectsBearer() {
          @Override
          public Optional<String> authorization() {
            return Optional.empty();
          }
        };
    reporter.door =
        QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(baseUrl))
            .build(ProjectsAgentWaiting.class);
    return reporter;
  }
}
