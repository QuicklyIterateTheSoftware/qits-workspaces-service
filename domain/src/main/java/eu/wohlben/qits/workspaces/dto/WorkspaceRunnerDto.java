package eu.wohlben.qits.workspaces.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A workspace runner as the runners page reads it. Built by {@code WorkspaceRunnerMapper}, and only
 * there, so a field added later (the connection and slot counts of qits-848, the login command of
 * qits-859) is one component here and one argument there.
 *
 * @param id the runner's id: what its install line, register door and socket name
 * @param name unique, {@code [a-z][a-z0-9-]{0,63}}
 * @param description the operator's words, or null
 * @param slots how many workspace containers it may run at once; 0 is a drained runner
 * @param version the runner binary's version, as it last reported it; null until it has
 * @param arch the node's architecture, as the runner last reported it; null until it has
 * @param dotClaudeVolume the node-local agent home volume the runner reported; null until it has
 * @param login the node's agent login state, as the runner last probed it; null until it has
 * @param registered whether the register door has answered this runner
 * @param registeredAt when it registered, or null
 * @param quarantined whether it is out of service (it takes no workspace)
 * @param quarantinedAt since when, or null
 * @param quarantineReason why, or null
 * @param eligible registered, with slots, and not quarantined: whether it may take a workspace at
 *     all. Whether it is connected is not part of this
 * @param lastSeenAt when the runner was last heard from, or null
 * @param lastHealthCheckAt when its newest health check settled, or null
 * @param lastHealthCheckOk whether that check passed, or null
 * @param createdAt when the runner was declared
 */
public record WorkspaceRunnerDto(
    UUID id,
    String name,
    String description,
    int slots,
    String version,
    String arch,
    String dotClaudeVolume,
    Login login,
    boolean registered,
    Instant registeredAt,
    boolean quarantined,
    Instant quarantinedAt,
    String quarantineReason,
    boolean eligible,
    Instant lastSeenAt,
    Instant lastHealthCheckAt,
    Boolean lastHealthCheckOk,
    Instant createdAt) {

  /**
   * The node's agent login state, last known: kept while the runner is offline.
   *
   * @param claude {@code PRESENT}, {@code ABSENT} or {@code UNKNOWN}, as the runner reported it
   * @param kimi the same, for Kimi
   * @param checkedAt when the runner probed, as it said; null when it did not say
   */
  public record Login(String claude, String kimi, Instant checkedAt) {}
}
