package eu.wohlben.qits.workspaces.control;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * One test application for every class that needs a shipped value moved but contradicts nobody.
 *
 * <p>It is deliberately not a theme. The four overrides below have nothing to do with each other —
 * a chain-await bound, a git identity, a cache window — and that is precisely the point: a {@code
 * @TestProfile} is a QUARKUS RESTART, Quarkus keys applications by profile IDENTITY rather than by
 * the map they produce, and three classes each carrying a map of its own were three restarts whose
 * classloaders are reclaimable only by a full GC. Measured 2026-09-14 while fixing bug e6f0bdfa:
 * each retained application is ~125 MB of metaspace, and the sibling {@code service} module's
 * fourteen profiles OOM-killed four CI gates at exit 137 against qits-ci's hard 4 g step limit.
 * Union what does not conflict and you pay for one application instead of three.
 *
 * <p>What makes the union legitimate is that no override here is any other consumer's SUBJECT. A
 * class that asserts about a value cannot share a map with a class that needs it otherwise — that is
 * what {@link AutoStartOffProfile} and {@code ServiceSettleKillSwitchTest.TestProfile} are for, and
 * they carry the marker saying so. These four are all in the other register: each one is scenery
 * that some class needs held still, and holding it still costs the other two nothing.
 *
 * <p>Note what is absent: {@code qits.test.origins-dir}. All three folded profiles used to mint a
 * fresh temp directory for it, which bought nothing — {@link TestOrigin#create} keys every origin by
 * a UUID of its own, so the thirty-odd classes on the default profile have always shared {@code
 * target/workspaces-test-data} without colliding — while guaranteeing by construction that no two of
 * these maps could ever be equal. That override is what multiplied the profiles in the first place.
 *
 * <p>Sharing an application means sharing the {@code @Singleton} fakes across the three classes'
 * methods, so the cross-test hygiene each one already practises (clearing the fakes it stages,
 * draining its own async passes) is now load-bearing between classes too, not just within one.
 */
public class SharedTestOverridesProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        // WorkspaceBootstrapRunnerTest: the host's chain-await timeout. The fake driver runs the
        // chain synchronously, so this only ever bounds a hung await — the shipped six hours would
        // be six hours of a wedged build.
        "qits.bootstrap.await-timeout-ms", "8000",
        // GitIdentityAttributionTest: the identity whose appearance in `git log` IS the assertion.
        // Pinned rather than defaulted, because the claim is that the CONFIGURED name reaches the
        // commit — a value read out of the ambient environment would assert nothing.
        "qits.git.author-name", "qits-bot",
        "qits.git.author-email", "qits-bot@example.com",
        // EditorProxyTargetsTest: the label-miss window, shortened so waiting it out is a second
        // rather than the shipped five. The assertion is that the answer expires at all, not that
        // it expires after any particular number.
        "qits.editor.label-miss-ttl-ms", "1000");
  }
}
