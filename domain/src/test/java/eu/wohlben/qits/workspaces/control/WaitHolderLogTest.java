package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * The subject lines of a wait that no agent holds name its holder (qits-2668): a project agent's and
 * a desk's, on a lookup ({@link AgentWaits#applyResolution}) and on an event ({@link
 * AgentWaits#recordSubjectState}). They used to print {@code Agent null's wait}.
 */
class WaitHolderLogTest {

  private static final UUID PROJECT = UUID.fromString("8273c743-e0bb-4d93-90d4-391aa10d9151");
  private static final String SUBJECT = "tok-project-agent-qits-developer";

  private static AgentWait runWait() {
    AgentWait wait = new AgentWait();
    wait.id = UUID.randomUUID();
    wait.label = "run r-1 ends";
    wait.subjectKind = WaitSubjects.Kind.CI_RUN.name();
    wait.subjectRef = "r-1";
    return wait;
  }

  private static AgentWait projectAgentWait() {
    AgentWait wait = runWait();
    wait.projectAgentSubject = SUBJECT;
    wait.projectAgentProjectId = PROJECT;
    wait.workId = UUID.randomUUID();
    wait.workQualifiedId = "qits-2573";
    return wait;
  }

  private static AgentWait deskWait() {
    AgentWait wait = runWait();
    wait.deskProjectId = PROJECT;
    return wait;
  }

  private static WaitSubjectResolver.Resolved running() {
    return new WaitSubjectResolver.Resolved("r-1", null, "RUNNING", Instant.now(), null);
  }

  /** What {@link AgentWaits} logged while {@code run} ran, formatted. */
  private static List<String> logged(Runnable run) {
    List<String> logged = new ArrayList<>();
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            logged.add(
                record.getParameters() == null
                    ? record.getMessage()
                    : String.format(record.getMessage(), record.getParameters()));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(AgentWaits.class.getName());
    logger.addHandler(capture);
    try {
      run.run();
    } finally {
      logger.removeHandler(capture);
    }
    return logged;
  }

  private static String only(List<String> lines) {
    assertEquals(1, lines.size(), () -> "one line, got " + lines);
    assertFalse(lines.get(0).contains("null"), lines.get(0));
    return lines.get(0);
  }

  @Test
  void aProjectAgentsLookedUpSubjectNamesTheProjectAgentAndItsWorkItem() {
    AgentWait wait = projectAgentWait();
    String line =
        only(
            logged(
                () ->
                    AgentWaits.applyResolution(
                        wait, WaitSubjects.Kind.CI_RUN, running(), Instant.now())));
    assertEquals(
        "project-agent " + SUBJECT + " for qits-2573: its wait run r-1 ends: its subject r-1 is"
            + " RUNNING (looked up)",
        line);
  }

  @Test
  void aDesksLookedUpSubjectNamesTheDesk() {
    AgentWait wait = deskWait();
    String line =
        only(
            logged(
                () ->
                    AgentWaits.applyResolution(
                        wait, WaitSubjects.Kind.CI_RUN, running(), Instant.now())));
    assertTrue(line.startsWith("desk " + PROJECT + ": its wait run r-1 ends: its subject"), line);
  }

  @Test
  void aProjectAgentsSubjectFromAnEventNamesTheProjectAgent() throws Exception {
    AgentWait wait = projectAgentWait();
    EventSelection selection =
        new EventSelection(
            "BuildStatusChanged",
            List.of(List.of(Condition.exact("runId", "r-1"), Condition.exact("status", "SUCCESS"))));
    var payload = new ObjectMapper().readTree("{\"runId\":\"r-1\",\"status\":\"RUNNING\"}");
    String line =
        only(
            logged(
                () ->
                    AgentWaits.recordSubjectState(
                        wait, selection, "BuildStatusChanged", Instant.now(), payload)));
    assertEquals(
        "project-agent " + SUBJECT + " for qits-2573: its wait run r-1 ends: its subject r-1 is"
            + " RUNNING",
        line);
  }
}
