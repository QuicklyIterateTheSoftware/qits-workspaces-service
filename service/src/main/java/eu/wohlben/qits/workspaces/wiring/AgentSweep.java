package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.AgentService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Runs the agent scheduler on a timer (qits-1152): every door kicks a pass, and this one catches
 * what no door kicks — a slot freed by a runner, a daemon that came back, an obsolete agent whose
 * idle time ran out. {@code qits.workspace.agent.sweep-interval}, blank to switch it off.
 */
@ApplicationScoped
public class AgentSweep {

  @Inject AgentService agents;

  void onStart(@Observes StartupEvent start) {
    agents.kick();
  }

  @Scheduled(
      every = "{qits.workspace.agent.sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      skipExecutionIf = Scheduled.ApplicationNotRunning.class)
  void sweep() {
    agents.kick();
  }
}
