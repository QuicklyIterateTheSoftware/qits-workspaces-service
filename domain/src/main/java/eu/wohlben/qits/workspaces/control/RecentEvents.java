package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.eventstream.control.EventFrame;
import java.time.Instant;
import java.util.List;

/**
 * Reads the event log back for a short time (qits-1668): a wait declared after its event already
 * happened replays the recent events of its name through the same matcher, so it fires at once.
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
