package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
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
 * The editor posture, read off the row: a workspace is the editor when its {@code editor} column is
 * set, and every other workspace is not.
 *
 * <p>The predicate is a column read now, so what is worth a test is what surrounds it — this answer
 * picks a container's image and its environment, and a spec that differs from what is running is a
 * {@code Recreate.ifChanged} <b>replacement</b>. So: an ordinary workspace is never it, the answer
 * does not move between two reads, and every absence falls to false.
 *
 * <p><b>Four cases that used to be here are gone with the derivation.</b> A non-wrapper repository's
 * main workspace, a wrapper's other branches, a registry that could not be asked not un-deciding an
 * already-decided workspace, and a 200 that arrived without an archetype or a main branch not being
 * memoized — all four were about a posture computed from {@link RepositoryLookup}, and the class
 * under test no longer calls it. The reproducibility they protected is now a property of the column
 * rather than of a memo, which is what {@link #theAnswerDoesNotMoveBetweenTwoEnsures} says instead.
 */
@QuarkusTest
public class PersistedWorkspacePosturesTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaces;
  @Inject WorkspacePostures postures;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /** The editor is one row for the whole database, so a class about it resolves the last one. */
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
  }

  @Test
  void theEditorRowIsTheEditorsWorkspace() {
    Workspace editor = workspaceService.createEditorWorkspace();

    assertTrue(postures.isEditor(editor.id));
    // …and it is not the admin kind by that fact. The two postures are independent: the socket is
    // asked for at creation, the editor is a row of its own, and neither implies the other.
    assertFalse(postures.isAdmin(editor.id));
  }

  @Test
  void anOrdinaryWorkspaceIsNot() throws Exception {
    // Every workspace anybody works in, including the one on a repository's main branch — which is
    // exactly what the editor USED to be, and is now just a workspace.
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId, "master");
    Workspace main = workspaceService.createMainWorkspace(repoId, "master");
    Workspace branched =
        workspaceService.createWorkspace(repoId, "editor-check", "master", "task/editor-check", null);

    assertFalse(postures.isEditor(main.id));
    assertFalse(postures.isEditor(branched.id));
  }

  @Test
  void theAnswerDoesNotMoveBetweenTwoEnsures() throws Exception {
    // THE REPRODUCIBILITY CLAIM, which is why the posture is a column at all. The orchestrator has
    // no start verb — a stopped container is resumed by presenting its spec AGAIN under
    // Recreate.ifChanged — so an answer that flipped between two ensures would describe a
    // plain-image container and REPLACE the editor's one. It used to take a memo to promise that,
    // because the answer came off a live registry call that an outage could turn into "no"; a column
    // in this service's own database is the same answer every time by construction, and the registry
    // being unreachable is not even a thing this lookup can notice.
    Workspace editor = workspaceService.createEditorWorkspace();
    assertTrue(postures.isEditor(editor.id));

    repositories.findOutage(true);
    try {
      assertTrue(postures.isEditor(editor.id), "the row says so whatever qits-projects is doing");
    } finally {
      repositories.findOutage(false);
    }
  }

  @Test
  void anUnknownWorkspaceIsNotTheEditorsWorkspace() {
    assertFalse(postures.isEditor(-1L));
    assertFalse(postures.isEditor(null));
  }
}
