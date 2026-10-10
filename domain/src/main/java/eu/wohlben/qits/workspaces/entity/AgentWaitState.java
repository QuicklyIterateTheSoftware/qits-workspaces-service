package eu.wohlben.qits.workspaces.entity;

/**
 * A declared wait's lifecycle (qits-1153): OPEN until an event matches it (MATCHED), its timeout
 * passes (TIMED_OUT) or it is cancelled (CANCELLED). Only OPEN is not final.
 */
public enum AgentWaitState {
  OPEN,
  MATCHED,
  TIMED_OUT,
  CANCELLED
}
