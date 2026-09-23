package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;

/**
 * The one lookup the editor proxy performs: <b>the</b> editor workspace, or nothing.
 *
 * <p>The sibling of {@link DaemonProxyTargets}, deliberately the same shape and with the same
 * posture. <b>Nothing about the request selects an address.</b> The forwarded host says only that
 * this is the editor's surface ({@link EditorHost}); the row comes out of this service's own state;
 * and what that row's container is called and how to reach it are derived from the row exactly as
 * they are for the daemon proxy — a component of a request that could name an origin would be an
 * SSRF primitive aimed at everything on the platform network.
 *
 * <p><b>No editor row is nothing, and nothing is a 404 with no connection made.</b> That is the
 * honest answer before anybody has opened the editor for the first time: the row is written by the
 * door ({@code EditorService.ensure}), not by a request arriving at an origin, so a browser that
 * navigates straight to the editor host on a fresh platform is told there is nothing there rather
 * than having a container started for it by a GET.
 *
 * <h2>What this class used to be</h2>
 *
 * <p>Almost all of it was about finding <em>which project's</em> editor a request was for, and none
 * of that survives one editor for the platform. It parsed a project label, derived the name
 * qits-projects gives a wrapper repository ({@code <slug>-<slug>}), scanned every repository
 * somebody had opened a main workspace for asking the registry about each one, remembered the hits
 * for good because a project's wrapper cannot change, and remembered the misses against the
 * candidate set they were computed over with {@code qits.editor.label-miss-ttl-ms} underneath as a
 * backstop. All of it existed to turn a name into a repository, and it is gone with the name: the
 * resolution is now one indexed local query ({@code WorkspaceRepository.findActiveEditor}), which is
 * cheaper than the cheapest cached path that machinery had and cannot go stale.
 *
 * <p>The row is still <b>re-read on every call</b>, for the reason the old row half was: a workspace
 * can be discarded and made again, and a remembered row id would point the proxy at nothing.
 */
@ApplicationScoped
public class EditorProxyTargets {

  @Inject WorkspaceRepository workspaces;

  /**
   * The workspace the editor origin addresses.
   *
   * @param workspaceRowId the row — the id every route, the ports and the keepalive use
   * @param repositoryId the row's repository id, which for the editor is {@link
   *     EditorWorkspace#REPOSITORY_ID} — carried because the container name is composed from it and
   *     {@code workspaceId} together, the same arithmetic every other workspace's name takes
   * @param workspaceId the label the container name is built from
   */
  public record EditorTarget(Long workspaceRowId, String repositoryId, String workspaceId) {}

  /**
   * Resolve an editor origin to the editor's workspace, or empty.
   *
   * <p>{@code @Transactional} because the row read needs a session and the caller is a raw Vert.x
   * route with none — which is also why the route runs this on a worker thread, exactly as {@code
   * ServiceProxyRoute} runs its supervisor lookup off the event loop.
   *
   * @param forwardedHost the raw {@code X-Forwarded-Host}; the first entry is the one that counts
   */
  @Transactional
  public Optional<EditorTarget> resolve(String forwardedHost) {
    if (!EditorHost.isEditorHost(forwardedHost)) {
      return Optional.empty();
    }
    return editor();
  }

  /**
   * The same resolution with the host test already made — what a route that has recognised the
   * origin itself hands over, so the header is read once per request rather than twice.
   */
  @Transactional
  public Optional<EditorTarget> editor() {
    return workspaces
        .findActiveEditor()
        .map(
            workspace ->
                new EditorTarget(workspace.id, workspace.repositoryId, workspace.workspaceId));
  }
}
