package eu.wohlben.qits.workspaces.control;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelectionEvaluator;
import eu.wohlben.qits.eventstream.selection.EventSelectionException;
import eu.wohlben.qits.eventstream.selection.EventSelectionGrammar;
import eu.wohlben.qits.workspaces.dto.AgentWaitDto;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.ForbiddenException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.persistence.AgentWaitRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>Declared waits</b> (qits-1153, W1–W8 of the superproject's {@code
 * agent-workspace-decoupling-plan.md}): an agent says which event it waits on, and gets a turn when
 * one arrives or the wait times out.
 *
 * <ul>
 *   <li><b>Matching</b> ({@link #onEvent}): the bus listener hands every event whose name some OPEN
 *       wait names ({@link #selects}, an in-memory set); the OPEN waits of that name are evaluated
 *       with qits-eventstream's {@code EventSelectionEvaluator}. A match closes the wait (MATCHED)
 *       and stores the wake as the agent's next turn ({@code pending_text}) in one transaction; the
 *       scheduler is kicked after the commit. Only OPEN waits match, so a repeated delivery of an
 *       event changes nothing.
 *   <li><b>The wake</b> (W6) is the qits-1152 turn path: the scheduler says {@code pending_text} to a
 *       running agent at its next turn boundary, and resumes a yielded one first (D17).
 *   <li><b>The sweep</b> ({@link #sweep}, every scheduler pass): an OPEN wait past {@code expires_at}
 *       is TIMED_OUT and wakes its agent; an OPEN wait of an agent that is no longer ACTIVE is
 *       CANCELLED with no wake (W6).
 *   <li><b>Waiting on</b> (W5): while an agent has an OPEN wait, its waiting relay to qits-projects
 *       says {@code cause: "Wait"} and names the wait's label in {@code waitingOn}, so the item is
 *       read as waiting on that, not blocked.
 * </ul>
 */
@ApplicationScoped
public class AgentWaits {

  private static final Logger LOG = Logger.getLogger(AgentWaits.class);

  /** At most this many OPEN waits per agent. */
  public static final int MAX_OPEN_PER_AGENT = 8;

  /** The longest timeout an agent may ask for. */
  public static final Duration MAX_TIMEOUT = Duration.ofDays(30);

  static final int MAX_LABEL = 200;

  /** How much of a matched event's payload the wake quotes. */
  static final int MAX_PAYLOAD_SUMMARY = 1000;

  /** The relay causes this class sends (beside the hook names the daemon reports). */
  public static final String CAUSE_WAIT = "Wait";

  public static final String CAUSE_MATCHED = "WaitMatched";

  public static final String CAUSE_TIMED_OUT = "WaitTimedOut";

  public static final String CAUSE_CANCELLED = "WaitCancelled";

  @Inject AgentWaitRepository waits;

  @Inject AgentWorktreeRepository agents;

  @Inject AgentService agentService;

  @Inject Instance<AgentWaitingReporter> waitingReporter;

  /** The event names some OPEN wait names. Replaced whole; read without a lock. */
  private volatile Set<String> openNames = Set.of();

  /** Whether {@link #openNames} was read from the database since boot. */
  private volatile boolean namesLoaded;

  private final Object namesLock = new Object();

  // --- the doors ----------------------------------------------------------------------------------

  /**
   * W4: an agent's own token may act on its waits only. {@code subject} is the caller's principal;
   * it must be the subject of the agent's own credential. The caller decides when this applies
   * (a person and the platform act on any agent).
   *
   * @throws NotFoundException no such agent
   * @throws ForbiddenException the caller is not that agent
   */
  public void requireOwnAgent(UUID agentId, String subject) {
    AgentWorktree agent = requireAgent(agentId);
    if (subject == null
        || agent.commissionedTokenSubject == null
        || !subject.equals(agent.commissionedTokenSubject)) {
      throw new ForbiddenException(
          "An agent may declare, list and cancel only its own waits; this credential is not agent "
              + agentId
              + "'s.");
    }
  }

  /**
   * Declare a wait. It stays OPEN until an event matches {@code selection}, {@code timeout} passes,
   * or it is cancelled. Declaring does not yield the agent (W5).
   *
   * @param label what the agent calls it; null = the selection's one-line description
   * @param timeout null = never
   * @throws NotFoundException no such agent
   * @throws ConflictException {@code AGENT_NOT_ACTIVE} or {@code TOO_MANY_WAITS}
   * @throws BadRequestException a timeout that is not positive or longer than {@link #MAX_TIMEOUT}
   */
  public AgentWaitDto create(UUID agentId, String label, EventSelection selection, Duration timeout) {
    if (selection == null) {
      throw new BadRequestException("A wait needs a selection: {\"event\": …, \"when\": […]}.");
    }
    if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
      throw new BadRequestException("A timeout must be positive, got " + timeout + ".");
    }
    if (timeout != null && timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw new BadRequestException(
          "A timeout may be at most " + MAX_TIMEOUT + " (30 days), got " + timeout + ".");
    }
    String named = label == null || label.isBlank() ? EventSelectionGrammar.describe(selection) : label.trim();
    String finalLabel = bounded(named, MAX_LABEL);
    UUID id = UUID.randomUUID();
    AgentWorktree agent =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  AgentWorktree row =
                      agents
                          .findByIdOptional(agentId, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                          .orElseThrow(() -> new NotFoundException("Agent not found: " + agentId));
                  if (row.state != AgentState.ACTIVE) {
                    throw new ConflictException(
                        "AGENT_NOT_ACTIVE",
                        "Agent " + agentId + " is " + row.state + "; only an ACTIVE agent waits.");
                  }
                  if (waits.countOpen(agentId) >= MAX_OPEN_PER_AGENT) {
                    throw new ConflictException(
                        "TOO_MANY_WAITS",
                        "Agent "
                            + agentId
                            + " already has "
                            + MAX_OPEN_PER_AGENT
                            + " open waits; cancel one first.");
                  }
                  AgentWait wait = new AgentWait();
                  wait.id = id;
                  wait.agentId = agentId;
                  wait.label = finalLabel;
                  wait.selection = selection.toJson();
                  wait.eventName = selection.event();
                  wait.createdAt = Instant.now();
                  wait.expiresAt = timeout == null ? null : wait.createdAt.plus(timeout);
                  waits.persist(wait);
                  return row;
                });
    addName(selection.event());
    LOG.infof("Agent %s waits on %s (%s)", agentId, finalLabel, id);
    relayIfWaiting(agent, CAUSE_WAIT);
    return get(agentId, id);
  }

  /** An agent's waits, newest first; {@code state} null = all. */
  public List<AgentWaitDto> list(UUID agentId, AgentWaitState state) {
    requireAgent(agentId);
    return QuarkusTransaction.requiringNew()
        .call(() -> waits.findByAgent(agentId, state).stream().map(AgentWaits::view).toList());
  }

  /** One wait of the agent. */
  public AgentWaitDto get(UUID agentId, UUID waitId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              AgentWait wait = waits.findById(waitId);
              if (wait == null || !wait.agentId.equals(agentId)) {
                throw new NotFoundException("Wait not found: " + waitId);
              }
              return view(wait);
            });
  }

  /**
   * Cancel a wait. An OPEN one becomes CANCELLED; a closed one is answered as it is (idempotent).
   * No turn is given.
   */
  public AgentWaitDto cancel(UUID agentId, UUID waitId) {
    boolean closed =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  AgentWait wait =
                      waits
                          .findByIdOptional(waitId, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                          .filter(w -> w.agentId.equals(agentId))
                          .orElseThrow(() -> new NotFoundException("Wait not found: " + waitId));
                  if (wait.state != AgentWaitState.OPEN) {
                    return false;
                  }
                  close(wait, AgentWaitState.CANCELLED, null);
                  return true;
                });
    if (closed) {
      refreshNames();
      LOG.infof("Agent %s's wait %s was cancelled", agentId, waitId);
      AgentWorktree agent = QuarkusTransaction.requiringNew().call(() -> agents.findById(agentId));
      relayIfWaiting(agent, CAUSE_CANCELLED);
    }
    return get(agentId, waitId);
  }

  // --- matching -----------------------------------------------------------------------------------

  /**
   * Whether some OPEN wait names this event: the listener's cheap, pure narrowing. The first call
   * after boot reads the names from the database, so a catch-up that runs before the first
   * scheduler pass does not settle an event a wait wants; if that read fails, this throws and the
   * event stays owed.
   */
  public boolean selects(String eventName) {
    if (!namesLoaded) {
      refreshNames();
    }
    return eventName != null && openNames.contains(eventName);
  }

  /**
   * An event arrived. Every OPEN wait of its name whose selection matches is closed MATCHED, and
   * its agent's next turn says what fired. Runs in a transaction of its own and throws when it
   * cannot finish, so the caller (the durable listener) leaves the event owed; a second delivery
   * finds the waits closed and does nothing.
   *
   * @return how many waits matched
   */
  public int onEvent(String eventId, String name, Instant occurredAt, String payload) {
    if (name == null || name.isBlank()) {
      return 0;
    }
    JsonNode tree = EventSelectionEvaluator.parsePayload(payload);
    List<Woken> woken =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<Woken> out = new ArrayList<>();
                  for (AgentWait wait : waits.findOpenByEventForUpdate(name)) {
                    EventSelection selection;
                    try {
                      selection = EventSelection.fromJson(wait.selection);
                    } catch (EventSelectionException unreadable) {
                      LOG.warnf("Wait %s has an unreadable selection; skipped: %s", wait.id, unreadable.getMessage());
                      continue;
                    }
                    if (!EventSelectionEvaluator.matches(selection, name, tree)) {
                      continue;
                    }
                    close(wait, AgentWaitState.MATCHED, eventId);
                    String turn = matchedTurn(wait.label, name, eventId, occurredAt, payload);
                    AgentWorktree agent = wake(wait.agentId, turn);
                    if (agent != null) {
                      out.add(new Woken(agent.workId, agent.sessionId));
                    }
                    LOG.infof(
                        "Agent %s's wait %s matched %s %s", wait.agentId, wait.label, name, eventId);
                  }
                  return out;
                });
    if (woken.isEmpty()) {
      return 0;
    }
    refreshNames();
    for (Woken w : woken) {
      relay(w.workId(), false, CAUSE_MATCHED, null, w.sessionId(), Instant.now());
    }
    agentService.kick();
    return woken.size();
  }

  /**
   * One sweep: time out OPEN waits past their expiry (each wakes its agent), cancel the OPEN waits
   * of agents that are no longer ACTIVE (no wake), and refresh the event names {@link #selects}
   * reads. Run on every scheduler pass.
   */
  public void sweep() {
    Instant now = Instant.now();
    List<Woken> woken =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<Woken> out = new ArrayList<>();
                  for (AgentWait wait : waits.findOpenOfInactiveAgentsForUpdate()) {
                    close(wait, AgentWaitState.CANCELLED, null);
                    LOG.infof("Agent %s is no longer active: its wait %s is cancelled", wait.agentId, wait.label);
                  }
                  for (AgentWait wait : waits.findExpiredForUpdate(now)) {
                    close(wait, AgentWaitState.TIMED_OUT, null);
                    Duration after = Duration.between(wait.createdAt, wait.expiresAt);
                    AgentWorktree agent =
                        wake(
                            wait.agentId,
                            "Your wait \""
                                + wait.label
                                + "\" timed out after "
                                + human(after)
                                + ": no "
                                + wait.eventName
                                + " event matched it. It is closed; declare a new one if you still"
                                + " need to wait.");
                    if (agent != null) {
                      out.add(new Woken(agent.workId, agent.sessionId));
                    }
                    LOG.infof("Agent %s's wait %s timed out", wait.agentId, wait.label);
                  }
                  return out;
                });
    refreshNames();
    for (Woken w : woken) {
      relay(w.workId(), false, CAUSE_TIMED_OUT, null, w.sessionId(), now);
    }
    if (!woken.isEmpty()) {
      agentService.kick();
    }
  }

  // --- waiting on (W5) ----------------------------------------------------------------------------

  /** The labels of each agent's OPEN waits, oldest first, joined; agents with none are absent. */
  public Map<UUID, String> waitingOn(Collection<UUID> agentIds) {
    Map<UUID, List<String>> labels = new LinkedHashMap<>();
    for (AgentWait wait : waits.findOpenByAgents(agentIds)) {
      labels.computeIfAbsent(wait.agentId, id -> new ArrayList<>()).add(wait.label);
    }
    Map<UUID, String> out = new LinkedHashMap<>();
    labels.forEach((id, list) -> out.put(id, bounded(String.join("; ", list), 4 * MAX_LABEL)));
    return out;
  }

  /** {@link #waitingOn(Collection)} for one agent, in a transaction of its own; null = none. */
  public String waitingOn(UUID agentId) {
    return QuarkusTransaction.requiringNew().call(() -> waitingOn(List.of(agentId)).get(agentId));
  }

  /**
   * Relay an agent's waiting state to qits-projects (qits-895). A waiting agent with an OPEN wait is
   * reported as {@code cause: "Wait"} with {@code waitingOn}, whatever the hook said (W5).
   */
  void relayActivity(String workId, UUID agentId, boolean waiting, String hookEvent, String sessionId, Instant at) {
    String on = waiting ? waitingOn(agentId) : null;
    relay(workId, waiting, on != null ? CAUSE_WAIT : hookEvent, on, sessionId, at);
  }

  /** A wait opened or closed while the agent waits for input: say what it waits on now. */
  private void relayIfWaiting(AgentWorktree agent, String cause) {
    if (agent == null || agent.waitingSince == null) {
      return;
    }
    String on = waitingOn(agent.id);
    relay(agent.workId, true, on != null ? CAUSE_WAIT : cause, on, agent.sessionId, Instant.now());
  }

  private void relay(String workId, boolean waiting, String cause, String waitingOn, String sessionId, Instant at) {
    if (!waitingReporter.isResolvable()) {
      return;
    }
    try {
      waitingReporter.get().report(workId, waiting, cause, waitingOn, sessionId, at.toEpochMilli());
    } catch (RuntimeException e) {
      LOG.warnf(e, "could not relay work item %s waiting=%s (%s) to qits-projects", workId, waiting, cause);
    }
  }

  // --- helpers ------------------------------------------------------------------------------------

  private record Woken(String workId, String sessionId) {}

  /** The wake: the turn is appended to what the agent has still to hear. Called in a transaction. */
  private AgentWorktree wake(UUID agentId, String turn) {
    AgentWorktree agent = agents.findById(agentId);
    if (agent == null || agent.state != AgentState.ACTIVE) {
      return null;
    }
    agent.pendingText = agent.pendingText == null ? turn : agent.pendingText + "\n\n" + turn;
    // It is about to work, not waiting: the next Stop is a fresh flip.
    agent.waitingSince = null;
    return agent;
  }

  private static void close(AgentWait wait, AgentWaitState state, String eventId) {
    wait.state = state;
    wait.closedAt = Instant.now();
    wait.matchedEventId = eventId;
  }

  static String matchedTurn(String label, String name, String eventId, Instant occurredAt, String payload) {
    StringBuilder turn =
        new StringBuilder("Your wait \"")
            .append(label)
            .append("\" matched: ")
            .append(name)
            .append(" at ")
            .append(occurredAt == null ? "an unknown time" : occurredAt.toString())
            .append(" (event ")
            .append(eventId)
            .append(").");
    if (payload != null && !payload.isBlank()) {
      turn.append("\nPayload: ").append(bounded(payload.strip(), MAX_PAYLOAD_SUMMARY));
    }
    return turn.toString();
  }

  static String human(Duration d) {
    long minutes = d.toMinutes();
    if (minutes < 1) {
      return d.toSeconds() + "s";
    }
    if (minutes < 120) {
      return minutes + "m";
    }
    long hours = d.toHours();
    return hours < 48 ? hours + "h" : d.toDays() + "d";
  }

  static String bounded(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }

  private void addName(String name) {
    synchronized (namesLock) {
      if (!openNames.contains(name)) {
        Set<String> next = new java.util.HashSet<>(openNames);
        next.add(name);
        openNames = Set.copyOf(next);
      }
    }
  }

  /** Re-read the OPEN waits' event names. Read and replace under one lock, so an add is not lost. */
  void refreshNames() {
    synchronized (namesLock) {
      openNames = Set.copyOf(QuarkusTransaction.requiringNew().call(() -> waits.openEventNames()));
      namesLoaded = true;
    }
  }

  private AgentWorktree requireAgent(UUID agentId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                agents
                    .findByIdOptional(agentId)
                    .orElseThrow(() -> new NotFoundException("Agent not found: " + agentId)));
  }

  static AgentWaitDto view(AgentWait wait) {
    return new AgentWaitDto(
        wait.id.toString(),
        wait.agentId.toString(),
        wait.label,
        wait.selection,
        wait.state,
        wait.createdAt,
        wait.expiresAt,
        wait.matchedEventId,
        wait.closedAt);
  }
}
