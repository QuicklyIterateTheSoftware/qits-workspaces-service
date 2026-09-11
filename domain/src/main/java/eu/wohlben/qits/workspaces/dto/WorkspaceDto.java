package eu.wohlben.qits.workspaces.dto;

import eu.wohlben.qits.workspaces.control.AgentActivityState;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import java.time.Instant;

/**
 * @param id the workspace's identifier — the generated surrogate key, which is what routes, the
 *     daemon control socket and the ports address. Stable and unique on its own; needs no
 *     repository beside it
 * @param workspaceId the branch-derived label. A display name and a path/container-name segment,
 *     not an identifier: unique only per repository, and reusable once the workspace resolves
 * @param repositoryMainBranch the default branch of the repository this workspace belongs to. Not
 *     decoration and not a duplicate of anything here: it is what makes {@code parent} readable.
 *     Only a release may write the default branch, so a client deciding between "Integrate" and
 *     "Release" is asking whether {@code parent} equals this — and without it that costs a second
 *     request to another service for one string. Free here: the listing already resolves the
 *     repository to check it exists, and this is a field of the answer it already has. {@code null}
 *     only on the create response, which is a thin view (see {@code WorkspaceMapper})
 * @param ahead commits the workspace's branch has that its parent does not (commits in front)
 * @param behind commits the parent has that the workspace's branch does not (commits it trails by)
 * @param conflictsWithParent whether merging the parent into this branch would hit merge conflicts.
 *     Only computed (and ever {@code true}) when the branch has diverged from its parent (both
 *     ahead and behind); {@code false} for branches that can be fast-forwarded or merged cleanly.
 *     Drives the "cannot integrate cleanly" warning in the branch tree.
 * @param status the workspace's resolution state (ACTIVE, or INTEGRATED/ABANDONED for history)
 * @param runtimeStatus the container's runtime state (RUNNING/STOPPED/PROVISIONING/FAILED),
 *     independent of {@code status}: the branch is the source of truth, the container is a
 *     recreatable cache of it
 * @param runtimeError when {@code runtimeStatus} is FAILED, why the last re-provision failed
 * @param clean whether the workspace's in-container working tree is clean ({@code true}) or has
 *     uncommitted changes ({@code false}), as last reported by {@code workspace-daemon} over its
 *     socket; {@code null} when unknown — the daemon only reports while the container is RUNNING,
 *     so a STOPPED workspace (or one whose daemon hasn't reported yet) carries no clean/dirty badge
 * @param agentActivity the live coding-agent activity rollup for this workspace
 *     (BUSY/WAITING/IDLE/ENDED, in that precedence), as last reported by {@code workspace-daemon}
 *     hearing the agent's lifecycle hooks; {@code null} when no tracked agent is running (or the
 *     container isn't RUNNING / none has reported yet) — same RUNNING-only, self-healing lifecycle
 *     as {@code clean}. {@code ENDED} means every tracked session in this workspace has finished
 *     <em>recently</em>: the host holds it for {@code qits.workspace.agent-activity.ended-ttl-ms}
 *     and then lets it fall back to {@code null}. It is a real value and not a transient — the
 *     agent-activity bar sorts on it, because a workspace whose agent just stopped is the one
 *     waiting for the next prompt
 * @param preamble markdown: the reason/goal authored at creation. A person's prose, and only that —
 *     a dispatched workspace names its subject in {@code ticketId}/{@code epicId} and leaves this
 *     empty, so nothing here is a second copy of a ticket that has moved on since
 * @param ticketId the qits-projects ticket this workspace was dispatched for, or {@code null}
 * @param epicId the qits-projects epic this workspace was dispatched for, or {@code null}. Both are
 *     carried and neither is resolved: the client turns one into a link, because composing that link
 *     needs the platform's public origin, which the browser is told and this service is not
 * @param result markdown: the outcome authored at resolution
 * @param createdAt when the workspace row was created. It is today's approximation of "last
 *     touched" — the overview sorts on it — and stays that until the row carries a real touch
 *     timestamp; {@code null} for rows that predate the column
 * @param resolvedAt when the workspace was resolved (null while ACTIVE)
 * @param daemonConnectedAt when the workspace's in-container {@code workspace-daemon} registered
 *     its control socket — the workspace's "connected since" (docs/epics/qits-workspace-registry/).
 *     {@code null} when unknown: like {@code clean}, the registry only knows it while the container
 *     is RUNNING, and it resets on each daemon (re)connect (it is not a durable first-registered
 *     time)
 * @param daemonVersion the release version of the daemon binary the running container is on (Maven
 *     {@code project.version}); {@code null} when unknown (no live daemon, or an older daemon image
 *     that announced none)
 * @param daemonBuildTime when that daemon binary was built — distinguishes floating {@code
 *     -SNAPSHOT} builds sharing one version; {@code null} when unknown
 * @param daemonOutdated whether this workspace's daemon build is strictly older than the newest one
 *     connected anywhere in the workspace registry — {@code true} means a newer workspace-daemon is
 *     available, so the UI shows a warning and offers a recreate. {@code null} when not comparable
 *     (no live daemon, no reported build time on either side) — no warning; only ever {@code true}
 *     or {@code null} in practice, since the newest and any tied daemons are simply not outdated
 *     (docs/epics/qits-workspace-registry/)
 * @param admin whether this workspace runs in admin mode — its container holds the host's docker
 *     socket, which makes it root-equivalent on the host. Decided in the request that created the
 *     workspace and never afterwards, so it is a fact about the workspace rather than about its
 *     current container. It is on the read model because a client that cannot see which workspaces
 *     are privileged cannot say so, and "which ones have the socket" is the question this whole
 *     posture exists to keep answerable
 */
public record WorkspaceDto(
    Long id,
    String workspaceId,
    String parent,
    String branch,
    String repositoryMainBranch,
    Integer ahead,
    Integer behind,
    boolean conflictsWithParent,
    WorkspaceStatus status,
    WorkspaceRuntimeStatus runtimeStatus,
    String runtimeError,
    Boolean clean,
    AgentActivityState agentActivity,
    String preamble,
    String ticketId,
    String epicId,
    String result,
    Instant createdAt,
    Instant resolvedAt,
    Instant daemonConnectedAt,
    String daemonVersion,
    Instant daemonBuildTime,
    Boolean daemonOutdated,
    boolean admin) {}
