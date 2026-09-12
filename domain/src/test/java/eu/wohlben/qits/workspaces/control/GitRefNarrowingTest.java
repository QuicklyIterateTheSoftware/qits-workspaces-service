package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contracts C4 and C5 on this side (principal-bound-git-refs-plan.md): the Git refs a workspace is
 * created with, the list its commission states, and the narrowing when another workspace in the
 * same project takes one of those branches.
 *
 * <p>The idp is {@link FakeCredentialCommissioner}, wired for these cases. The update after a
 * narrowing runs on a thread of its own after the creation commits, so those assertions wait for
 * the fake's record rather than reading it straight away.
 */
@QuarkusTest
public class GitRefNarrowingTest {

  private static final String EPIC = "refs/heads/epic/e";
  private static final String TASK_A = "refs/heads/task/e/a";
  private static final String TASK_B = "refs/heads/task/e/b";
  private static final String FEATURES = "refs/heads/feature/e/*";
  private static final List<String> EPIC_LIST = List.of(EPIC, TASK_A, TASK_B, FEATURES);

  @Inject FakeRepositoryLookup repositories;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject FakeWorkspaceGitStatus gitStatus;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject GitRefScopes gitRefScopes;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /** Wired for these cases only: the double is a bean for the whole module. */
  @BeforeEach
  void wireAnIssuer() {
    commissioner.reset();
    commissioner.wire();
  }

  @AfterEach
  void unwireTheIssuer() {
    commissioner.reset();
  }

  private String repository() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    return repoId;
  }

  /** An epic workspace that may push the epic branch, two task branches and a feature pattern. */
  private Long epicWorkspace(String repoId) {
    workspaceService.createWorkspace(
        repoId,
        "epic-e",
        "master",
        "epic/e",
        null,
        false,
        false,
        false,
        WorkspaceSubject.none(),
        EPIC_LIST);
    return workspaceIds.of(repoId, "epic-e");
  }

  private List<String> refsOf(Long rowId) {
    String stored =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findActiveById(rowId).orElseThrow().gitRefs);
    return stored == null ? null : GitRefs.read(stored);
  }

  private boolean pendingOf(Long rowId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workspaceRepository.findActiveById(rowId).orElseThrow().gitRefsPending);
  }

  private String clientOf(Long rowId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workspaceRepository.findActiveById(rowId).orElseThrow().commissionedClientId);
  }

  private static void await(BooleanSupplier condition, String what) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    fail("timed out waiting for: " + what);
  }

  @Test
  public void aWorkspaceCreatedWithoutGitRefsMayPushItsOwnBranch() throws Exception {
    String repoId = repository();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat");
    Long feat = workspaceIds.of(repoId, "feat");

    assertEquals(List.of("refs/heads/feat"), refsOf(feat));

    workspaceService.ensureContainer(feat);
    assertEquals(List.of("refs/heads/feat"), commissioner.gitRefsFor(feat));
  }

  @Test
  public void aWorkspaceCreatedWithGitRefsStoresThemAndItsCommissionStatesThem() throws Exception {
    String repoId = repository();
    Long epic = epicWorkspace(repoId);

    assertEquals(EPIC_LIST, refsOf(epic));

    workspaceService.ensureContainer(epic);
    assertEquals(EPIC_LIST, commissioner.gitRefsFor(epic));
    assertFalse(pendingOf(epic), "a fresh commission states the current list");
  }

  @Test
  public void aRowFromBeforeTheColumnIsCommissionedWithItsOwnBranch() throws Exception {
    String repoId = repository();
    workspaceService.createWorkspace(repoId, "old", "master", "old");
    Long old = workspaceIds.of(repoId, "old");
    QuarkusTransaction.requiringNew()
        .run(() -> workspaceRepository.findActiveById(old).orElseThrow().gitRefs = null);

    workspaceService.ensureContainer(old);

    assertEquals(List.of("refs/heads/old"), commissioner.gitRefsFor(old));
  }

  @Test
  public void aTaskWorkspaceTakesItsBranchOutOfTheEpicsListAndTellsTheIdp() throws Exception {
    String repoId = repository();
    Long epic = epicWorkspace(repoId);
    workspaceService.ensureContainer(epic);
    String epicClient = clientOf(epic);

    workspaceService.createWorkspace(repoId, "task-e-a", "epic/e", "task/e/a");
    Long task = workspaceIds.of(repoId, "task-e-a");

    // The exact ref leaves the list; the epic's own branch, the other task and the pattern stay.
    List<String> narrowed = List.of(EPIC, TASK_B, FEATURES);
    assertEquals(narrowed, refsOf(epic));
    assertEquals(List.of(TASK_A), refsOf(task), "the task may push its own branch");

    await(
        () ->
            commissioner
                .gitRefUpdates()
                .contains(new FakeCredentialCommissioner.GitRefUpdate(epicClient, narrowed)),
        "the narrowed list sent to the epic's commission");
    await(() -> !pendingOf(epic), "the pending flag cleared once the update landed");
  }

  @Test
  public void anEpicWithNoContainerYetIsNarrowedOnTheRowAlone() throws Exception {
    String repoId = repository();
    Long epic = epicWorkspace(repoId);

    workspaceService.createWorkspace(repoId, "task-e-a", "epic/e", "task/e/a");

    List<String> narrowed = List.of(EPIC, TASK_B, FEATURES);
    assertEquals(narrowed, refsOf(epic));
    assertFalse(pendingOf(epic), "there is no commission to update");
    assertEquals(List.of(), commissioner.gitRefUpdates(), "so nothing is sent");

    // The first commission states the list as it is now.
    workspaceService.ensureContainer(epic);
    assertEquals(narrowed, commissioner.gitRefsFor(epic));
  }

  @Test
  public void aWorkspaceKeepsItsOwnBranchWhenAnotherRepositoryUsesTheSameName()
      throws Exception {
    String repoId = repository();
    Long epic = epicWorkspace(repoId);
    String sibling = repository(); // same project

    workspaceService.createWorkspace(sibling, "epic-e", "master", "epic/e");

    assertEquals(EPIC_LIST, refsOf(epic), "the epic's own branch is never narrowed away");
  }

  @Test
  public void aWorkspaceInAnotherProjectNarrowsNothing() throws Exception {
    String repoId = repository();
    Long epic = epicWorkspace(repoId);
    String elsewhere = TestOrigin.create(dataDir);
    repositories.registerInProject(elsewhere, "another-project");

    workspaceService.createWorkspace(elsewhere, "task-e-a", "master", "task/e/a");

    assertEquals(EPIC_LIST, refsOf(epic), "a list is scoped to its own project");
  }

  @Test
  public void anUpdateTheIdpDidNotTakeStaysPendingAndIsSentAgain() throws Exception {
    String repoId = repository();
    Long epic = epicWorkspace(repoId);
    workspaceService.ensureContainer(epic);
    String epicClient = clientOf(epic);
    commissioner.failGitRefUpdates(true);

    workspaceService.createWorkspace(repoId, "task-e-a", "epic/e", "task/e/a");
    await(() -> commissioner.gitRefUpdates().size() == 1, "the first, failing update");
    assertTrue(pendingOf(epic), "a failed update stays pending");

    commissioner.failGitRefUpdates(false);
    assertEquals(1, gitRefScopes.pushPending());

    List<String> narrowed = List.of(EPIC, TASK_B, FEATURES);
    assertEquals(
        new FakeCredentialCommissioner.GitRefUpdate(epicClient, narrowed),
        commissioner.gitRefUpdates().get(1));
    assertFalse(pendingOf(epic));
    assertEquals(0, gitRefScopes.pushPending(), "nothing is left to send");
  }

  @Test
  public void closingTheTaskWorkspaceGivesTheEpicNothingBack() throws Exception {
    String repoId = repository();
    Long epic = epicWorkspace(repoId);
    workspaceService.createWorkspace(repoId, "task-e-a", "epic/e", "task/e/a");
    Long task = workspaceIds.of(repoId, "task-e-a");
    workspaceService.ensureContainer(task);
    gitStatus.report(task, true); // a discard needs an explicit clean report

    workspaceService.discardWorkspace(task);

    assertEquals(List.of(EPIC, TASK_B, FEATURES), refsOf(epic), "no automatic widening");
  }
}
