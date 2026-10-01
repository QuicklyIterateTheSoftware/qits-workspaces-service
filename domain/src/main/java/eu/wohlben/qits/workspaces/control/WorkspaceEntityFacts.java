package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * The subject facts a workspace's container is told about, by row id — what {@link
 * WorkspaceContainerFactory} writes as {@code QITS_WORKSPACE_DAEMON_ENTITY_TITLE}, {@code _STATUS}
 * and {@code _BLOCKED} (qits-617).
 *
 * <p><b>A lookup rather than an argument, for {@link WorkspacePostures}'s reason</b>: a stopped
 * container is started by presenting its spec again, so whatever the spec says has to be derivable
 * from the row at every ensure. Here that is also the feature: the row is updated by every relayed
 * change, so a container that was stopped while one happened comes back up saying what is true now.
 *
 * <p>An interface, injected as {@code Instance<T>}, because the factory is built by hand in the
 * unit tests that have no database. <b>Absent means none</b> — the keys are omitted, which is what
 * every workspace that was never dispatched looks like.
 */
@FunctionalInterface
public interface WorkspaceEntityFacts {

  /** The row's stored facts, or empty when it carries none at all (or is not an ACTIVE row). */
  Optional<EntityFacts> forWorkspace(Long rowId);
}
