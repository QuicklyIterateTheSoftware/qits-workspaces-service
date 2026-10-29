package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A scripted {@link AgentDaemon} (qits-1152): every call is recorded, every workspace is reachable
 * unless {@linkplain #disconnect disconnected}, and the cleanup check answers clean unless a test
 * scripts leftover work. A test can make the daemon {@linkplain #forget forget} an agent (its 404,
 * qits-1233) or script what a removal answers. {@link #reset} between tests.
 *
 * <p>Harnesses survive like the daemon's (qits-1225): the first start of an agent launches one with
 * the instruction as its opening turn; a later start finds it running and answers {@code
 * launched=false}, the instruction ignored, until {@link #restart} (a daemon restart) or a remove
 * forgets it.
 */
@ApplicationScoped
public class FakeAgentDaemon implements AgentDaemon {

  // Private, with methods: a test holds a client proxy, and a field read on it reads the proxy's.
  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

  private final List<Start> starts = Collections.synchronizedList(new ArrayList<>());

  public List<Start> starts() {
    synchronized (starts) {
      return List.copyOf(starts);
    }
  }

  private final Map<String, CleanupCheck> checks = new ConcurrentHashMap<>();

  /** The workspaces whose daemon cannot be reached (no tunnel). */
  private final Set<Long> unreachable = ConcurrentHashMap.newKeySet();

  /** The agents the daemon no longer knows: the check and every removal answer 404. */
  private final Set<String> forgotten = ConcurrentHashMap.newKeySet();

  /** What a removal of an agent answers, whatever its check says. */
  private final Map<String, Outcome> removeAnswers = new ConcurrentHashMap<>();
  /** The agents whose harness runs, by agent id. */
  private final Set<String> harnesses = ConcurrentHashMap.newKeySet();

  private volatile Outcome turnAnswer = Outcome.OK;

  /** The refusal every start answers with; null: starts are accepted. */
  private volatile String startRefusal;

  public void reset() {
    calls.clear();
    starts.clear();
    checks.clear();
    forgotten.clear();
    unreachable.clear();
    removeAnswers.clear();
    harnesses.clear();
    turnAnswer = Outcome.OK;
    startRefusal = null;
    interruptAnswer = null;
  }

  /** A daemon restart: every harness is gone; the next start of each agent launches one again. */
  public void restart() {
    harnesses.clear();
  }

  /** Every start is refused (409) with {@code detail} until {@link #acceptStarts}. */
  public void refuseStarts(String detail) {
    startRefusal = detail;
  }

  public void acceptStarts() {
    startRefusal = null;
  }

  public void leftover(String agentId, CleanupCheck check) {
    checks.put(agentId, check);
  }

  /** The workspace's daemon is not connected: {@link #reachable} is false until {@link #reset}. */
  public void disconnect(Long workspaceRowId) {
    unreachable.add(workspaceRowId);
  }

  /** The daemon removed the agent, or lost it: it answers 404 for it from now on. */
  public void forget(String agentId) {
    forgotten.add(agentId);
    harnesses.remove(agentId);
  }

  /** Every removal of {@code agentId} answers {@code outcome} until {@link #reset}. */
  public void removesAnswer(String agentId, Outcome outcome) {
    removeAnswers.put(agentId, outcome);
  }

  public void turnsAnswer(Outcome outcome) {
    turnAnswer = outcome;
  }

  public List<String> callsFor(String agentId) {
    synchronized (calls) {
      return calls.stream().filter(c -> c.contains(agentId)).toList();
    }
  }

  @Override
  public boolean reachable(Long workspaceRowId) {
    return !unreachable.contains(workspaceRowId);
  }

  @Override
  public Started start(Long workspaceRowId, Start start) {
    calls.add("start " + workspaceRowId + " " + start.agentId() + " " + start.instruction());
    starts.add(start);
    String refusal = startRefusal;
    if (refusal != null) {
      return Started.of(Outcome.REFUSED, refusal);
    }
    boolean launched = harnesses.add(start.agentId());
    return new Started(
        Outcome.OK,
        "cmd-" + start.agentId(),
        "session-" + start.agentId(),
        start.sessionId() != null,
        launched,
        null);
  }

  @Override
  public Outcome turn(Long workspaceRowId, String agentId, String text) {
    calls.add("turn " + workspaceRowId + " " + agentId + " " + text);
    return turnAnswer;
  }

  @Override
  public Outcome entity(Long workspaceRowId, String agentId, EntityFacts facts, String blockSource) {
    calls.add("entity " + workspaceRowId + " " + agentId + " " + facts.status());
    return Outcome.OK;
  }

  @Override
  public CleanupAnswer cleanupCheck(Long workspaceRowId, String agentId) {
    calls.add("cleanup-check " + workspaceRowId + " " + agentId);
    if (forgotten.contains(agentId)) {
      return CleanupAnswer.unknownAgent();
    }
    return CleanupAnswer.found(
        checks.getOrDefault(agentId, new CleanupCheck(true, List.of(), List.of())));
  }

  @Override
  public Outcome remove(Long workspaceRowId, String agentId, boolean force) {
    calls.add("remove " + workspaceRowId + " " + agentId + " force=" + force);
    if (forgotten.contains(agentId)) {
      return Outcome.UNKNOWN_AGENT;
    }
    Outcome scripted = removeAnswers.get(agentId);
    if (scripted != null) {
      return scripted;
    }
    CleanupCheck check = checks.get(agentId);
    if (check != null && !force) {
      if (check.busy()) {
        return Outcome.BUSY;
      }
      if (!check.clean()) {
        return Outcome.REFUSED;
      }
    }
    harnesses.remove(agentId);
    return Outcome.OK;
  }

  /** Stops the harness, as the daemon's yield does; the next start launches one again. */
  @Override
  public Outcome yield(Long workspaceRowId, String agentId) {
    calls.add("yield " + workspaceRowId + " " + agentId);
    if (unreachable.contains(workspaceRowId)) {
      return Outcome.UNREACHABLE;
    }
    if (forgotten.contains(agentId)) {
      return Outcome.UNKNOWN_AGENT;
    }
    harnesses.remove(agentId);
    return Outcome.OK;
  }

  /** What the next interrupts answer; null: OK. */
  private volatile Interrupted interruptAnswer;

  public void interruptsAnswer(Interrupted answer) {
    interruptAnswer = answer;
  }

  @Override
  public Interrupted interrupt(Long workspaceRowId, String agentId) {
    calls.add("interrupt " + workspaceRowId + " " + agentId);
    Interrupted scripted = interruptAnswer;
    return scripted != null ? scripted : new Interrupted(Outcome.OK, null);
  }
}
