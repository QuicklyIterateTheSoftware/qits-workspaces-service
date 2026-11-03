package eu.wohlben.qits.workspaces.transcripthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.ArchivedSessionDto;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@link AgentTranscripts} answers (qits-368): an agent's sessions are the shipped store's and
 * nothing else's, and an old-model workspace answers none. Plain JUnit over the bean with a real
 * store under a temp directory; the slot's agent lookup is the history door test's to prove.
 */
class AgentTranscriptsTest {

  private static final String SESSION = "00000000-0000-4000-8000-0000000000e1";

  private static final String TURN =
      "{\"type\":\"user\",\"timestamp\":\"2026-10-30T10:00:00Z\",\"message\":{\"content\":\"hi\"}}";

  @TempDir Path storeRoot;

  private AgentTranscripts transcripts;

  @BeforeEach
  void aStoreUnderATempDirectory() {
    ShippedClaudeTranscripts shipped = new ShippedClaudeTranscripts();
    shipped.storeRoot = storeRoot.toString();
    transcripts = new AgentTranscripts();
    transcripts.shipped = shipped;
  }

  private static AgentWorktree agent() {
    AgentWorktree agent = new AgentWorktree();
    agent.id = UUID.randomUUID();
    agent.repositoryId = "repo-a";
    agent.wrapperBranch = "ticket/w-1";
    return agent;
  }

  @Test
  void anAgentsSessionsAreWhatItsDaemonShipped() throws Exception {
    AgentWorktree agent = agent();
    transcripts.shipped.store().append(agent.id, SESSION, "main", 0, TURN + "\n");

    List<ArchivedSessionDto> sessions = transcripts.sessionsFor(agent);

    assertEquals(List.of(SESSION), sessions.stream().map(ArchivedSessionDto::sessionId).toList());
    assertEquals(List.of(TURN), transcripts.transcriptOf(agent, SESSION));
  }

  /** Another agent's shipped session is not this one's: listed nowhere, and a 404 when asked for. */
  @Test
  void anotherAgentsSessionIsNotFound() throws Exception {
    AgentWorktree mine = agent();
    AgentWorktree theirs = agent();
    transcripts.shipped.store().append(theirs.id, SESSION, "main", 0, TURN + "\n");

    assertTrue(transcripts.sessionsFor(mine).isEmpty());
    assertThrows(NotFoundException.class, () -> transcripts.transcriptOf(mine, SESSION));
  }

  /**
   * An old-model workspace (a branch of its own, no agents) never had a daemon that shipped, so it
   * has no sessions — an empty list, never an error — and any id asked of it is the unknown-session
   * 404.
   */
  @Test
  void anOldModelWorkspaceAnswersNoSessionsAndNotFound() {
    Workspace workspace = new Workspace();
    workspace.id = 42L;
    workspace.branch = "ticket/an-old-model-workspace";

    assertTrue(transcripts.sessionsFor(workspace).isEmpty());
    NotFoundException refused =
        assertThrows(NotFoundException.class, () -> transcripts.transcriptOf(workspace, SESSION));
    assertEquals("Agent session not found: " + SESSION, refused.getMessage());
  }
}
