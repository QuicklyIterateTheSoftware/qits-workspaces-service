package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.DispatchService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Runs {@link DispatchService#drainHeldLaunches} on a schedule, beside the drain the service runs at
 * boot (qits-1064). Here and not in {@code domain} because the scheduler extension is this
 * deployable's, not the domain jar's.
 *
 * <p>The boot drain alone is not enough, and the gap is a deploy. This service deploys start-first,
 * so the new process boots and drains while the old one still holds the launches it is delivering;
 * the old one gives them back when it stops, a few seconds later, and by then the new one's boot
 * drain has run. The take those launches waited for has already happened, so no event will come for
 * them — this pass is what picks them up. It also picks up a launch whose runner had not reconnected
 * at boot, and one whose claim outlived a process that died without its shutdown.
 */
@ApplicationScoped
public class HeldAgentLaunchSweep {

  private static final Logger LOG = Logger.getLogger(HeldAgentLaunchSweep.class);

  @Inject DispatchService dispatches;

  @Scheduled(
      every = "{qits.workspace.agent-dispatch.held-launch-sweep-interval}",
      delayed = "{qits.workspace.agent-dispatch.held-launch-sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweep() {
    try {
      int resumed = dispatches.drainHeldLaunches();
      if (resumed > 0) {
        LOG.infof("resumed %d held agent launch(es)", Integer.valueOf(resumed));
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "could not drain the held agent launches; the next pass tries again");
    }
  }
}
