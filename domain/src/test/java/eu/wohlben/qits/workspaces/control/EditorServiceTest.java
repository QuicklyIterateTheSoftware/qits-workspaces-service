package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The editor door's behaviour, one level below the route.
 *
 * <p>Three claims, and each of them is something a two-second poll would otherwise get wrong: the
 * row is found rather than made twice, an editor that is up is not asked for again, and readiness is
 * the service's judgement rather than the caller's.
 *
 * <p>The first of those grew a second half with the editor becoming one container for the platform.
 * It is not enough that a second call finds what the first made: <b>callers who came in from two
 * different projects</b> have to land on the same row and therefore the same container, which is the
 * whole of what changed. The door no longer takes a project, so what that reads as here is two
 * callers of one door — and the assertion is about the answer being the same row, and that row
 * belonging to no repository either of them named.
 */
@QuarkusTest
public class EditorServiceTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject FakeWorkspaceDaemonLiveness liveness;
  @Inject WorkspaceContainerStartedRecorder startedRecorder;
  @Inject ContainerRuntime containers;
  @Inject WorkspaceRepository workspaces;
  @Inject EditorService editors;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /** The container the one editor row derives — deterministic, and shared, which is the point. */
  private static final String EDITOR_CONTAINER = "qits-ws-editor-editor";

  /**
   * <b>There is no editor yet</b>, arranged rather than assumed.
   *
   * <p>A per-project editor was per-project in the database too, so every test could mint a fresh
   * repository and get a fresh editor with it. The editor is a platform singleton now and the
   * database outlives a test method — so the second test to run would find the first one's row and
   * {@code fresh} would answer whatever the class's method order happened to be. Resolving it here
   * is what makes "this call started it" a claim about the call.
   *
   * <p>The row is resolved directly rather than through {@code discardWorkspace}, which is the right
   * verb for a workspace and the wrong one for this: it resolves the repository the row names, and
   * the editor's is a sentinel nothing resolves. The container goes with it, because the next
   * ensure's answer depends on whether one is there.
   */
  @BeforeEach
  void noEditorYet() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaces
                    .findActiveEditor()
                    .ifPresent(
                        editor -> {
                          editor.status = WorkspaceStatus.ABANDONED;
                          editor.resolvedAt = Instant.now();
                        }));
    containers.rm(EDITOR_CONTAINER);
    startedRecorder.clear();
  }

  @Test
  void theFirstCallStartsTheEditorAndTheSecondFindsIt() throws Exception {
    EditorService.EditorSession first = editors.ensure();
    assertTrue(first.fresh(), "nothing was there, so this call started it — a 201");

    // Wait for the provision to settle, then let the workspace look the way a live editor does: the
    // container running and its daemon on the socket.
    assertTrue(
        startedRecorder.awaitCount(
            EditorWorkspace.REPOSITORY_ID, EditorWorkspace.WORKSPACE_ID, 1, 10_000));
    startedRecorder.clear();
    Long rowId = Long.valueOf(first.workspaceId());
    liveness.markLive(rowId);
    try {
      EditorService.EditorSession second = editors.ensure();
      assertFalse(second.fresh(), "it was already there — a 200");
      assertEquals(first.workspaceId(), second.workspaceId(), "and it is the same workspace");
      assertEquals(WorkspaceRuntimeStatus.RUNNING.name(), second.containerStatus());
    } finally {
      liveness.markDead(rowId);
    }
  }

  @Test
  void twoCallersFromTwoDifferentProjectsReachOneEditorContainer() throws Exception {
    // THE POINT OF THE WHOLE CHANGE, stated as the thing a user notices. Two projects exist and
    // somebody opens the editor from each of their pages; before, that was two rows on two wrapper
    // repositories running two containers off a multi-gigabyte image. The door takes no project any
    // more, so the two presses are the same request — and they have to answer the same row.
    String alpha = TestOrigin.create(dataDir);
    String beta = TestOrigin.create(dataDir);
    repositories.registerNamed(alpha, "master", "alpha-alpha");
    repositories.registerNamed(beta, "master", "beta-beta");

    EditorService.EditorSession fromAlpha = editors.ensure();
    EditorService.EditorSession fromBeta = editors.ensure();

    assertEquals(
        fromAlpha.workspaceId(), fromBeta.workspaceId(), "one editor row, whoever asked for it");

    // And ONE container, which is the claim the row id alone does not quite make: the name is
    // composed from the row's workspace id and repository id, and both are constants for the editor
    // — so it cannot carry either project's repository in it and cannot differ between the two
    // callers.
    assertEquals(EDITOR_CONTAINER, containers.containerName("editor", "editor"));
    assertTrue(
        startedRecorder.awaitCount(
            EditorWorkspace.REPOSITORY_ID, EditorWorkspace.WORKSPACE_ID, 1, 10_000));
    startedRecorder.clear();
    assertTrue(containers.exists(EDITOR_CONTAINER), "and it is the container that was started");
  }

  @Test
  void readinessNeedsTheEDITORAndNotOnlyTheContainer() throws Exception {
    // The container being up is half of it. WorkspaceEditorState is implemented in `service` (the
    // daemon registry, off the control socket's EditorState frame) and this module has no such
    // bean, so the port is unsatisfied here — the state is null and the readiness is false, which
    // is the honest answer and the one a waiting page needs.
    // A door that read readiness off the container alone would send a reader to an origin serving
    // nothing.
    EditorService.EditorSession session = editors.ensure();
    assertTrue(
        startedRecorder.awaitCount(
            EditorWorkspace.REPOSITORY_ID, EditorWorkspace.WORKSPACE_ID, 1, 10_000));
    startedRecorder.clear();

    EditorService.EditorSession settled = editors.ensure();
    assertNull(settled.editorState());
    assertFalse(settled.editorReady());
    assertEquals(session.workspaceId(), settled.workspaceId());
  }
}
