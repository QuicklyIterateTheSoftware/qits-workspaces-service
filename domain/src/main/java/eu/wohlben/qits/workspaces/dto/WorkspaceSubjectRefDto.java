package eu.wohlben.qits.workspaces.dto;

import java.time.Instant;

/**
 * A workspace that names a qits-projects ticket or epic as its subject — the answer to "which
 * workspaces are on this row?", asked by the side that owns the row. Resolved ones included: a
 * workspace that was integrated is still the workspace the ticket's work was done in, and the row
 * says so itself rather than being left out.
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
 * @param status the workspace's own status — {@code ACTIVE}, {@code INTEGRATED} or {@code
 *     ABANDONED}. It rides every row because this read narrows on none of them, so it is what tells
 *     the caller whether it is looking at a way in or at a record of work that has landed. A
 *     {@code String} rather than the enum, because the far side is another service's client and a
 *     status this host adds later must read as an unrecognised value there rather than fail to parse
 * @param resolvedAt when the workspace stopped being live, or {@code null} while it still is. The
 *     pair with {@code status}: a caller showing several resolved workspaces for one row wants the
 *     last one, and asking this service to order them would be deciding a presentation question here
 */
public record WorkspaceSubjectRefDto(
    Long workspaceRowId,
    String repositoryId,
    String workspaceId,
    String branch,
    String ticketId,
    String epicId,
    String status,
    Instant resolvedAt) {}
