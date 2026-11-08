package eu.wohlben.qits.workspaces.entity;

/**
 * A declared wait's lifecycle (qits-1153): OPEN until an event matches it (MATCHED), its subject,
 * looked up, can no longer reach what it waits for (BROKEN, qits-1961: {@code broken_reason} says
 * why), its timeout — or the platform's ceiling, when it asked for none — passes (TIMED_OUT), or it
 * is cancelled (CANCELLED). Only OPEN is not final, and every OPEN wait has an expiry, so every wait
 * ends.
 */
public enum AgentWaitState {
  OPEN,
  MATCHED,
  TIMED_OUT,
  BROKEN,
  CANCELLED
}
