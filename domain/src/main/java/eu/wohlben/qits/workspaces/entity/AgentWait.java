package eu.wohlben.qits.workspaces.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A declared wait (qits-1153): the agent waits for an event that matches {@link #selection}, and
 * gets a turn when one arrives or {@link #expiresAt} passes. See {@code V23}.
 *
 * <p><b>In, a {@link CausedRow}.</b> It is created on the request thread of the agent's own call
 * ({@code POST /agents/{id}/waits}), so the stamp records why the wait exists. Closing it does not
 * move the stamp; the event that matched is {@link #matchedEventId}.
 */
@Entity
@Table(name = "agent_wait")
@EntityListeners(CausationStamp.class)
public class AgentWait extends PanacheEntityBase implements CausedRow {

  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }

  @Id public UUID id;

  @Column(name = "agent_id", nullable = false)
  public UUID agentId;

  /** What the agent calls it; the selection's one-line description when it named none. */
  @Column(nullable = false, columnDefinition = "text")
  public String label;

  /** qits-eventstream's {@code EventSelection}, in its JSON form. */
  @Column(nullable = false, columnDefinition = "text")
  public String selection;

  /** The selection's event name: what the listener narrows by. */
  @Column(name = "event_name", nullable = false, length = 128)
  public String eventName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public AgentWaitState state = AgentWaitState.OPEN;

  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  /** When it times out; null = never. */
  @Column(name = "expires_at")
  public Instant expiresAt;

  /** The id of the event that matched it. */
  @Column(name = "matched_event_id", columnDefinition = "text")
  public String matchedEventId;

  @Column(name = "closed_at")
  public Instant closedAt;
}
