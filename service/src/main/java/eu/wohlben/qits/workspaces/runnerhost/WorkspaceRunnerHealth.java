package eu.wohlben.qits.workspaces.runnerhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.error.RunnerRefusals;
import eu.wohlben.qits.workspacesrunner.protocol.CheckResult;
import eu.wohlben.qits.workspacesrunner.protocol.HealthCheck;
import eu.wohlben.qits.workspacesrunner.protocol.HealthChecked;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>A workspace runner's health check, and how it gates the runner</b> (qits-850, epic qits-624):
 * when one is asked for, how its answer is settled, and what a quarantine or a reinstatement tells
 * the runner. qits-ci's {@code CiRunnerHealth} is the exemplar — the schedule, the names and the
 * shape are its — without the build-failure streak, which is about builds and has no counterpart
 * here.
 *
 * <p><b>The check is the runner's.</b> {@code healthCheck{requestId}} asks it to run every check it
 * has registered — {@code docker}, {@code workspaceImage}, {@code selfTest} (start the pinned
 * workspace image under its label, see it running, remove it), {@code nodeInventory}, {@code login}
 * and {@code session} today, an extensible set — and {@code healthChecked{ok, detail, requestId,
 * checks}} is its answer. {@code ok} is the verdict; the whole report is kept on the row, as the
 * capabilities' {@code health} key, beside {@code last_health_check_at}/{@code _ok}.
 *
 * <p><b>Quarantine.</b> A quarantined runner's slots are 0 — its {@code ack} carries 0 and its
 * {@code reserve} is answered {@code nothing} — while every workspace it already runs is left as it
 * is: a routed {@code stop} or {@code delete} still goes to it. A runner is quarantined:
 *
 * <ul>
 *   <li>at its registration ({@link WorkspaceRunners#AWAITING_FIRST_HEALTH_CHECK}): it has proved it
 *       holds a token, not that it can run a workspace;
 *   <li>when it <b>comes back</b> — its first socket after it held none for longer than the
 *       reconnect grace ({@link #RECONNECTED}). A reconnect inside the grace, the rollover
 *       successor included, is not a comeback. What is known about an absence is this process's
 *       memory of the drop, so a runner that reconnects to a freshly started qits-workspaces is not
 *       a comeback either: the absence was this service's;
 *   <li>when a check fails ({@code health check failed: <detail>}), or is not answered within
 *       {@code qits.workspaces.runner.healthcheck.timeout} ({@value #NO_ANSWER}). A runner already
 *       out keeps its {@code quarantined_at}, so the schedule carries on, and takes the newer reason.
 * </ul>
 *
 * <p><b>A check is sent</b> when a runner awaiting its first one or coming back is greeted, when
 * somebody asks ({@link #request}: an operator, the bootstrap or an agent — it reads and self-tests
 * only), and by {@link #sweep} for each quarantined, connected runner with none pending once it is
 * <b>due</b>. A passing check reinstates a quarantined runner ({@code reinstated{by: "health
 * check"}}, then {@code ack} with its slots); one that is in service stays so.
 *
 * <p><b>The schedule backs off, counted from the quarantine</b> — CI's, key for key. {@code
 * qits.workspaces.runner.healthcheck.schedule} is a list of offsets from {@code quarantined_at},
 * shipped {@code PT1M,PT15M,PT30M,PT60M,PT90M,PT120M,PT180M}, and after its last one more every
 * {@link #AFTER_SCHEDULE}. Those are the runner's slots; a check is due at the first slot after the
 * newest check sent or settled since the quarantine, or at the first slot when there is none ({@link
 * #nextSlot}). So a runner that was away at its slots gets one check on the first sweep after it is
 * back, and then waits for its next slot — never a backlog.
 *
 * <p><b>A pending check is memory, keyed by its {@code requestId}</b> (a UUID per sent {@code
 * healthCheck}), one per runner. An answer naming it settles it; an answer naming none — a runner
 * older than the field — settles the runner's pending one; an answer naming another (one already
 * settled as unanswered) is still the runner's newest word about itself and is settled as such. A
 * check sent on a connection that has since closed is not waited for at the runner's next greeting.
 * A restart forgets every pending check, which costs at most one check sent again: the rows, not the
 * memory, carry the verdicts and the schedule.
 *
 * <p><b>Nothing here may fail the frame or the request that caused it</b>, except the one door:
 * a check that could not be recorded is logged, and corrected by the next check or the next sweep.
 */
@ApplicationScoped
public class WorkspaceRunnerHealth {

  private static final Logger LOG = Logger.getLogger(WorkspaceRunnerHealth.class);

  /**
   * The gap between checks once {@code qits.workspaces.runner.healthcheck.schedule}'s offsets are
   * spent: one every hour, from its last, for as long as the runner stays out.
   */
  static final Duration AFTER_SCHEDULE = Duration.ofHours(1);

  /** {@code reinstated.by} when the runner's own health check passed. */
  public static final String BY_HEALTH_CHECK = "health check";

  /** The quarantine reason of a runner that came back after the reconnect grace. */
  public static final String RECONNECTED = "reconnected after being offline";

  /** The detail of a check its runner did not answer in time. */
  public static final String NO_ANSWER = "no answer";

  /** The prefix of a failed check's quarantine reason. */
  static final String FAILED = "health check failed";

  @Inject WorkspaceRunners runners;

  @Inject WorkspaceRunnerRegistry registry;

  @Inject ObjectMapper objectMapper;

  /** How long a sent check is waited for before it is settled {@value #NO_ANSWER}. */
  @ConfigProperty(name = "qits.workspaces.runner.healthcheck.timeout")
  Duration timeout;

  /** The schedule's offsets from a runner's quarantine — see the class javadoc. */
  @ConfigProperty(name = "qits.workspaces.runner.healthcheck.schedule")
  List<Duration> schedule;

  /** A check sent and not yet settled: its id, when it went, and the connection it went on. */
  record Pending(String requestId, Instant sentAt, WorkspaceRunnerRegistry.Session session) {}

  /** The one pending check per runner. */
  private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();

  /** When each runner was last sent a check by this process — the schedule's "newest check". */
  private final ConcurrentHashMap<UUID, Instant> newestSent = new ConcurrentHashMap<>();

  /** The runners that came back and are owed a check at their next greeting at the pin. */
  private final Set<UUID> owedOnGreeting = ConcurrentHashMap.newKeySet();

  // --- the triggers -------------------------------------------------------------------------------

  /**
   * Asks a connected runner for a health check now and answers the check's {@code requestId}. A
   * runner with one pending is answered that one's id and sent nothing more — the selfTest is not
   * run twice at once.
   *
   * @throws NotFoundException for no such runner
   * @throws ConflictException {@code RUNNER_UNAVAILABLE} when no connection of it is greeted at the
   *     pin
   */
  public String request(UUID runnerId) {
    WorkspaceRunner runner = runners.get(runnerId);
    String requestId = sendUnlessPending(runnerId, Instant.now());
    if (requestId == null) {
      throw new ConflictException(
          RunnerRefusals.RUNNER_UNAVAILABLE,
          "Runner " + runner.name + " is not connected, so it cannot run a health check now");
    }
    return requestId;
  }

  /**
   * The registry admitted the first socket of a runner that held none for longer than the reconnect
   * grace: it is quarantined ({@value #RECONNECTED}) — unless it already is, when its quarantine
   * stands — and owed a check at its greeting. Never throws.
   */
  void cameBack(UUID runnerId, String name, Duration away) {
    try {
      owedOnGreeting.add(runnerId);
      WorkspaceRunner quarantined = runners.quarantine(runnerId, RECONNECTED);
      LOG.infof(
          "Runner %s came back after %ss offline%s; it takes no workspace until a health check passes",
          name, away.toSeconds(), quarantined == null ? " (already quarantined)" : ", quarantined");
    } catch (RuntimeException e) {
      LOG.warnf(e, "Runner %s came back and could not be quarantined", name);
    }
  }

  /**
   * A connection of the runner was greeted at the pin as {@code row}: a runner awaiting its first
   * check, or one that came back, is sent one unless it has one pending on a live connection.
   */
  void onGreeted(WorkspaceRunner row) {
    boolean owed = owedOnGreeting.remove(row.id);
    if (!row.quarantined()
        || !(owed || WorkspaceRunners.AWAITING_FIRST_HEALTH_CHECK.equals(row.quarantineReason))) {
      return;
    }
    if (sendUnlessPending(row.id, Instant.now()) == null) {
      LOG.warnf("Runner %s was greeted and its health check could not be sent", row.name);
    }
  }

  /** The runner's row was deleted: nothing about it is waited for any more. */
  void forget(UUID runnerId) {
    pending.remove(runnerId);
    newestSent.remove(runnerId);
    owedOnGreeting.remove(runnerId);
  }

  // --- a settled check ----------------------------------------------------------------------------

  /** The runner answered: its pending check is settled, and the answer is recorded and acted on. */
  void onHealthChecked(UUID runnerId, HealthChecked checked) {
    Pending settled = claim(runnerId, checked.requestId());
    String requestId = checked.requestId();
    if (requestId == null && settled != null) {
      requestId = settled.requestId();
    }
    String detail = checked.detail() == null ? "" : checked.detail();
    Instant now = Instant.now();
    settle(
        runnerId,
        checked.ok(),
        detail,
        report(now, checked.ok(), detail, requestId, checked.checks()),
        now);
  }

  /**
   * The pending check {@code requestId} answers, removed: the one it names, or the runner's pending
   * one when it names none. Null when it names one that is not pending.
   */
  private Pending claim(UUID runnerId, String requestId) {
    if (requestId == null) {
      return pending.remove(runnerId);
    }
    Pending[] claimed = {null};
    pending.computeIfPresent(
        runnerId,
        (id, waiting) -> {
          if (requestId.equals(waiting.requestId())) {
            claimed[0] = waiting;
            return null;
          }
          return waiting;
        });
    return claimed[0];
  }

  /**
   * A check's verdict reached its runner's row: recorded first, whatever follows; a pass lifts a
   * quarantine ({@code reinstated}, then {@code ack} with the row's slots), a failure begins or
   * keeps one ({@code quarantined}, then {@code ack{0}}). Never throws.
   */
  private void settle(UUID runnerId, boolean ok, String detail, ObjectNode report, Instant at) {
    try {
      WorkspaceRunner row = runners.recordHealthCheck(runnerId, ok, at, report);
      if (row == null) {
        return;
      }
      if (ok) {
        LOG.infof("Health check of runner %s passed", row.name);
        if (row.quarantined()) {
          runners.greenlight(runnerId);
          LOG.infof("Runner %s passed its health check; it takes workspaces now", row.name);
          registry.reinstated(runnerId, BY_HEALTH_CHECK);
        }
        return;
      }
      String reason = truncate(detail.isBlank() ? FAILED : FAILED + ": " + detail);
      LOG.warnf("Health check of runner %s failed: %s", row.name, detail);
      WorkspaceRunners.Quarantine out = runners.quarantineFor(runnerId, reason);
      if (out.runner() != null && (out.began() || out.reasonChanged())) {
        registry.quarantined(runnerId, out.runner().quarantineReason, out.runner().quarantinedAt);
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Health check of runner %s could not be recorded", runnerId);
    }
  }

  /** The report the row keeps: {@code {at, ok, detail, requestId, checks:[{name, ok, detail, data}]}}. */
  private ObjectNode report(
      Instant at, boolean ok, String detail, String requestId, List<CheckResult> checks) {
    ObjectNode report = objectMapper.createObjectNode();
    report.put("at", at.toString());
    report.put("ok", ok);
    report.put("detail", detail);
    report.put("requestId", requestId);
    ArrayNode list = report.putArray("checks");
    for (CheckResult check : checks == null ? List.<CheckResult>of() : checks) {
      ObjectNode entry = list.addObject();
      entry.put("name", check.name());
      entry.put("ok", check.ok());
      entry.put("detail", check.detail());
      entry.set("data", objectMapper.valueToTree(check.data() == null ? Map.of() : check.data()));
    }
    return report;
  }

  // --- the schedule -------------------------------------------------------------------------------

  /**
   * Every {@code qits.workspaces.runner.healthcheck.sweep-interval}, a {@link #sweep}. {@link
   * Scheduled.ConcurrentExecution#SKIP}, and delayed by one interval so a booting process sends no
   * check before any runner could have dialled back.
   */
  @Scheduled(
      every = "{qits.workspaces.runner.healthcheck.sweep-interval}",
      delayed = "{qits.workspaces.runner.healthcheck.sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweepTick() {
    sweep(Instant.now());
  }

  /**
   * One pass, as of {@code now} — package-private because the tick is stretched out of a suite's
   * way, so this is what a test drives. First every check pending longer than {@code
   * qits.workspaces.runner.healthcheck.timeout} is settled failed ({@value #NO_ANSWER}); then every
   * quarantined runner greeted at the pin with no check pending on a live connection that is {@link
   * #due} is sent one.
   */
  void sweep(Instant now) {
    expireUnanswered(now);
    List<WorkspaceRunner> quarantined;
    try {
      quarantined = runners.list().stream().filter(WorkspaceRunner::quarantined).toList();
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the quarantined runners");
      return;
    }
    for (WorkspaceRunner runner : quarantined) {
      if (registry.serving(runner.id) == null || livePending(runner.id) != null || !due(runner, now)) {
        continue;
      }
      if (sendUnlessPending(runner.id, now) == null) {
        LOG.warnf("Runner %s is due a health check and it could not be sent", runner.name);
      }
    }
  }

  /** Whether a quarantined runner's next check has come — see {@link #nextSlot}. */
  private boolean due(WorkspaceRunner runner, Instant now) {
    Instant since = runner.quarantinedAt;
    if (since == null) {
      return false;
    }
    Instant newest = latestSince(since, newestSent.get(runner.id), runner.lastHealthCheckAt);
    return !nextSlot(since, newest).isAfter(now);
  }

  private static Instant latestSince(Instant since, Instant a, Instant b) {
    Instant newest = null;
    for (Instant at : new Instant[] {a, b}) {
      if (at != null && !at.isBefore(since) && (newest == null || at.isAfter(newest))) {
        newest = at;
      }
    }
    return newest;
  }

  /**
   * When a runner quarantined at {@code since} is next due a check: its first slot ({@code since}
   * plus an {@link #offset}) after {@code newest}, the newest check since the quarantine — or its
   * first slot of all when there is none.
   */
  Instant nextSlot(Instant since, Instant newest) {
    long k = 0;
    if (newest != null) {
      while (!since.plus(offset(k)).isAfter(newest)) {
        k++;
      }
    }
    return since.plus(offset(k));
  }

  /**
   * The k-th slot's offset from the quarantine: the schedule's k-th entry, and after its last one
   * more {@link #AFTER_SCHEDULE} per slot. An empty schedule is a slot every {@link #AFTER_SCHEDULE}.
   */
  Duration offset(long k) {
    List<Duration> offsets = schedule == null ? List.of() : schedule;
    if (k < offsets.size()) {
      return offsets.get((int) k);
    }
    Duration last = offsets.isEmpty() ? Duration.ZERO : offsets.get(offsets.size() - 1);
    return last.plus(AFTER_SCHEDULE.multipliedBy(k - offsets.size() + 1));
  }

  private void expireUnanswered(Instant now) {
    for (Map.Entry<UUID, Pending> entry : List.copyOf(pending.entrySet())) {
      Pending waiting = entry.getValue();
      if (waiting.sentAt().plus(timeout).isAfter(now) || !pending.remove(entry.getKey(), waiting)) {
        continue;
      }
      LOG.warnf(
          "Health check %s of runner %s was not answered within %s",
          waiting.requestId(), entry.getKey(), timeout);
      settle(
          entry.getKey(),
          false,
          NO_ANSWER,
          report(now, false, NO_ANSWER, waiting.requestId(), List.of()),
          now);
    }
  }

  // --- internals ----------------------------------------------------------------------------------

  /** The id of the runner's pending check, or null: what a suite waits on. */
  String pendingCheck(UUID runnerId) {
    Pending waiting = pending.get(runnerId);
    return waiting == null ? null : waiting.requestId();
  }

  /** The runner's pending check, unless the connection it went on has closed since. */
  private Pending livePending(UUID runnerId) {
    Pending waiting = pending.get(runnerId);
    return waiting != null && waiting.session().isOpen() ? waiting : null;
  }

  /**
   * Sends {@code healthCheck{requestId}} to the runner's serving connection and answers the id — or
   * the pending check's id when one is waiting on a live connection; null when no connection could
   * take it. The pending entry is written before the frame leaves, so an answer cannot outrun it.
   */
  private String sendUnlessPending(UUID runnerId, Instant now) {
    WorkspaceRunnerRegistry.Session session = registry.serving(runnerId);
    if (session == null) {
      return null;
    }
    Pending fresh = new Pending(UUID.randomUUID().toString(), now, session);
    Pending[] standing = {null};
    pending.compute(
        runnerId,
        (id, waiting) -> {
          if (waiting != null && waiting.session().isOpen()) {
            standing[0] = waiting;
            return waiting;
          }
          return fresh;
        });
    if (standing[0] != null) {
      return standing[0].requestId();
    }
    if (!registry.send(session, new HealthCheck(fresh.requestId()))) {
      pending.remove(runnerId, fresh);
      return null;
    }
    newestSent.put(runnerId, now);
    LOG.infof("Asked runner %s for health check %s", session.runnerName(), fresh.requestId());
    return fresh.requestId();
  }

  private static String truncate(String s) {
    return s.length() <= 1000 ? s : s.substring(0, 1000);
  }
}
