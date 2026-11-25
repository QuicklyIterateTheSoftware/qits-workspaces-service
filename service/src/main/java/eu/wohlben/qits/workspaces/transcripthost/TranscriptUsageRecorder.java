package eu.wohlben.qits.workspaces.transcripthost;

import eu.wohlben.qits.workspaces.control.LifecycleAnnouncer;
import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.events.AgentUsageRecorded;
import eu.wohlben.qits.workspaces.persistence.TranscriptUsageMessageRepository;
import eu.wohlben.qits.workspaces.transcripthost.TranscriptUsage.Usage;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The usage in an agent's stored transcript, counted once and announced as {@link
 * AgentUsageRecorded} (qits-2675, epic qits-2521).
 *
 * <p><b>Counted once per session, ever.</b> The lines are read by {@link TranscriptUsage}, which
 * keeps a message's first line; each message is then claimed in {@code transcript_usage_message}
 * ({@code ON CONFLICT DO NOTHING}), and only the messages this call inserted are summed. So a chunk
 * the store refused (a re-sent offset) reads nothing, a message whose lines span two chunks counts
 * in the first, and the boot backfill ({@link TranscriptUsageBackfill}) running over what the ingest
 * already counted publishes nothing.
 *
 * <p><b>One event per {@code (sessionId, model)}</b> of one call with at least one newly counted
 * message, announced after the claims committed — {@code AgentService}'s rule for its lifecycle
 * events. The ids are the agent row's; the project is its repository's, as qits-projects names it,
 * remembered once known.
 */
@ApplicationScoped
public class TranscriptUsageRecorder {

  @Inject ShippedClaudeTranscripts transcripts;

  @Inject TranscriptUsageMessageRepository messages;

  @Inject RepositoryLookup repositories;

  @Inject Instance<LifecycleAnnouncer> lifecycle;

  private final Map<String, String> projects = new ConcurrentHashMap<>();

  /**
   * Count the complete lines a chunk newly appended to one of {@code agent}'s files, {@code [from,
   * to)} in bytes.
   *
   * @return the events announced
   */
  public int recordAppended(AgentWorktree agent, String sessionId, String file, long from, long to)
      throws IOException {
    List<String> lines = new ArrayList<>();
    transcripts.store().completeLines(agent.id, sessionId, file, from, to, lines::add);
    return record(agent, sessionId, TranscriptUsage.firstPerMessage(lines));
  }

  /**
   * Count every complete line held for one of {@code agent}'s sessions — its sidechains and its main
   * file — as one call: the backfill's unit.
   *
   * @return the events announced
   */
  public int recordSession(AgentWorktree agent, String sessionId) throws IOException {
    TranscriptStore store = transcripts.store();
    Map<String, Usage> seen = new LinkedHashMap<>();
    for (String file : store.files(agent.id, sessionId)) {
      store.completeLines(
          agent.id,
          sessionId,
          file,
          0,
          Long.MAX_VALUE,
          line -> TranscriptUsage.of(line).ifPresent(u -> seen.putIfAbsent(u.messageId(), u)));
    }
    return record(agent, sessionId, List.copyOf(seen.values()));
  }

  private int record(AgentWorktree agent, String sessionId, List<Usage> usages) {
    if (usages.isEmpty()) {
      return 0;
    }
    Instant now = Instant.now();
    List<Usage> counted =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<Usage> claimed = new ArrayList<>();
                  for (Usage usage : usages) {
                    if (messages.claim(sessionId, usage.messageId(), agent.id, now)) {
                      claimed.add(usage);
                    }
                  }
                  return claimed;
                });
    if (counted.isEmpty()) {
      return 0;
    }
    Map<String, List<Usage>> byModel = new LinkedHashMap<>();
    for (Usage usage : counted) {
      byModel.computeIfAbsent(usage.model(), m -> new ArrayList<>()).add(usage);
    }
    Long workspace = agent.workspaceId != null ? agent.workspaceId : agent.startedWorkspaceId;
    String projectId = projectOf(agent.repositoryId);
    for (Map.Entry<String, List<Usage>> group : byModel.entrySet()) {
      long input = 0;
      long output = 0;
      long cacheCreation = 0;
      long cacheRead = 0;
      Instant newest = null;
      for (Usage usage : group.getValue()) {
        input += usage.inputTokens();
        output += usage.outputTokens();
        cacheCreation += usage.cacheCreationTokens();
        cacheRead += usage.cacheReadTokens();
        if (usage.at() != null && (newest == null || usage.at().isAfter(newest))) {
          newest = usage.at();
        }
      }
      LifecycleAnnouncer.announce(
          lifecycle,
          new AgentUsageRecorded(
              agent.id.toString(),
              agent.workId,
              workspace == null ? null : workspace.toString(),
              projectId,
              sessionId,
              group.getKey(),
              group.getValue().size(),
              input,
              output,
              cacheCreation,
              cacheRead,
              newest != null ? newest : now));
    }
    return byModel.size();
  }

  /** The project of {@code repositoryId}, remembered once known; null when it cannot be named. */
  private String projectOf(String repositoryId) {
    if (repositoryId == null) {
      return null;
    }
    String known = projects.get(repositoryId);
    if (known != null) {
      return known;
    }
    String found;
    try {
      found = repositories.find(repositoryId).map(RepositoryLookup.RepositoryView::projectId).orElse(null);
    } catch (RuntimeException registryDidNotAnswer) {
      return null;
    }
    if (found != null) {
      projects.put(repositoryId, found);
    }
    return found;
  }
}
