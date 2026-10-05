package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.error.RunnerRefusals;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link RunnerPlacement}: the runners' sockets as a set of connected runner ids and
 * a log of every call. It starts with <b>no runner connected</b>, which is what an absent port
 * means too, so a test that does not mean to place anything sees the shipped posture; a test that
 * connects one calls {@link #reset} in {@code @AfterEach}.
 *
 * <p>Every call is logged as {@code "<verb>:<rowId|runnerId>"}, newest last. The log is how a test
 * proves a DIRECT row never reached this port: it stays empty.
 */
@ApplicationScoped
public class FakeRunnerPlacement implements RunnerPlacement {

  private final Set<UUID> connected = ConcurrentHashMap.newKeySet();
  private final Set<UUID> timingOut = ConcurrentHashMap.newKeySet();
  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
  private volatile RuntimeException backlogFailure;

  /** The runner is connected from now on. */
  public void connect(UUID runnerId) {
    connected.add(runnerId);
  }

  /** The runner is gone, past its grace. */
  public void disconnect(UUID runnerId) {
    connected.remove(runnerId);
  }

  /** The runner is connected but never answers a routed verb: every one times out. */
  public void neverAnswer(UUID runnerId) {
    connected.add(runnerId);
    timingOut.add(runnerId);
  }

  /** Every backlog signal from now on throws {@code failure}: a start that fails in-request. */
  public void failBacklog(RuntimeException failure) {
    backlogFailure = failure;
  }

  /** Every call so far, newest last. */
  public List<String> calls() {
    synchronized (calls) {
      return List.copyOf(calls);
    }
  }

  /** Forget the calls so far, keeping who is connected. */
  public void clearCalls() {
    calls.clear();
  }

  /** Back to the shipped posture: nothing connected, nothing logged. */
  public void reset() {
    connected.clear();
    timingOut.clear();
    calls.clear();
    backlogFailure = null;
  }

  @Override
  public void backlogChanged(Workspace row) {
    calls.add("backlog:" + row.id);
    RuntimeException failure = backlogFailure;
    if (failure != null) {
      throw failure;
    }
  }

  @Override
  public void stop(Workspace row) {
    calls.add("stop:" + row.id);
    answer(row, "stop");
  }

  @Override
  public void delete(Workspace row) {
    calls.add("delete:" + row.id);
    answer(row, "delete its container");
  }

  private void answer(Workspace row, String verb) {
    if (row.runnerId == null || !connected.contains(row.runnerId)) {
      throw RunnerRefusals.unavailable(row.id, verb);
    }
    if (timingOut.contains(row.runnerId)) {
      throw RunnerRefusals.timeout(row.id, verb);
    }
  }

  @Override
  public void released(Workspace row) {
    calls.add("released:" + row.id);
  }

  @Override
  public boolean presence(UUID runnerId) {
    calls.add("presence:" + runnerId);
    return connected.contains(runnerId);
  }

  @Override
  public void estateChanged(UUID runnerId) {
    calls.add("estate:" + runnerId);
  }
}
