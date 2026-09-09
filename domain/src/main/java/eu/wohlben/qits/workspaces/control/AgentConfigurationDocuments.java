package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * The agent-configuration document a workspace's current container was born with, by row id — what
 * {@link WorkspaceContainerFactory} reads when it composes a container's environment.
 *
 * <p><b>Why the factory looks this up instead of being handed it.</b> Exactly the reason {@link
 * WorkspaceCredentials} gives, and it bites harder here. The orchestrator has no start verb: a
 * stopped container is started by presenting its spec <em>again</em>, under {@code
 * Recreate.ifChanged}, so a spec whose environment differs from the running container's is a spec
 * change and the container is <b>replaced</b>. The document rides the environment, so it has to be
 * derivable from the workspace at <em>every</em> ensure and it has to be the same bytes every time —
 * which is why it is fetched once, at provision, written to the row, and read back from there
 * afterwards. Fetching it here instead would replace a container every time the store was edited,
 * and — since the document carries its own {@code generatedAt} — every time at all.
 *
 * <p>An interface, injected as {@code Instance<T>}, because {@link WorkspaceContainerFactory} is
 * built by hand in the unit tests that assert what a container is made of and those tests have no
 * database. <b>Absent means no document environment is injected</b>, which is a container running on
 * the harness library's shipped defaults — the behaviour every workspace container had before this
 * existed.
 */
@FunctionalInterface
public interface AgentConfigurationDocuments {

  /**
   * The document this workspace's current container was created with, or empty when it holds none —
   * a container created before this shipped, or one whose fetch failed and which is therefore
   * running on the shipped defaults (see {@code Workspace.agentConfigurationError}).
   */
  Optional<String> forWorkspace(Long rowId);
}
