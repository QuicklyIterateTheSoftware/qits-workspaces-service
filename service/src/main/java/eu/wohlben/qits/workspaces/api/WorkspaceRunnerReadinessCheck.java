package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * The workspace runners on this process's readiness, and <b>always UP</b>. Readiness gates swarm
 * routing, and a runner connects only once this process is routed to: a check that waited for a
 * runner would never come up and the deploy would roll back. So it reports, in memory and with no
 * row read, how many runners are connected — data for a person, never a verdict. qits-ci's {@code
 * CiRunnerReadinessCheck} makes the same choice.
 */
@Readiness
@ApplicationScoped
public class WorkspaceRunnerReadinessCheck implements HealthCheck {

  static final String NAME = "workspace-runners";

  @Inject WorkspaceRunnerRegistry registry;

  @Override
  public HealthCheckResponse call() {
    return HealthCheckResponse.named(NAME)
        .up()
        .withData("connectedRunners", registry.connectedRunnerIds().size())
        .build();
  }
}
