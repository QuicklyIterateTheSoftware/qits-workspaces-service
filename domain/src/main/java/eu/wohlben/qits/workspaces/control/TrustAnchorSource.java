package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * The platform CA's anchor bundle as it stands right now (epic qits-2524, qits-2634): the PEM a
 * container <b>created now</b> is given as {@value #ENV}, or empty while no bundle is held.
 *
 * <p>A port, because the bundle is polled by qits-trust-core's {@code TrustAnchors}, which only the
 * {@code service} module carries ({@code trust/PlatformTrustAnchorSource}): the domain module and
 * its tests never start a poller. Injected as {@code Instance<>} everywhere, so absent reads exactly
 * like "no bundle yet" — a container is created with the public trust it always had, and a creation
 * never fails for want of the CA.
 *
 * <p><b>Read only when a container is created, never to re-describe one that exists.</b> A change of
 * the bundle alone must not recreate or roll a running workspace; it applies at the next creation.
 * The DIRECT path keeps the value a container was created with on its row ({@link
 * WorkspaceTrustAnchors}); the TARGET path reads it only when it asks for a new lease ({@link
 * RunnerWorkspaceSpecs}).
 */
@FunctionalInterface
public interface TrustAnchorSource {

  /** The environment variable the bundle travels as. */
  String ENV = "QITS_TRUST_ANCHORS";

  /** The bundle a container created now is given, or empty. Never throws. */
  Optional<String> pem();
}
