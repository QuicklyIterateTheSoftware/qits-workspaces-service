package eu.wohlben.qits.workspaces.persistence;

import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;

/**
 * The assistant messages already counted as usage (qits-2675, {@code V43}): {@code
 * transcript_usage_message}, keyed {@code (session_id, message_id)}. No entity maps it; this one
 * native insert is its only writer.
 */
@ApplicationScoped
public class TranscriptUsageMessageRepository {

  @Inject
  @PersistenceUnit("workspaces")
  EntityManager em;

  /**
   * Record that a session's message has been counted, inside the caller's transaction.
   *
   * @return whether this call recorded it — false when it was counted before, by anyone
   */
  public boolean claim(String sessionId, String messageId, UUID agentId, Instant at) {
    return em.createNativeQuery(
                "insert into transcript_usage_message (session_id, message_id, agent_id, recorded_at)"
                    + " values (?1, ?2, ?3, ?4) on conflict do nothing")
            .setParameter(1, sessionId)
            .setParameter(2, messageId)
            .setParameter(3, agentId)
            .setParameter(4, at)
            .executeUpdate()
        == 1;
  }
}
