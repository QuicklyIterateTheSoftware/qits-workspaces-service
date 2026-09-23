package eu.wohlben.qits.workspaces.control;

import java.util.List;

/**
 * The wrappers the one shared editor clones, as the single string its daemon is told them in.
 *
 * <p>The editor is one container for the whole platform, so it holds no repository of its own and
 * every project's wrapper is checked out inside it side by side. Which wrappers those are is what
 * this port answers, and the answer travels as {@value #ENV}: {@code <projectId>/<repoName>} entries
 * separated by commas — the same two halves the daemon already composes a clone url from ({@code
 * <gitBase>/<projectId>/<repoName>}), so nothing new is derived at either end and each wrapper lands
 * at {@code /workspace/<repoName>}.
 *
 * <p><b>A port with a persisted implementation beside it</b>, the shape {@link WorkspacePostures} /
 * {@link PersistedWorkspacePostures} already has and for the same two reasons: {@link
 * WorkspaceContainerFactory} composes a spec and must be buildable by hand in a test with no
 * database behind it, and the one implementation reads this service's own tables plus the
 * repository registry, which is not something a spec builder should know about.
 *
 * <p><b>It is a functional interface deliberately</b> — a test writes it as a lambda, exactly as
 * every other port here is written — so nothing may be added to it that is not defaulted.
 *
 * @see PersistedEditorProjects for where the list actually comes from, what it costs, and what a
 *     registry outage does
 */
@FunctionalInterface
public interface EditorProjects {

  /**
   * The environment variable this composes. Spelled once here because the factory writes it and two
   * tests read it back off a spec.
   */
  String ENV = "QITS_WORKSPACE_DAEMON_PROJECTS";

  /** What separates two entries. A comma; the daemon accepts whitespace too. */
  String SEPARATOR = ",";

  /**
   * Every project's wrapper as {@code <projectId>/<repoName>}, <b>sorted and without duplicates</b>.
   *
   * <p>The sort is part of the contract and not an implementation's tidiness: the answer reaches a
   * container's environment, environment is part of the spec, and {@code Recreate.ifChanged} turns a
   * spec that reshuffled between two ensures into a REPLACEMENT of the container somebody is working
   * in — on a door that is polled every two seconds.
   *
   * <p>An empty list is a supported answer and means what it says: a platform with no workspaces in
   * it yet, or a registry that could not be asked.
   */
  List<String> wrappers();

  /** {@link #wrappers} as the one string the environment carries. */
  default String composed() {
    return String.join(SEPARATOR, wrappers());
  }
}
