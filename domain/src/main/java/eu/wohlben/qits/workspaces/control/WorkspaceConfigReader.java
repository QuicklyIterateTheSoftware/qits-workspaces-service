package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * Reads a workspace's {@code .qits-config.yml} from its <b>in-container checkout</b> — the daemon
 * parses the file on its own branch and reports it over the control socket, so the config is the
 * workspace's branch's config, not the bare origin's {@code mainBranch}
 * (docs/epics/qits-workspace-daemon/ Part 2). Framework-free so it lives in {@code domain} (no
 * websockets type); the real implementation is the backend {@code WorkspaceDaemonRegistry} (service
 * module), reached over the socket, and {@code domain}/{@code service} read paths consume it as an
 * {@code Instance<>} that is simply empty in apps without the backend (cli, tests with no daemon).
 *
 * <p>Distinct from {@link WorkspaceDaemonProvisioner} (which drives provisioning): this is an
 * on-demand read. It replaces the host-side, {@code mainBranch}-only DB config store as the source
 * of a workspace's actions/bootstrap list — the UI/read-path rewire lands with the config
 * single-source-of-truth part.
 */
public interface WorkspaceConfigReader {

  /**
   * The parsed config for {@code workspaceId}'s in-container checkout, or {@link Optional#empty()}
   * when no daemon is live (no open control socket) to read it. A present result whose {@link
   * WorkspaceConfigView#warning()} is non-null means the file was there but could not be
   * read/parsed (the config is empty) — still a successful read, just degraded.
   */
  Optional<WorkspaceConfigView> readConfig(Long workspaceId);
}
