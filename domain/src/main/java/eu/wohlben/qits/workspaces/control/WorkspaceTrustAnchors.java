package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * The anchor bundle a DIRECT workspace's <b>current container</b> was created with (qits-2634), by
 * row id — what {@link WorkspaceContainerFactory} writes as {@value TrustAnchorSource#ENV}.
 *
 * <p><b>Why the factory looks the value up on the row instead of asking for today's bundle.</b> The
 * orchestrator has no start verb: a stopped container is started by presenting its spec again, and
 * the ensure carries {@code Recreate.ifChanged}, so a spec whose environment differs is a spec
 * change and the container is <b>replaced</b> — its writable layer with it. If the factory read the
 * live bundle, a CA rotation alone would replace every stopped workspace on its next resume, and
 * replace a running one whenever an ensure reached it. So the bundle is captured once, when the
 * container is created ({@link ContainerTrustAnchors#captureForNewContainer}), and every later ensure
 * of that container presents the same value byte for byte — the reason {@link WorkspaceCredentials}
 * gives for the token, applied to the anchors. A new bundle reaches the workspace at its next
 * creation (a provision, a recreate).
 *
 * <p>Injected as {@code Instance<>}: the hand-built unit-test factory has no database, and <b>absent
 * means no {@value TrustAnchorSource#ENV} is written</b> — the answer a container created before any
 * bundle was held gets too.
 */
@FunctionalInterface
public interface WorkspaceTrustAnchors {

  /** The PEM bundle this row's current container was created with, or empty. */
  Optional<String> anchorsFor(Long rowId);
}
