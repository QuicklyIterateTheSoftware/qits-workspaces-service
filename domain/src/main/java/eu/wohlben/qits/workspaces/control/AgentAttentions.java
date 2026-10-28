package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.events.AgentAttentionChanged;
import eu.wohlben.qits.workspaces.persistence.AgentWaitRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>Stores and announces what an agent needs a person for</b> (epic qits-1681, "Attention"): {@link
 * AgentAttention} decides, this writes the answer onto the agent row ({@code attention_code},
 * {@code attention_reason}, {@code attention_since}, {@code attention_wait_id}) and announces a
 * change as {@link AgentAttentionChanged} once the write committed.
 *
 * <p><b>A change is the code or the wait.</b> A reason whose minutes moved, or a since that moved
 * with it, is written silently; only a new code, a new wait or a cleared attention ({@code code}
 * absent) is announced. An agent that is no longer ACTIVE clears quietly: no event.
 *
 * <p><b>When it is asked</b>: after a wait of the agent opens, changes, closes or is cancelled
 * ({@link AgentWaits}); after an activity flip ({@code AgentService.onActivity}); after an entity
 * mark ({@code markBlocked}, {@code markEntity}); after an {@code @desk} comment ({@link
 * #onDeskMention}); and for every candidate on each scheduler pass ({@link #reevaluateAll}), which
 * is what announces the codes that only time brings. Nothing here throws into a caller.
 */
@ApplicationScoped
public class AgentAttentions {

  private static final Logger LOG = Logger.getLogger(AgentAttentions.class);

  /** The longest reason stored: it is one line. */
  static final int MAX_REASON = 300;

  @Inject AgentWorktreeRepository agents;

  @Inject AgentWaitRepository waits;

  @Inject AgentWaits agentWaits;

  @Inject Instance<LifecycleAnnouncer> lifecycle;

  /** How long an agent waits for input with nothing declared before it is {@code IDLE_NO_WAIT}. */
  @ConfigProperty(name = "qits.workspaces.attention.idle-after", defaultValue = "PT30M")
  Duration idleAfter;

  /** How long an explicit block stands with no {@code @desk} comment before it is {@code BLOCKED_NO_DESK}. */
  @ConfigProperty(name = "qits.workspaces.attention.blocked-after", defaultValue = "PT30M")
  Duration blockedAfter;

  /** How long a release request may stay in one unfinished state before its wait is overdue. */
  @ConfigProperty(name = "qits.workspaces.attention.request-overdue-after", defaultValue = "PT3H")
  Duration requestOverdueAfter;

  AgentAttention.Config config() {
    return new AgentAttention.Config(idleAfter, blockedAfter, requestOverdueAfter);
  }

  /** Evaluate {@code agentId} now, store the answer and announce a change. Never throws. */
  public void reevaluate(UUID agentId) {
    reevaluate(agentId, Instant.now());
  }

  /** {@link #reevaluate(UUID)} for each, in order. Never throws. */
  public void reevaluate(Collection<UUID> agentIds) {
    if (agentIds == null) {
      return;
    }
    for (UUID id : new LinkedHashSet<>(agentIds)) {
      if (id != null) {
        reevaluate(id);
      }
    }
  }

  /**
   * The scheduler pass's share: every ACTIVE agent, and every other still storing an attention, is
   * evaluated from one read of the rows and their OPEN waits; only an agent whose answer differs
   * from what it stores is written (and announced where the code or the wait changed). Never throws.
   *
   * @return how many agents were written
   */
  public int reevaluateAll() {
    return reevaluateAll(Instant.now());
  }

  int reevaluateAll(Instant now) {
    List<UUID> differing;
    try {
      differing =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    List<AgentWorktree> rows = agents.findAttentionCandidates();
                    if (rows.isEmpty()) {
                      return List.<UUID>of();
                    }
                    Map<UUID, List<AgentWait>> open =
                        waits.findOpenByAgents(rows.stream().map(a -> a.id).toList()).stream()
                            .collect(Collectors.groupingBy(w -> w.agentId));
                    List<UUID> out = new ArrayList<>();
                    for (AgentWorktree row : rows) {
                      Optional<AgentAttention.Attention> attention =
                          AgentAttention.evaluate(row, open.getOrDefault(row.id, List.of()), now, config());
                      if (!stored(row, attention)) {
                        out.add(row.id);
                      }
                    }
                    return out;
                  });
    } catch (RuntimeException e) {
      LOG.warnf(e, "agent attention could not be read; the next pass tries again");
      return 0;
    }
    int written = 0;
    for (UUID id : differing) {
      if (reevaluate(id, now)) {
        written++;
      }
    }
    return written;
  }

  /**
   * One agent: evaluated on its locked row, written when the answer differs from what it stores,
   * and announced after the commit when the code or the wait changed and the agent is ACTIVE.
   *
   * @return whether the row was written
   */
  boolean reevaluate(UUID agentId, Instant now) {
    AgentAttentionChanged[] changed = new AgentAttentionChanged[1];
    String[] repository = new String[1];
    boolean written;
    try {
      written =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    AgentWorktree row = agents.findById(agentId);
                    if (row == null) {
                      return false;
                    }
                    Optional<AgentAttention.Attention> attention = evaluate(row, now);
                    if (stored(row, attention)) {
                      return false;
                    }
                    // Only now the lock, and the answer again on what it holds.
                    agents.getEntityManager().refresh(row, LockModeType.PESSIMISTIC_WRITE);
                    attention = evaluate(row, now);
                    if (stored(row, attention)) {
                      return false;
                    }
                    String code = attention.map(a -> a.code().name()).orElse(null);
                    UUID waitId = attention.map(AgentAttention.Attention::waitId).orElse(null);
                    boolean announced =
                        !Objects.equals(code, row.attentionCode)
                            || !Objects.equals(waitId, row.attentionWaitId);
                    row.attentionCode = code;
                    row.attentionReason =
                        attention.map(a -> AgentWaits.bounded(a.reason(), MAX_REASON)).orElse(null);
                    row.attentionSince = attention.map(a -> micros(a.since())).orElse(null);
                    row.attentionWaitId = waitId;
                    if (announced && row.state == AgentState.ACTIVE) {
                      repository[0] = row.repositoryId;
                      changed[0] =
                          new AgentAttentionChanged(
                              row.id.toString(),
                              row.workId,
                              row.workspaceId == null ? null : row.workspaceId.toString(),
                              row.repositoryId,
                              null,
                              code,
                              row.attentionReason,
                              row.attentionSince,
                              waitId == null ? null : waitId.toString(),
                              now);
                      LOG.infof(
                          "Agent %s needs attention: %s",
                          row.id, code == null ? "no longer" : code + " (" + row.attentionReason + ")");
                    }
                    return true;
                  });
    } catch (RuntimeException e) {
      LOG.warnf(e, "agent %s's attention was not evaluated", agentId);
      return false;
    }
    AgentAttentionChanged event = changed[0];
    if (event != null) {
      try {
        LifecycleAnnouncer.announce(
            lifecycle,
            new AgentAttentionChanged(
                event.eventId(),
                event.agentId(),
                event.workId(),
                event.workspaceId(),
                event.repositoryId(),
                agentWaits.projectOf(repository[0]),
                event.code(),
                event.reason(),
                event.since(),
                event.waitId(),
                event.occurredAt()));
      } catch (RuntimeException e) {
        LOG.warnf(e, "AgentAttentionChanged for agent %s not announced", agentId);
      }
    }
    return written;
  }

  /**
   * An {@code @desk} comment on the work item {@code entityId} (the platform's {@code
   * EntityCommented} naming {@code desk}): its ACTIVE agent records when it was asked, then is
   * evaluated. An older comment than the one recorded moves nothing. Throws when the row cannot be
   * written, so a durable listener leaves the event owed.
   *
   * @return the agent that recorded it, or null when the item has no ACTIVE agent
   */
  public UUID onDeskMention(String entityId, Instant commentedAt) {
    if (entityId == null || entityId.isBlank()) {
      return null;
    }
    Instant at = micros(commentedAt == null ? Instant.now() : commentedAt);
    UUID agentId =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    agents
                        .findActiveByWorkId(entityId)
                        .map(
                            agent -> {
                              if (agent.deskAskedAt == null || agent.deskAskedAt.isBefore(at)) {
                                agent.deskAskedAt = at;
                              }
                              return agent.id;
                            })
                        .orElse(null));
    if (agentId != null) {
      LOG.infof("Agent %s's item %s asked the desk at %s", agentId, entityId, at);
      reevaluate(agentId);
    }
    return agentId;
  }

  private Optional<AgentAttention.Attention> evaluate(AgentWorktree row, Instant now) {
    List<AgentWait> open =
        row.state == AgentState.ACTIVE ? waits.findOpenByAgents(List.of(row.id)) : List.of();
    return AgentAttention.evaluate(row, open, now, config());
  }

  /** Whether the row stores exactly {@code attention} (an empty one: nothing). */
  private static boolean stored(AgentWorktree row, Optional<AgentAttention.Attention> attention) {
    if (attention.isEmpty()) {
      return row.attentionCode == null
          && row.attentionReason == null
          && row.attentionSince == null
          && row.attentionWaitId == null;
    }
    AgentAttention.Attention a = attention.get();
    return a.code().name().equals(row.attentionCode)
        && Objects.equals(a.waitId(), row.attentionWaitId)
        && Objects.equals(AgentWaits.bounded(a.reason(), MAX_REASON), row.attentionReason)
        && Objects.equals(micros(a.since()), row.attentionSince);
  }

  private static Instant micros(Instant at) {
    return at == null ? null : at.truncatedTo(ChronoUnit.MICROS);
  }
}
