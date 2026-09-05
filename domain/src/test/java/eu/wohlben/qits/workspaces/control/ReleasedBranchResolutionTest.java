package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.WorkspaceEventDto;
import eu.wohlben.qits.workspaces.dto.WorkspaceHistoryDetailDto;
import eu.wohlben.qits.workspaces.entity.WorkspaceEventType;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code resolveReleasedBranch} — the workspace-lifecycle verb a release calls once it has deleted a
 * branch on the git host.
 *
 * <p>The case that made it exist is an <b>absence</b>: the deletion fires no event, so without this
 * call the workspace standing on the released branch stays ACTIVE forever, holding a container, a
 * volume and a commissioned credential for a ref nobody can fetch. So the assertions are about the
 * teardown being complete, and about the two refusals that keep a release away from the workspace a
 * repository is actually worked in.
 *
 * <p><b>The fixture leaves the branch in the origin on purpose.</b> In production the release has
 * already deleted it, and nothing this verb does depends on that — but a surviving ref is the only
 * way to <em>observe</em> that no delete was pushed. A ref that is genuinely gone would make a
 * skipped deletion and a swallowed failed one look identical.
 */
@QuarkusTest
public class ReleasedBranchResolutionTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject WorkspaceCredentials credentials;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceHistoryService history;
  @Inject ContainerRuntime containers;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /**
   * Wired for these cases and unwired again afterwards: the double is one bean for the whole
   * module's suite, so an armed issuer would commission for every other class's containers too.
   */
  @BeforeEach
  void wireAnIssuer() {
    commissioner.reset();
    commissioner.wire();
  }

  @AfterEach
  void unwireTheIssuer() {
    commissioner.reset();
  }

  private String clonedRepo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    return repoId;
  }

  private String originBranches(String repoId) throws Exception {
    Path origin = Path.of(dataDir, repoId, "origin").toAbsolutePath();
    return TestGit.exec(
        origin.toFile(), "git", "for-each-ref", "--format=%(refname:short)", "refs/heads");
  }

  private WorkspaceHistoryDetailDto detail(Long rowId) {
    return history.get(rowId);
  }

  @Test
  public void aWorkspaceOnTheReleasedBranchResolvesAsIntegrated() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    Long rowId = workspaceIds.of(repoId, "feat");
    workspaceService.ensureContainer(rowId);
    String container = containers.containerName("feat", repoId);
    WorkspaceCredential credential = credentials.forWorkspace(rowId).orElseThrow();
    assertTrue(containers.exists(container), "the fixture has a live container to tear down");

    WorkspaceService.BranchResolution answer =
        workspaceService.resolveReleasedBranch(
            repoId, "feat", "2026.905.120000", "0123456789abcdef", "released");

    assertTrue(answer.resolved());
    assertEquals(rowId, answer.workspaceId(), "the answer names the row it resolved");

    WorkspaceHistoryDetailDto resolved = detail(rowId);
    assertEquals(WorkspaceStatus.INTEGRATED, resolved.status());
    assertNotNull(resolved.resolvedAt(), "a resolution is stamped");
    assertEquals("released", resolved.result());

    // The whole point: container, volume and credential all go.
    assertFalse(containers.exists(container), "the container is removed");
    // Filtered by repository: the fake runtime is one bean for the whole module's suite, so a bare
    // "no volumes at all" would be an assertion about whatever else ran first.
    assertTrue(
        containers.listWorkspaceVolumes().stream().noneMatch(v -> repoId.equals(v.repoId())),
        "and so is its /workspace volume");
    assertEquals(List.of(credential.clientId()), commissioner.decommissioned());
    assertEquals(List.of(), commissioner.liveClientIds());

    // The timeline entry names what consumed the branch, so it can be followed back to the release.
    WorkspaceEventDto integrated =
        resolved.events().stream()
            .filter(e -> e.type() == WorkspaceEventType.INTEGRATED)
            .findFirst()
            .orElseThrow();
    assertEquals("feat", integrated.branch());
    assertEquals("2026.905.120000", integrated.target());
    assertEquals("0123456789abcdef", integrated.commit());
  }

  /** The branch deletion is SKIPPED — the release already made it, and nothing here pushes one. */
  @Test
  public void theBranchIsLeftAloneBecauseTheReleaseAlreadyDeletedIt() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);

    workspaceService.resolveReleasedBranch(repoId, "feat", "2026.905.120000", null, null);

    // A discard would have pushed `:refs/heads/feat` here. This verb does not: see the fixture note
    // on the class — the surviving ref is the observation, not the production state.
    assertTrue(originBranches(repoId).contains("feat"), "no branch deletion was pushed");
  }

  @Test
  public void aBranchWithNoWorkspaceIsTheOrdinaryAnswerAndNothingIsTornDown() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));

    WorkspaceService.BranchResolution answer =
        workspaceService.resolveReleasedBranch(repoId, "some-other-branch", "v1", null, null);

    assertFalse(answer.resolved());
    assertNull(answer.workspaceId());
    // The unrelated workspace is untouched — this is not a sweep.
    assertTrue(containers.exists(containers.containerName("feat", repoId)));
    assertEquals(List.of(), commissioner.decommissioned());
  }

  /**
   * Belt one, on its own: the row has no parent. The registry's main branch is moved away first so
   * the second belt cannot be what refuses.
   */
  @Test
  public void theMainWorkspaceIsRefusedBecauseItHasNoParent() throws Exception {
    String repoId = clonedRepo();
    repositories.setMainBranch(repoId, "trunk");

    BadRequestException refused =
        assertThrows(
            BadRequestException.class,
            () -> workspaceService.resolveReleasedBranch(repoId, "master", "v1", null, null));

    assertTrue(
        refused.getMessage().contains("no parent branch"),
        "the refusal names the belt: " + refused.getMessage());
    assertEquals(WorkspaceStatus.ACTIVE, detail(workspaceIds.of(repoId, "master")).status());
  }

  /**
   * Belt two, on its own: a row that HAS a parent, standing on what the registry now calls the
   * default branch. This is the reading the first belt cannot make — a main branch renamed under a
   * workspace created as an ordinary one.
   */
  @Test
  public void aWorkspaceOnTheDefaultBranchIsRefusedEvenWithAParent() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    repositories.setMainBranch(repoId, "feat");

    BadRequestException refused =
        assertThrows(
            BadRequestException.class,
            () -> workspaceService.resolveReleasedBranch(repoId, "feat", "v1", null, null));

    assertTrue(
        refused.getMessage().contains("default branch"),
        "the refusal names the belt: " + refused.getMessage());
    assertEquals(WorkspaceStatus.ACTIVE, detail(workspaceIds.of(repoId, "feat")).status());
  }

  /** The caller is best-effort and retries; a second call must be a no-op, not a second teardown. */
  @Test
  public void aSecondCallAfterTheResolutionAnswersFalse() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    Long rowId = workspaceIds.of(repoId, "feat");
    workspaceService.ensureContainer(rowId);

    assertTrue(
        workspaceService.resolveReleasedBranch(repoId, "feat", "v1", null, null).resolved());
    int decommissionedOnce = commissioner.decommissioned().size();

    WorkspaceService.BranchResolution again =
        workspaceService.resolveReleasedBranch(repoId, "feat", "v1", null, null);

    assertFalse(again.resolved());
    assertNull(again.workspaceId());
    assertEquals(decommissionedOnce, commissioner.decommissioned().size(), "nothing runs twice");
    assertEquals(WorkspaceStatus.INTEGRATED, detail(rowId).status());
  }
}
