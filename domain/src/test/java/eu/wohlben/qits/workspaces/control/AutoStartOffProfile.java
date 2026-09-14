package eu.wohlben.qits.workspaces.control;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * The service auto-start coupling turned off, shared by every class that needs it off.
 *
 * <p>It exists because a {@code @TestProfile} is a QUARKUS RESTART, and a restart is not free: it
 * augments a second application in the same JVM and abandons the first one's classloader, whose
 * metaspace is reclaimable only by a full GC. The bound in the root pom is what provokes that GC;
 * this class is the other half — two classes stating the same one override were two restarts,
 * because Quarkus compares profile IDENTITY and not the map it produces. Measured 2026-09-14 while
 * fixing bug e6f0bdfa: the surefire fork's peak was 3.41 GiB against qits-ci's hard 4 g step limit.
 *
 * <p>The map is the whole difference from the default test application, and each half of that
 * sentence was a correction:
 *
 * <ul>
 *   <li><b>No {@code qits.test.origins-dir}.</b> Both classes used to mint a fresh temp directory
 *       for it, which made their two maps unequal by construction and could never have been shared.
 *       It bought nothing: the shipped test value is {@code target/workspaces-test-data} and {@link
 *       TestOrigin#create} puts every origin under a UUID of its own, so the thirty-odd classes on
 *       the default profile have always shared that directory without colliding.
 *   <li><b>No {@code qits.services.autostop-enabled}.</b> {@code ServiceLifecycleCouplerSettleTest}
 *       used to set it to {@code true}, which is already {@code ServiceLifecycleCoupler}'s
 *       {@code defaultValue} — a no-op override that nonetheless made its map a third distinct one.
 *       The class whose subject is that switch being OFF keeps a profile of its own
 *       ({@code ServiceSettleKillSwitchTest}), because there the value is the point.
 * </ul>
 */
public class AutoStartOffProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("qits.services.autostart-enabled", "false");
  }
}
