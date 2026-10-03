package eu.wohlben.qits.workspaces.dto;

import java.time.Instant;

/**
 * A workspace as a work list sees it (qits-112): which work item it is bound to, and its state.
 * Thin on purpose, like {@link WorkspaceSubjectRefDto}: no mirror refresh, no container listing.
 *
 * @param id the workspace's row id, the key every route addresses it by
 * @param workId the work item's entity id in qits-projects, or {@code null} for a workspace no
 *     dispatch bound
 * @param qualifiedId the work item's qualified id as it was at dispatch, e.g. {@code qits-614}, or
 *     {@code null}. It is what the UI links by, but it can be out of date after a project slug
 *     change; {@code workId} cannot
 * @param repositoryId the repository the workspace is in
 * @param workspaceId the workspace's display label, derived from its branch
 * @param branch the branch the workspace owns
 * @param status {@code ACTIVE}, {@code INTEGRATED} or {@code ABANDONED}. A {@code String} so that a
 *     status added later reads as an unknown value rather than failing to parse
 * @param runtimeStatus the container state last recorded on the row ({@code RUNNING}, {@code
 *     STOPPED}, {@code PROVISIONING}, {@code FAILED}). Not checked live
 * @param createdAt when the workspace was made; {@code null} for rows older than that column
 * @param resolvedAt when it was integrated or abandoned; {@code null} while ACTIVE
 */
public record WorkItemWorkspaceDto(
    Long id,
    String workId,
    String qualifiedId,
    String repositoryId,
    String workspaceId,
    String branch,
    String status,
    String runtimeStatus,
    Instant createdAt,
    Instant resolvedAt) {}
