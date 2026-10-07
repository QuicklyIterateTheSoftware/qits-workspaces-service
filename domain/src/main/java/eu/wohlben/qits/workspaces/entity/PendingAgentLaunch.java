package eu.wohlben.qits.workspaces.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An agent launch (or delivery) held for a RUNNER workspace QUEUED for a slot, from the press until
 * its daemon took it (qits-1064, {@code V17}). One row per workspace — the primary key <em>is</em>
 * the {@link Workspace}'s {@code id}, and that is the one-launch rule.
 *
 * <p>Owned by {@code DispatchService}, through {@code HeldAgentLaunches}; its class javadoc says why
 * the launch is stored and how the claim columns are used.
 *
 * <p>{@code @Uncaused} by decision, for {@link WorkspacePromptDraft}'s reasons: the row is only ever
 * inserted by a native {@code insert … on conflict do nothing}, which no {@code @PrePersist} sees,
 * and it is rewritten by its claim, so a stamped cause would name the press and then misname the
 * delivery.
 */
@Entity
@Table(name = "pending_agent_launch")
@Uncaused
public class PendingAgentLaunch extends PanacheEntityBase {

  /** The workspace's {@code id} — the PK and the FK. */
  @Id
  @Column(name = "workspace_id")
  public Long workspaceId;

  /** The instruction of a launch, or the text of a delivery. */
  @Column(name = "text", columnDefinition = "text", nullable = false)
  public String text;

  /** Whether it is a delivery's wait rather than a dispatch's. */
  @Column(name = "delivery", nullable = false)
  public boolean delivery;

  /** A delivery's request for a {@code /compact} ahead of it; false for a launch. */
  @Column(name = "compact_first", nullable = false)
  public boolean compactFirst;

  /** When it was held. */
  @Column(name = "parked_at", nullable = false)
  public Instant parkedAt;

  /** The process delivering it; null while it waits for a take. */
  @Column(name = "claimed_by")
  public String claimedBy;

  /** When {@link #claimedBy} took it; null with it. */
  @Column(name = "claimed_at")
  public Instant claimedAt;
}
