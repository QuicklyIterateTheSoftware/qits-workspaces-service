package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The editor origin resolved to a workspace, out of this service's own state.
 *
 * <p>The claims that matter are the two the SSRF posture rests on and the two the cache rests on: a
 * label that names nothing resolves to nothing (and the caller 404s without connecting anywhere), a
 * repository that is not a wrapper is never the answer even when its name would fit, the workspace
 * half is re-read rather than remembered — a main workspace can be discarded and made again, and a
 * stale row id would point the proxy at nothing — and a MISS costs one scan per window rather than
 * one per request.
 *
 * <p>The profile shortens that window. The shipped five seconds would be five seconds of sleeping
 * here, and the assertion is about the answer expiring rather than about the number. It is the
 * shared profile rather than one of this class's own: the window is nobody else's subject, so it
 * rides along with two other classes' scenery in a single Quarkus application instead of buying a
 * restart for one line (see {@link SharedTestOverridesProfile}). The 1200 ms sleep below is sized
 * against the second it sets.
 */
@QuarkusTest
@TestProfile(SharedTestOverridesProfile.class)
public class EditorProxyTargetsTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject EditorProxyTargets targets;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /** A wrapper repository for {@code <slug>}, named the way qits-projects names one. */
  private String wrapperFor(String slug) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.registerWrapper(repoId, "master", EditorHost.wrapperRepositoryName(slug));
    return repoId;
  }

  @Test
  void anEditorOriginResolvesToTheWrappersMainWorkspace() throws Exception {
    String slug = "editorproj";
    String repoId = wrapperFor(slug);
    Workspace main = workspaceService.createMainWorkspace(repoId, "master");

    Optional<EditorProxyTargets.EditorTarget> target =
        targets.resolve("editor." + slug + ".dev.example.eu");

    assertTrue(target.isPresent());
    assertEquals(main.id, target.get().workspaceRowId());
    assertEquals(repoId, target.get().repositoryId());
    assertEquals(slug, target.get().projectLabel());
  }

  @Test
  void anUnknownLabelResolvesToNothing() throws Exception {
    // The whole of the 404: no project by that name, so no repository, so no row, so no container is
    // dialled. It is the same answer a malformed host gets, which is what makes the refusal
    // uninformative on purpose.
    wrapperFor("editorproj2");

    assertTrue(targets.resolve("editor.nosuchproject.dev.example.eu").isEmpty());
    assertTrue(targets.resolve("editor.nosuchproject").isEmpty(), "not an editor origin at all");
    assertTrue(targets.resolve(null).isEmpty());
  }

  @Test
  void aRepositoryThatIsNotAWrapperIsNeverTheAnswer() throws Exception {
    // The name alone must not do it. A project whose slug happens to derive the name of an ordinary
    // repository would otherwise hand that repository's main workspace out as an editor.
    String slug = "impostor";
    String repoId = TestOrigin.create(dataDir);
    repositories.registerAs(repoId, "master", "SERVICE");
    // the wrapper's name, on a repository that is not one
    repositories.registerWrapper("some-other-id", "master", "unrelated-unrelated");
    workspaceService.createMainWorkspace(repoId, "master");

    assertTrue(targets.resolve("editor." + slug + ".dev.example.eu").isEmpty());
  }

  @Test
  void theWorkspaceHalfIsReReadAndNotRemembered() throws Exception {
    // The registry half is an immutable fact (a project's slug cannot change, so neither can its
    // wrapper's name) and is cached; the row half moves, so it is not. Resolving before the main
    // workspace exists must not poison the answer for after it does — which is also what a miss
    // held against its CANDIDATE SET buys: the first call here is a miss (no root workspace, so the
    // repository is not even a candidate), and the second must not wait out its window, because
    // what a reader has in front of them meanwhile is a 404 page rather than a reloading splash.
    String slug = "laterproj";
    String repoId = wrapperFor(slug);
    String host = "editor." + slug + ".dev.example.eu";

    assertTrue(targets.resolve(host).isEmpty(), "no main workspace yet");

    Workspace main = workspaceService.createMainWorkspace(repoId, "master");
    assertEquals(main.id, targets.resolve(host).orElseThrow().workspaceRowId());
  }

  @Test
  void aStormOfMissesCostsOneScanPerWindowAndTheAnswerStillExpires() throws Exception {
    // The miss path is a scan: one qits-projects call per ROOT repository, and every slug-shaped
    // label takes it. The SPA polls the editor door every two seconds while a project's main
    // workspace is being created, so an uncached miss is N round trips twice a second for a 404.
    String repoId = wrapperFor("stormy");
    workspaceService.createMainWorkspace(repoId, "master");
    String host = "editor.notaprojectyet.dev.example.eu";

    int beforeFirst = repositories.findCalls();
    assertTrue(targets.resolve(host).isEmpty());
    assertTrue(repositories.findCalls() > beforeFirst, "the first miss really does scan");

    int afterFirst = repositories.findCalls();
    for (int i = 0; i < 10; i++) {
      assertTrue(targets.resolve(host).isEmpty());
    }
    assertEquals(afterFirst, repositories.findCalls(), "a storm of misses costs no round trip");

    // And it is a WINDOW, not an answer: a project registered a minute from now must resolve then,
    // which is why the positive half of this cache may be kept for good and this half may not.
    Thread.sleep(1200);
    assertTrue(targets.resolve(host).isEmpty());
    assertTrue(repositories.findCalls() > afterFirst, "the miss expires and the scan runs again");
  }

  @Test
  void aRegistryThatCouldNotBeAskedIsNotREMEMBEREDAsAnAbsence() throws Exception {
    // "Could not ask" is not "not there" — the distinction this port is read with everywhere. A
    // blip written into the miss map would 404 a project that exists for the whole window.
    String slug = "outageproj";
    String repoId = wrapperFor(slug);
    workspaceService.createMainWorkspace(repoId, "master");
    String host = "editor." + slug + ".dev.example.eu";

    repositories.findOutage(true);
    try {
      assertTrue(targets.resolve(host).isEmpty(), "nothing resolves while the registry is down");
    } finally {
      repositories.findOutage(false);
    }

    assertEquals(
        repoId,
        targets.resolve(host).orElseThrow().repositoryId(),
        "the outage must not have been remembered as an absence");
  }
}
