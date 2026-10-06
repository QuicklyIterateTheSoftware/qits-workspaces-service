package eu.wohlben.qits.workspaces.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One workspace runner an operator declared: a machine that registers with this service, takes
 * queued RUNNER workspaces and runs their containers on its own node. {@code
 * V12__workspace_runners.sql} gives the reasons for every column; what follows is what reading a
 * row means. Shaped on qits-ci's {@code CiRunner}.
 *
 * <p><b>Its registration state is which of two pairs is set.</b> {@link #registrationTokenId} and
 * {@link #registrationTokenSubject} name the one-use token qits-idp commissioned at create (or at
 * the last rotation): the id so it can be deleted, the subject because the register door compares
 * the caller's {@code sub} to it. The token's value is on no column. {@link #clientId} and {@link
 * #registeredAt} are set once, by the register door.
 *
 * <p><b>{@link #capabilities} is the runner's own word about itself</b>, merged key by key on every
 * report and read through {@code WorkspaceRunnerCapabilities}. A {@code String} attribute over a
 * {@code jsonb} column, as {@code CiRunner} does it, so nothing here binds another party's payload.
 *
 * <p>Connection state is not on the row: it lives in memory, in the socket registry.
 *
 * <p>A {@link CausedRow}: a runner is created by an operator's request, and the REST filter's
 * restored scope is standing when it is, so the stamp fills the column on its own.
 */
@Entity
@Table(name = "workspace_runner")
@EntityListeners(CausationStamp.class)
public class WorkspaceRunner extends PanacheEntityBase implements CausedRow {

  @Id public UUID id;

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

  /** Unique, {@code [a-z][a-z0-9-]{0,63}}; see {@code WorkspaceRunners.NAME}. */
  @Column(nullable = false, length = 64)
  public String name;

  @Column(length = 1024)
  public String description;

  /** How many workspace containers this runner may run at once; zero is a drained runner. */
  @Column(nullable = false)
  public int slots;

  /**
   * The {@code --memory} this runner's workspace containers get, as a docker size string ({@code
   * 12g}, {@code 12288m}); see {@code WorkspaceRunners.MEMORY_LIMIT}. Null is the platform's own
   * {@code qits.workspace.memory-limit}. Read when a runner takes a workspace, so a change reaches
   * the next launch. See {@code V14__runner_workspace_memory_limits.sql}.
   */
  @Column(name = "workspace_memory_limit", length = 32)
  public String workspaceMemoryLimit;

  /**
   * The {@code --memory-swap} this runner's workspace containers get: docker's total of memory plus
   * swap, the same grammar or {@code -1} for unlimited swap. Null is {@link #workspaceMemoryLimit}
   * when that is set (no swap beyond it) and the platform's {@code qits.workspace.memory-swap-limit}
   * otherwise; {@code RunnerWorkspaceSpecs.limitsFor} is that rule.
   */
  @Column(name = "workspace_memory_swap_limit", length = 32)
  public String workspaceMemorySwapLimit;

  /** What the runner last said about itself, as a JSON object's text; null until it registers. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  public String capabilities;

  @Column(name = "registration_token_id", length = 255)
  public String registrationTokenId;

  @Column(name = "registration_token_subject", length = 255)
  public String registrationTokenSubject;

  /** The commissioned client the register door answered with, or null while unregistered. */
  @Column(name = "client_id", length = 255)
  public String clientId;

  /**
   * When this runner was taken out of service, or null while it is in it. Set and cleared together
   * with {@link #quarantineReason}. {@link #slots} is never touched by either, so a greenlit runner
   * gets back exactly what its operator configured.
   */
  @Column(name = "quarantined_at")
  public Instant quarantinedAt;

  @Column(name = "quarantine_reason", columnDefinition = "text")
  public String quarantineReason;

  /** When the newest health check settled; null until one has. */
  @Column(name = "last_health_check_at")
  public Instant lastHealthCheckAt;

  /** Whether the newest health check passed; null until one has settled. */
  @Column(name = "last_health_check_ok")
  public Boolean lastHealthCheckOk;

  @Column(name = "registered_at")
  public Instant registeredAt;

  /** Host-stamped each time the runner is heard from; null until it first is. */
  @Column(name = "last_seen_at")
  public Instant lastSeenAt;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /** Whether the register door has answered this runner. */
  public boolean registered() {
    return clientId != null;
  }

  /** Whether this runner is out of service: it takes no workspace but its own health check. */
  public boolean quarantined() {
    return quarantinedAt != null;
  }

  /**
   * Whether this runner may take a workspace at all: registered, with slots, and not quarantined.
   * Whether it is connected right now is the socket registry's question, not the row's.
   */
  public boolean eligible() {
    return registered() && slots > 0 && !quarantined();
  }
}
