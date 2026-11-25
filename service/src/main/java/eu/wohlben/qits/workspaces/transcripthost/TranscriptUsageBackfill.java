package eu.wohlben.qits.workspaces.transcripthost;

import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The boot one-shot that counts the usage already in the transcript store (qits-2675, epic
 * qits-2521): the sessions shipped before the ingest counted usage, read through the same {@link
 * TranscriptUsageRecorder} and announced as the same {@code AgentUsageRecorded}.
 *
 * <p><b>Which agents.</b> Those whose {@code agent_worktree.created_at} is at or after {@code
 * qits.workspace.usage.backfill-since}: every session held for each, main file and sidechains.
 *
 * <p><b>Idempotent, and at every boot.</b> A message is claimed in {@code
 * transcript_usage_message} before it is counted, so a second run — or a run over sessions the
 * ingest already counted — publishes nothing. One session that cannot be read is a WARN and the
 * pass moves on; it never fails a startup.
 *
 * <p><b>Off the boot thread.</b> Reading every covered transcript can take a while, so the pass runs
 * on a daemon thread of its own and readiness never waits for it. Off in {@code %test} ({@code
 * qits.workspace.usage.backfill.enabled}); a test calls {@link #run} itself.
 */
@ApplicationScoped
public class TranscriptUsageBackfill {

  private static final Logger LOG = Logger.getLogger(TranscriptUsageBackfill.class);

  @ConfigProperty(name = "qits.workspace.usage.backfill.enabled", defaultValue = "true")
  boolean enabled;

  @ConfigProperty(name = "qits.workspace.usage.backfill-since", defaultValue = "2026-11-25T00:00:00Z")
  Instant since;

  @Inject AgentWorktreeRepository agents;

  @Inject ShippedClaudeTranscripts transcripts;

  @Inject TranscriptUsageRecorder recorder;

  void onStart(@Observes StartupEvent start) {
    if (!enabled) {
      return;
    }
    Thread thread = new Thread(this::runLogged, "transcript-usage-backfill");
    thread.setDaemon(true);
    thread.start();
  }

  private void runLogged() {
    try {
      run(since);
    } catch (RuntimeException e) {
      LOG.warnf(e, "The transcript usage backfill stopped; the next boot runs it again");
    }
  }

  /**
   * One pass over the agents created at or after {@code from}.
   *
   * @return the {@code AgentUsageRecorded} events it announced
   */
  public int run(Instant from) {
    List<AgentWorktree> covered =
        QuarkusTransaction.requiringNew()
            .call(() -> agents.list("createdAt >= ?1 order by createdAt", from));
    TranscriptStore store = transcripts.store();
    int published = 0;
    int sessions = 0;
    for (AgentWorktree agent : covered) {
      List<String> held;
      try {
        held = store.sessions(agent.id);
      } catch (IOException e) {
        LOG.warnf("Could not list the transcripts of agent %s for the usage backfill: %s", agent.id, e);
        continue;
      }
      for (String session : held) {
        try {
          published += recorder.recordSession(agent, session);
          sessions++;
        } catch (IOException | RuntimeException e) {
          LOG.warnf(
              "Could not count the usage of agent %s session %s for the backfill: %s",
              agent.id, session, e);
        }
      }
    }
    LOG.infof(
        "Transcript usage backfill: %d AgentUsageRecorded published over %d session(s) of %d"
            + " agent(s) created since %s",
        published, sessions, covered.size(), from);
    return published;
  }
}
