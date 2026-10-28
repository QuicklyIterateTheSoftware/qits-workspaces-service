package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.eventstream.control.EventFrame;
import java.time.Instant;
import java.util.List;

/**
 * Reads the event log back for a short time (qits-1668): a wait declared after its event already
 * happened replays the recent events of its name through the same matcher, so it fires at once.
 * Not every frame read back is replayed (qits-1677): one that occurred before the declaration may
 * match only when it is the latest event about the wait's stateful subject — a release request, a
 * run, a work item — so an outcome a newer event superseded does not fire; every other frame must
 * occur at or after the declaration. The port answers the whole window regardless; {@link
 * AgentWaits} chooses.
 *
 * <p>A port: the transport is qits-events' list API and lives in {@code service}. Injected as
 * {@code Instance<>}; absent means a wait sees new events only.
 */
public interface RecentEvents {

  /**
   * The events of {@code name} that occurred at or after {@code since}, oldest first.
   *
   * @throws RuntimeException when the log cannot be read; the caller logs it and goes on
   */
  List<EventFrame> since(String name, Instant since);
}
