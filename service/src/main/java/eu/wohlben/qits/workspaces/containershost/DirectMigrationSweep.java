package eu.wohlben.qits.workspaces.containershost;

import eu.wohlben.qits.workspaces.control.DirectPlacementMove;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.ScheduledExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The direct-migration sweep (qits-776): every five minutes, two minutes after boot, one tick of
 * {@link DirectPlacementMove#sweep} — at most one regular workspace moved off the platform host
 * onto a runner, plus the teardown retry of any {@code direct-orphan}. What a tick decides, and the
 * one INFO line it logs per decision, is the domain's; this class is only the clock.
 *
 * <p><b>The cadence is code, not configuration.</b> The sweep exists to drain a handful of rows
 * written before qits-774 and then to find nothing, so there is no deployment that wants a different
 * interval, and a key nobody sets is a key nobody tests.
 *
 * <p><b>It never ticks under {@code @QuarkusTest}</b> ({@link NotInTests}): a suite's database holds
 * every suite's rows, and a tick landing mid-suite would move another test's DIRECT row from under
 * it. The tests drive {@link DirectPlacementMove#sweep} directly, on their own rows.
 */
@ApplicationScoped
public class DirectMigrationSweep {

  private static final Logger LOG = Logger.getLogger(DirectMigrationSweep.class);

  @Inject DirectPlacementMove moves;

  @Scheduled(
      identity = "direct-migration-sweep",
      every = "5m",
      delayed = "2m",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      skipExecutionIf = NotInTests.class)
  void tick() {
    try {
      moves.sweep();
    } catch (RuntimeException e) {
      LOG.warnf(e, "direct-migration sweep tick failed; the next one tries again");
    }
  }

  /** Skips every tick in the test launch mode; see the class javadoc. */
  @ApplicationScoped
  public static class NotInTests implements Scheduled.SkipPredicate {
    @Override
    public boolean test(ScheduledExecution execution) {
      return LaunchMode.current() == LaunchMode.TEST;
    }
  }
}
