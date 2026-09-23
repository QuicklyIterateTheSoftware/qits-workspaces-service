package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The editor origin resolved to a workspace, out of this service's own state.
 *
 * <p>Three claims, and they are what the proxy's answers rest on: an editor origin resolves to the
 * one editor row whatever else the name says, a request that is not an editor origin resolves to
 * nothing at all, and an absent editor is nothing rather than something invented — the caller 404s
 * without connecting anywhere.
 *
 * <p><b>Most of what this class used to assert has no subject left</b>, and the cases went with it
 * rather than being rephrased: a project label naming an unknown project, a repository that is not a
 * wrapper never being the answer, the registry half being remembered while the row half is not, a
 * storm of misses costing one scan per window, and an outage not being written down as an absence.
 * Every one of them was about turning a project's name into a repository — a scan of qits-projects,
 * with a hit cache and a miss cache in front of it. There is one editor now, the resolution is a
 * single indexed local query, and none of those failure modes can occur. It also has no
 * {@code @TestProfile} any more: the only thing it needed a moved config value for was the
 * miss window, and that key is retired.
 */
@QuarkusTest
public class EditorProxyTargetsTest {

  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaces;
  @Inject EditorProxyTargets targets;

  /**
   * The editor is a singleton in the database as well as on the platform, and the database outlives
   * a test method — so a class that asserts about "no editor" has to make that true rather than
   * assume it.
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
  }

  @Test
  void anEditorOriginResolvesToTheOneEditorWorkspace() {
    Workspace editor = workspaceService.createEditorWorkspace();

    var target = targets.resolve("editor.dev.example.eu");

    assertTrue(target.isPresent());
    assertEquals(editor.id, target.get().workspaceRowId());
    // The two halves the container name is composed from, and both are constants: that is what makes
    // the editor's container deterministic and shared rather than derived from whoever asked.
    assertEquals(EditorWorkspace.REPOSITORY_ID, target.get().repositoryId());
    assertEquals(EditorWorkspace.WORKSPACE_ID, target.get().workspaceId());
  }

  @Test
  void whatIsBEHINDTheFirstLabelChangesNothing() {
    // The origin is still spelled with the old per-project grammar and is moving to a shorter one,
    // and neither says anything about which editor this is — because there is one. A resolution that
    // still read the name would answer differently for these two.
    Workspace editor = workspaceService.createEditorWorkspace();

    assertEquals(
        editor.id, targets.resolve("editor.someproject.dev.example.eu").orElseThrow().workspaceRowId());
    assertEquals(editor.id, targets.resolve("editor.dev.example.eu").orElseThrow().workspaceRowId());
  }

  @Test
  void aHostThatIsNotTheEditorsResolvesToNothing() {
    // Not a redirect and not the editor anyway: a name this route was not addressed by is a request
    // for another surface entirely, and the route falls through rather than answering.
    workspaceService.createEditorWorkspace();

    assertTrue(targets.resolve("workspaces.dev.example.eu").isEmpty());
    assertTrue(targets.resolve("editorial.example.eu").isEmpty());
    assertTrue(targets.resolve(null).isEmpty());
  }

  @Test
  void anEditorNobodyHasOpenedYetIsNothing() {
    // The whole of the 404 on a fresh platform: the row is written by the DOOR, so a browser that
    // navigates straight to the editor origin finds nothing — and nothing is started for it, because
    // a GET at an origin is not somebody asking for a container.
    assertTrue(targets.resolve("editor.dev.example.eu").isEmpty());
  }

  @Test
  void theRowIsReReadAndNotRemembered() {
    // The half of the old cache that was right stays right: a workspace row can be resolved and made
    // again, so a remembered row id would point the proxy at nothing. Resolving before the editor
    // exists must not poison the answer for after it does.
    assertTrue(targets.resolve("editor.dev.example.eu").isEmpty(), "no editor yet");

    Workspace editor = workspaceService.createEditorWorkspace();
    assertEquals(editor.id, targets.resolve("editor.dev.example.eu").orElseThrow().workspaceRowId());
  }
}
