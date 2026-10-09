package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.archrules.NecessaryTestProfileDuplication;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * The provision-time bootstrap trigger turned off ({@code qits.bootstrap.autorun-enabled=false}),
 * for {@link WorkspaceBootstrapKillSwitchTest}, whose subject that switch is.
 *
 * <p>It exists because a {@code @TestProfile} is a QUARKUS RESTART, and a restart is not free: it
 * augments a second application in the same JVM and abandons the first one's classloader, whose
 * metaspace is reclaimable only by a full GC. The bound in the root pom is what provokes that GC.
 * Measured 2026-09-14 while fixing bug e6f0bdfa: the surefire fork's peak was 3.41 GiB against
 * qits-ci's hard 4 g step limit. So the map holds exactly the one value that is the point, and no
 * scenery: no {@code qits.test.origins-dir} (the shipped test value is shared safely, {@link
 * TestOrigin#create} puts every origin under a UUID of its own).
 *
 * <p>It used to be {@code AutoStartOffProfile} and carry {@code qits.services.autostart-enabled=false}
 * as well, for the workspace services' kill-switch and settle tests. The services concept went with
 * qits-947, and that key and those classes with it; the bootstrap half is what is left.
 *
 * <p><b>Why this one is a {@link NecessaryTestProfileDuplication}</b> rather than folded into {@link
 * SharedTestOverridesProfile}: the switch being OFF is the SUBJECT of {@code
 * WorkspaceBootstrapKillSwitchTest} (a fresh provision with a declared chain records no run). It
 * contradicts {@code WorkspaceBootstrapRunnerTest} head-on, which runs on that shared profile and
 * whose central claim is that a fresh provision RUNS the declared chain — impossible with the
 * trigger off. The two maps assert opposite things about the same key, so no single application can serve
 * both. The duplication is the disagreement, not a preference.
 */
public class BootstrapAutorunOffProfile
    implements QuarkusTestProfile, NecessaryTestProfileDuplication {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("qits.bootstrap.autorun-enabled", "false");
  }
}
