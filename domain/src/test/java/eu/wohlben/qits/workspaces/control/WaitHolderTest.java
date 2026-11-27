package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.entity.AgentWait;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** How a wait log line names the wait's holder (qits-2668): each kind first, then its ids. */
class WaitHolderTest {

  private static final UUID AGENT = UUID.fromString("5a1759ed-aede-4886-ab92-69a28f125a28");
  private static final UUID PROJECT = UUID.fromString("8273c743-e0bb-4d93-90d4-391aa10d9151");
  private static final String SUBJECT = "tok-project-agent-qits-developer";

  @Test
  void eachKindIsNamedByItsKindThenItsIds() {
    assertEquals("agent " + AGENT, WaitHolder.agent(AGENT));
    assertEquals("desk " + PROJECT, WaitHolder.desk(PROJECT));
    assertEquals(
        "project-agent " + SUBJECT + " for qits-2573", WaitHolder.projectAgent(SUBJECT, "qits-2573"));
  }

  @Test
  void aProjectAgentWithNoWorkItemIsNamedByItsSubjectAlone() {
    assertEquals("project-agent " + SUBJECT, WaitHolder.projectAgent(SUBJECT, null));
  }

  @Test
  void ofNamesTheHolderTheRowHas() {
    AgentWait agent = new AgentWait();
    agent.agentId = AGENT;
    assertEquals("agent " + AGENT, WaitHolder.of(agent));

    AgentWait desk = new AgentWait();
    desk.deskProjectId = PROJECT;
    assertEquals("desk " + PROJECT, WaitHolder.of(desk));

    AgentWait projectAgent = new AgentWait();
    projectAgent.projectAgentSubject = SUBJECT;
    projectAgent.projectAgentProjectId = PROJECT;
    projectAgent.workQualifiedId = "qits-2573";
    assertEquals("project-agent " + SUBJECT + " for qits-2573", WaitHolder.of(projectAgent));
  }
}
