package eu.wohlben.qits.workspaces.daemonhost;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * The reverse tunnel's nonce window, shortened, shared by both end-to-end tunnel classes.
 *
 * <p>{@link DaemonStreamRouteTest} and {@link EditorTunnelRouteTest} are one shape over two stream
 * targets and had written the same override twice, in two nested classes. Quarkus compares the
 * profile CLASS, not the map it returns, so that was two augmentations of the same application —
 * and an augmentation abandons a classloader whose metaspace only a full GC reclaims. The surefire
 * fork peaked at 3.41 GiB against qits-ci's hard 4 g step limit, which is bug e6f0bdfa; the root
 * pom's bound is the other half of the fix.
 *
 * <p>Neither copy carried a {@code qits.test.origins-dir} of its own any more: they minted a temp
 * directory each, which is what made the two maps unequal in the first place, and it bought nothing
 * — {@code TestOrigin.create} puts every origin under a UUID, which is why the default profile's
 * thirty-odd classes share {@code target/workspaces-test-data} without colliding.
 *
 * <p>8 s is the number both classes chose and the reasoning is theirs: short enough that the expiry
 * case does not dominate the run, long enough that a loopback dial-back never loses the race.
 */
public class TunnelNonceProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("qits.workspace.daemon-tunnel.nonce-ttl-ms", "8000");
  }
}
