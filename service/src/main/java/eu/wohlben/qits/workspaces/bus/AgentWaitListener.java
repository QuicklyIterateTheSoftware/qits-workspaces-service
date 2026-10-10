package eu.wohlben.qits.workspaces.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.QitsRawEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.workspaces.control.AgentWaits;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * The bus end of declared waits (qits-1153, W1): every event whose name some OPEN wait names,
 * handed to {@link AgentWaits#onEvent}. This service's first listener.
 *
 * <p><b>Durable</b>, because a missed event is an agent that never wakes; the catch-up sweep reads
 * back what a restart or a disconnect missed. <b>{@code "*"}</b>, because the names are the waits'
 * and change with every declared wait — qits-ci's {@code CiEventTriggerListener} is the precedent.
 *
 * <p>{@link #selects} is cheap and pure: a lookup in the set of event names OPEN waits name, which
 * {@link AgentWaits} keeps in memory (refreshed on every create and close, and every scheduler
 * pass). So only events some wait could match leave a claim row.
 *
 * <p>{@link #onFrame} closes the matched waits and stores the wake in a transaction of its own, on
 * this service's datasource (the claim is on the eventstream one). It runs before the claim
 * commits: if it throws, the claim rolls back and the event stays owed; if the claim then fails,
 * the next delivery finds the waits closed and does nothing. The wake itself (the scheduler kick)
 * follows the commit.
 */
@ApplicationScoped
public class AgentWaitListener implements QitsDurableEventListener {

  /**
   * The storage key of this consumer's claims and watermark. Never change it, and never reuse it:
   * a new value is a new consumer starting at the head of the log.
   */
  static final String CONSUMER_ID = "qits-workspaces.agent-waits";

  @Inject AgentWaits waits;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(QitsRawEventListener.ALL);
  }

  @Override
  public boolean selects(EventFrame frame) {
    return waits.selects(frame.name());
  }

  @Override
  public void onFrame(EventFrame frame) {
    waits.onEvent(frame.id(), frame.name(), frame.occurredAt(), frame.payload());
  }
}
