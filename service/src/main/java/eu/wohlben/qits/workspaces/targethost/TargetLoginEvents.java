package eu.wohlben.qits.workspaces.targethost;

import eu.wohlben.qits.eventstream.QitsEventListener;
import eu.wohlben.qits.workspaces.control.AgentAttentions;
import eu.wohlben.qits.workspaces.control.TargetLogins;
import eu.wohlben.qits.workspaces.control.TargetPlacement;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * How this service learns that an execution target lost a harness's sign-in (qits-2088):
 * qits-system's {@link TargetLoginChanged} as it happens, and its target listing ({@code GET
 * /system/api/targets}, each target's {@code login}) every {@code
 * qits.workspaces.target-logins.reconcile-interval} (PT5M). Both are handed to {@link
 * TargetLogins}; when a sign-in began or stopped being ABSENT there, every agent is evaluated again
 * ({@link AgentAttentions#reevaluateAll}), which sets or clears {@code TARGET_LOGIN_ABSENT} on the
 * RUNNING agents whose workspace runs on that target.
 *
 * <p><b>A live listener, not a durable one</b>, for {@link TargetLeaseEvents}' reason: the listing
 * says what an event says, so a missed one costs at most an interval and needs no watermark. And
 * the stream has no replay, so the reconcile is also the seed: an interval job's first run is at
 * boot, and {@link TargetLogins} is in memory.
 */
@ApplicationScoped
public class TargetLoginEvents implements QitsEventListener<TargetLoginChanged> {

  private static final Logger LOG = Logger.getLogger(TargetLoginEvents.class);

  @Inject TargetLogins logins;

  @Inject AgentAttentions attentions;

  @Inject Instance<TargetPlacement> placement;

  @Override
  public Class<TargetLoginChanged> eventType() {
    return TargetLoginChanged.class;
  }

  @Override
  public void onEvent(TargetLoginChanged event) {
    if (event == null) {
      return;
    }
    UUID targetId = uuid(event.targetId());
    if (targetId == null) {
      LOG.debugf("TargetLoginChanged %s names no target id; ignored", event.eventId());
      return;
    }
    Instant checkedAt = instant(event.checkedAt());
    TargetLogins.Login login =
        new TargetLogins.Login(
            targetId,
            event.targetName(),
            event.claude(),
            event.kimi(),
            checkedAt != null ? checkedAt : event.occurredAt());
    if (logins.observe(login)) {
      LOG.infof(
          "Target %s's sign-in is claude %s, kimi %s; agents evaluated again",
          event.targetName() == null ? targetId : event.targetName(), event.claude(), event.kimi());
      attentions.reevaluateAll();
    }
  }

  @Scheduled(
      every = "{qits.workspaces.target-logins.reconcile-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      skipExecutionIf = Scheduled.ApplicationNotRunning.class)
  void reconcileOnSchedule() {
    reconcile();
  }

  /**
   * One pass: every listed target's sign-in, then every agent again when an absence began or ended.
   *
   * @return whether an absence began or ended; false too when the targets could not be listed
   */
  boolean reconcile() {
    if (!placement.isResolvable()) {
      return false;
    }
    List<TargetPlacement.Target> targets;
    try {
      targets = placement.get().targets();
    } catch (RuntimeException e) {
      // Keep what is held: an unreadable listing says nothing about anybody's sign-in.
      LOG.warnf(e, "Could not list the targets' sign-ins; the next pass tries again");
      return false;
    }
    boolean changed =
        logins.reconcile(
            targets.stream()
                .map(
                    t ->
                        new TargetLogins.Login(
                            t.id(), t.name(), t.claudeLogin(), t.kimiLogin(), t.loginCheckedAt()))
                .toList());
    if (changed) {
      attentions.reevaluateAll();
    }
    return changed;
  }

  private static UUID uuid(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(text.trim());
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }

  private static Instant instant(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(text.trim());
    } catch (DateTimeParseException notAnInstant) {
      return null;
    }
  }
}
