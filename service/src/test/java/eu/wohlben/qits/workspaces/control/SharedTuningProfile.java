package eu.wohlben.qits.workspaces.control;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * <b>This module's one unmarked test profile</b>: every surefire class that needs nothing but a dial
 * turned names this one, and turning a new dial means adding a key here rather than writing a
 * profile.
 *
 * <p>A {@code @TestProfile} is not a configuration overlay, it is an APPLICATION. Quarkus compares
 * the profile CLASS, not the map it returns, so two classes with identical overrides are still two
 * augmentations — and an augmentation abandons a classloader whose ~125 MB of metaspace only a full
 * GC gives back. Fourteen of them in this module took the surefire fork to 3.41 GiB against qits-ci's
 * hard 4 g step limit and killed four consecutive release requests at exit 137, with every test
 * passing and no failure to read (bug e6f0bdfa). {@code TestProfileBudgetTest} is what now stops a
 * fifteenth arriving unargued; this class is what makes having only one possible.
 *
 * <p><b>Why these four keys can share one application.</b> None of them is the SUBJECT of the class
 * that asked for it — each is a number that makes an assertion cheap rather than a behaviour under
 * test — and none of them is read by any other class in the set. A class whose subject IS its
 * configuration (the machine gate, the launch window, an oidc client's identity) cannot come here,
 * because the value it needs is exactly the value its co-tenants must not see; those carry {@code
 * NecessaryTestProfileDuplication} and say so in their own javadoc.
 *
 * <p><b>No {@code qits.test.origins-dir} here, deliberately.</b> Four of the profiles folded into
 * this one minted a throwaway temp directory apiece. That bought nothing — {@code
 * TestOrigin#create} puts every origin under its own UUID, which is why the default profile's
 * thirty-odd classes share {@code target/workspaces-test-data} without colliding — while
 * guaranteeing by construction that no two config maps could ever be equal, which is a good part of
 * how fourteen profiles happened. The one class that overrides it does so because a RELATIVE path is
 * its subject ({@code WorkspaceRelativeDataDirTest}), which is the opposite of a throwaway.
 */
public class SharedTuningProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        // The technical-process SSE heartbeat, so the ping assertion does not wait out the shipped
        // 25 s. TechnicalProcessEventsControllerTest.
        "qits.process.heartbeat-ms",
        "200",
        // Small enough to trip the oversize cases cheaply against the shipped 10 MiB, large enough
        // for every happy path. CaptureResourceTest.
        "qits.capture.max-payload-bytes",
        "8192",
        // The reverse tunnel's nonce window, shortened from 10 s: short enough that the expiry case
        // does not dominate the run, long enough that a loopback dial-back never loses the race.
        // DaemonStreamRouteTest and EditorTunnelRouteTest, which are one shape over two targets.
        "qits.workspace.daemon-tunnel.nonce-ttl-ms",
        "8000",
        // The editor idle-stop policy, which ships BLANK and off. This is the only key here that
        // turns a behaviour on rather than moving a number, and it is safe to share because it
        // rides the container POLICY at creation: an editor container is described with
        // `idleStop(30m)` instead of an explicit lifetime, and EditorKeepalive stops being a no-op.
        // Thirty minutes is far longer than any class in this set runs, so nothing here can idle
        // long enough for the far end to act, and no co-tenant asserts a container's policy.
        // EditorKeepaliveTest. (`qits.editor.touch-interval` came with it and is gone: PT30S merely
        // restated the shipped default.)
        "qits.editor.idle-stop-after",
        "PT30M");
  }
}
