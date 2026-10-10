package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for {@link AgentWaitingReporter}: records every relay, sends nothing. Recording only,
 * so being a bean for every {@code @QuarkusTest} in the module changes no other test.
 */
@ApplicationScoped
public class FakeAgentWaitingReporter implements AgentWaitingReporter {

  public record Report(
      String workId, boolean waiting, String cause, String waitingOn, String sessionId, long at) {}

  private final List<Report> reports = new CopyOnWriteArrayList<>();

  @Override
  public void report(
      String workId, boolean waiting, String cause, String waitingOn, String sessionId, long at) {
    reports.add(new Report(workId, waiting, cause, waitingOn, sessionId, at));
  }

  public List<Report> reportsFor(String workId) {
    return reports.stream().filter(r -> r.workId().equals(workId)).toList();
  }
}
