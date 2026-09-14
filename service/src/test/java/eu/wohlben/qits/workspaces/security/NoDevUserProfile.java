package eu.wohlben.qits.workspaces.security;

import eu.wohlben.qits.archrules.NecessaryTestProfileDuplication;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Blanks the {@code %test} dev-user fallback this module ships, so a test sees the deployed
 * posture: no header ⇒ anonymous. (An empty value reads as absent for the {@code Optional} config
 * property.)
 *
 * <p><b>It cannot join the module's shared profile, because it is that profile's opposite.</b> Every
 * other class here calls a door with no header and expects the {@code %test} dev identity to answer
 * for it; the classes behind this one call the same doors with no header and their assertion is the
 * refusal. One application cannot make "no header" mean both, so this is a restart the module buys
 * on purpose rather than a config map somebody forgot to merge.
 */
public class NoDevUserProfile implements QuarkusTestProfile, NecessaryTestProfileDuplication {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("qits.auth.forward.dev-user", "");
  }
}
