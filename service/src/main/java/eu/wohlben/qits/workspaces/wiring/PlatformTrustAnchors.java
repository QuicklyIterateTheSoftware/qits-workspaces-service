package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.trust.TrustAnchors;
import eu.wohlben.qits.workspaces.control.TrustAnchorSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * The shipped {@link TrustAnchorSource} (qits-2634): qits-trust-core's {@link TrustAnchors}, which
 * polls the platform CA's anchor bundle from qits-certificates with the {@code qits} oidc client's
 * bearer and holds the last good one. Empty until the first bundle is held, and always in {@code
 * %test} ({@code qits.trust.enabled=false}), so no test makes the outbound call.
 */
@ApplicationScoped
public class PlatformTrustAnchors implements TrustAnchorSource {

  @Inject TrustAnchors anchors;

  @Override
  public Optional<String> pem() {
    return anchors.pem();
  }
}
