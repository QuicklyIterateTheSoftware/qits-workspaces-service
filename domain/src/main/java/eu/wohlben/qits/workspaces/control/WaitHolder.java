package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.AgentWait;
import java.util.UUID;

/**
 * How a log line names a wait's holder (qits-2668): one of the three {@link AgentWait} holders,
 * named with its kind first, so that each kind can be filtered by prefix: {@code agent <id>}, {@code
 * project-agent <subject> for <work item>} (qits-2596), or {@code desk <project id>} (qits-1618).
 * Every wait log line that names its holder takes the name from here, so none can print the
 * {@code agentId} of a wait that has none.
 */
final class WaitHolder {

  private WaitHolder() {}

  /** The holder of {@code wait}, whichever of the three it is. */
  static String of(AgentWait wait) {
    if (wait.isDeskWait()) {
      return desk(wait.deskProjectId);
    }
    if (wait.isProjectAgentWait()) {
      return projectAgent(wait.projectAgentSubject, wait.workQualifiedId);
    }
    return agent(wait.agentId);
  }

  /** An agent, by its id. */
  static String agent(UUID agentId) {
    return "agent " + agentId;
  }

  /** A project agent, by its bearer's subject and the work item the wait is held for, if known. */
  static String projectAgent(String subject, String workQualifiedId) {
    return workQualifiedId == null
        ? "project-agent " + subject
        : "project-agent " + subject + " for " + workQualifiedId;
  }

  /** A project's desk, by the project's id. */
  static String desk(UUID projectId) {
    return "desk " + projectId;
  }
}
