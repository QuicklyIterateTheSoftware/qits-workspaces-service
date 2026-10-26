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
 * <p><b>Two holders</b> (qits-1618, {@code V32}): an agent ({@link #agentId}) or a project's front
 * desk ({@link #deskProjectId}), exactly one of them. A desk wait's close is announced as {@code
 * DeskWaitClosed} rather than written as a turn; {@link #closedEventId}, {@link #closedTurn} and
 * {@link #announcedAt} carry that announcement until the bus has taken it.
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

  /** The agent that waits; null for a desk wait. */
  @Column(name = "agent_id")
  public UUID agentId;

  /** The project whose front desk waits (qits-1618); null for an agent's wait. */
  @Column(name = "desk_project_id")
  public UUID deskProjectId;

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

  /** A desk wait's {@code DeskWaitClosed} id, set when it MATCHED or TIMED_OUT; re-published as is. */
  @Column(name = "closed_event_id")
  public UUID closedEventId;

  /** The turn that event carries, rendered when the wait closed. */
  @Column(name = "closed_turn", columnDefinition = "text")
  public String closedTurn;

  /** When the bus took the {@code DeskWaitClosed}; null while it is still to be published. */
  @Column(name = "announced_at")
  public Instant announcedAt;

  /** Whether a project's desk holds this wait rather than an agent. */
  public boolean isDeskWait() {
    return deskProjectId != null;
  }
}
