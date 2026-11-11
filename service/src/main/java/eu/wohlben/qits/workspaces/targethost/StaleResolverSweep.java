package eu.wohlben.qits.workspaces.targethost;

import eu.wohlben.qits.workspaces.control.StaleResolverRefresh;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Runs {@link StaleResolverRefresh} every {@code
 * qits.workspaces.target-leases.resolver-refresh-interval} (PT5M; {@code off} switches it off)
 * (qits-2091): an idle TARGET workspace whose lease reads {@code resolversStale} has its lease
 * refreshed, so its runner recreates the container on the same work volume with the node's current
 * resolvers. A pass never overlaps the one before it.
 */
@ApplicationScoped
public class StaleResolverSweep {

  private static final Logger LOG = Logger.getLogger(StaleResolverSweep.class);

  @Inject StaleResolverRefresh refresh;

  @Scheduled(
      every = "{qits.workspaces.target-leases.resolver-refresh-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      skipExecutionIf = Scheduled.ApplicationNotRunning.class)
  void sweep() {
    try {
      refresh.refreshStale();
    } catch (RuntimeException e) {
      LOG.warnf(e, "The stale-resolver refresh pass failed; the next pass tries again");
    }
  }
}
