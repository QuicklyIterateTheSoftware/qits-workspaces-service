package eu.wohlben.qits.workspaces.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A workspace row a retired workspaces runner held when the runner tables were dropped ({@code
 * V30}, epic qits-1481): which runner, and what was known about its docker engine. The row's first
 * lease adopts the runner's container and volume ({@code
 * qits-workspaces-runner-ws-<rowId>-<oldRunnerId8>}) and is pinned to the target on the same
 * engine; the entry is deleted once that lease is RUNNING, or when the row's container is deleted.
 *
 * <p>{@code @Uncaused} by decision: every row is written by the migration, never by a request, and
 * the only other write is its deletion.
 */
@Entity
@Table(name = "legacy_placement")
@Uncaused
public class LegacyPlacement extends PanacheEntityBase {

  /** The workspace row, {@code Workspace.id}. */
  @Id
  @Column(name = "row_id")
  public Long rowId;

  /** The retired runner that held it; its first eight characters end the old names. */
  @Column(name = "old_runner_id", nullable = false)
  public UUID oldRunnerId;

  /**
   * The runner's docker engine id, from its {@code capabilities.engineId}. Null for every row today:
   * qits-workspaces-runner-daemon never reported one (its capabilities held {@code version}, {@code
   * docker}, {@code arch}, {@code os}, {@code dotClaudeVolume}, {@code login} and {@code health}).
   */
  @Column(name = "engine_id", columnDefinition = "text")
  public String engineId;

  /** The runner's host name, from its {@code capabilities.hostname}; null for the same reason. */
  @Column(name = "hostname", columnDefinition = "text")
  public String hostname;

  /** The runner's own name, the last way to find the target on the same node. */
  @Column(name = "runner_name", columnDefinition = "text")
  public String runnerName;

  /** {@code qits-workspaces-runner-ws-<rowId>-<oldRunnerId8>}: the old container and volume alike. */
  public String legacyName() {
    return legacyName(rowId, oldRunnerId);
  }

  /** {@link #legacyName()} for any row and runner. */
  public static String legacyName(long rowId, UUID oldRunnerId) {
    return "qits-workspaces-runner-ws-" + rowId + "-" + oldRunnerId.toString().substring(0, 8);
  }
}
