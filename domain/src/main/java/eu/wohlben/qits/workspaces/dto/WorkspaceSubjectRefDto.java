package eu.wohlben.qits.workspaces.dto;

/**
 * A live workspace that names a qits-projects ticket or epic as its subject — the answer to "which
 * workspaces are working on this row?", asked by the side that owns the row.
 *
 * <p><b>A shape of its own, deliberately thinner than {@link WorkspaceDto}.</b> That record is the
 * branch tree's row: it carries ahead/behind against the repository mirror, the container's live
 * runtime status, the daemon's build identity and the clean/dirty badge, and computing them costs a
 * mirror refresh and a container listing per repository. The caller here is decorating somebody
 * else's listing — a project's whole tickets panel, in one round trip — and needs none of it. What
 * it needs is enough to draw a link and say which row the link belongs to.
 *
 * @param workspaceRowId the workspace's identifier, the generated key every route addresses it by
 * @param repositoryId the repository the workspace belongs to. Travels beside the id because the
 *     workspaces SPA's route is {@code repositories/{repositoryId}/workspaces/{workspaceRowId}} —
 *     without it the caller would have to ask a second time for a string it is being handed
 * @param workspaceId the branch-derived label, the workspace's display name
 * @param branch the branch the workspace owns
 * @param ticketId the ticket this workspace was dispatched for, or {@code null}
 * @param epicId the epic this workspace was dispatched for, or {@code null}. Exactly one of the two
 *     is set on every row this read can answer with — a row is only here because it matched one
 */
public record WorkspaceSubjectRefDto(
    Long workspaceRowId,
    String repositoryId,
    String workspaceId,
    String branch,
    String ticketId,
    String epicId) {}
