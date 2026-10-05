package eu.wohlben.qits.workspaces.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A workspace runner as the runners page reads it. Built by {@code WorkspaceRunnerMapper}, and only
 * there. The row's columns and the runner's last report come from the mapper; what is live — the
 * connection, the pin, the counts and the login commands (qits-848, qits-859) — is handed to it by
 * the service, which holds the sockets, as {@code WorkspaceRunnerMapper.Live}.
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
 * @param connected whether the runner holds a socket to this process right now
 * @param connectedSince since when it has, without a break; null while it does not
 * @param pinnedVersion the runner version this process pins and upgrades every runner to; a
 *     connected runner whose {@code version} differs is being updated
 * @param running its workspace containers running or being launched (RUNNING + PROVISIONING)
 * @param owned the ACTIVE workspaces placed on it, whatever their runtime state
 * @param queued the workspaces sticky to it that wait for one of its slots (QUEUED)
 * @param loginCommand the one command that logs the node's agent home in to Claude, run on the
 *     node; null until the runner has reported its agent home volume
 * @param kimiLoginCommand the same for Kimi; null until the volume is known
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
    Instant createdAt,
    boolean connected,
    Instant connectedSince,
    String pinnedVersion,
    int running,
    int owned,
    int queued,
    String loginCommand,
    String kimiLoginCommand) {

  /**
   * The node's agent login state, last known: kept while the runner is offline.
   *
   * @param claude {@code PRESENT}, {@code ABSENT} or {@code UNKNOWN}, as the runner reported it
   * @param kimi the same, for Kimi
   * @param checkedAt when the runner probed, as it said; null when it did not say
   */
  public record Login(String claude, String kimi, Instant checkedAt) {}
}
