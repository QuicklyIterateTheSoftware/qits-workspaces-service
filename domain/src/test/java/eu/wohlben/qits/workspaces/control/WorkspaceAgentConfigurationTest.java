package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The agent-configuration document a container is born with, over the lifecycle it mirrors: fetched
 * when a container is provisioned, kept on the row for as long as that container lives, and replaced
 * by the next provision — <b>the container's lifetime, not the row's</b>, which is the same rule the
 * commissioned credential follows and for the same spec-reproducibility reason.
 *
 * <p>Every case drives {@link WorkspaceService} against the fake container runtime and reads the
 * result back through {@link AgentConfigurationDocuments}, the lookup {@link
 * WorkspaceContainerFactory} composes a container's environment from — so asserting the row through
 * that lookup asserts the thing the environment is made of. The environment itself is {@code
 * WorkspaceContainerFactoryTest}'s.
 *
 * <p><b>The failure cases are the point of the class.</b> The settled policy is that a container
 * which cannot get its document is created anyway and runs on the harness library's shipped
 * defaults, because refusing would trade a configuration outage for a work outage — and that trade
 * is only safe while the fallback is visible, so what is asserted is not merely that the workspace
 * survived but that the row says why it is running on defaults.
 */
@QuarkusTest
public class WorkspaceAgentConfigurationTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject FakeAgentConfigurationSource source;
  @Inject AgentConfigurationDocuments documents;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaces;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /**
   * Wired for these cases and unwired again afterwards, the discipline {@link
   * FakeCredentialCommissioner} carries: the double is a bean for the whole module, so leaving it
   * armed would put a document into every other test class's containers.
   */
  @BeforeEach
  void wireTheSource() {
    source.reset();
    source.wire();
  }

  @AfterEach
  void unwireTheSource() {
    source.reset();
  }

  private String clonedRepo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    return repoId;
  }

  @Transactional
  String errorOf(Long rowId) {
    return workspaces.findActiveById(rowId).map(w -> w.agentConfigurationError).orElse(null);
  }

  @Test
  public void aFreshProvisionFetchesTheDocumentAndPutsItOnTheWorkspace() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    Long rowId = workspaceIds.of(repoId, "feat");

    // Creating a workspace fetches nothing: there is no container yet, and the document's lifetime
    // is the container's.
    assertTrue(documents.forWorkspace(rowId).isEmpty(), "creation fetches nothing");
    assertEquals(0, source.fetches());

    workspaceService.ensureContainer(rowId);

    assertEquals(
        Optional.of(FakeAgentConfigurationSource.DOCUMENT),
        documents.forWorkspace(rowId),
        "the row carries the document byte for byte, which is what the container is born with");
    assertNull(errorOf(rowId), "nothing failed, so nothing is recorded");
    assertEquals(1, source.fetches(), "one read per provision, and none per launch");
  }

  @Test
  public void aStoppedContainerThatIsStartedAgainKeepsTheDocumentItWasBornWith() throws Exception {
    // The rule this whole design exists for. A running container keeps what it was created with; an
    // edit in the store reaches the NEXT container. If the resume re-fetched, the document would be
    // part of a spec that differs from the running container's — and the orchestrator replaces a
    // container whose spec moved, so an edit anywhere on the platform would destroy every running
    // workspace's container at its next ensure.
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    Long rowId = workspaceIds.of(repoId, "feat");
    workspaceService.ensureContainer(rowId);
    String born = documents.forWorkspace(rowId).orElseThrow();

    source.answering(FakeAgentConfigurationSource.DOCUMENT.replace("CLAUDE", "KIMI"));
    workspaceService.stopContainer(rowId);
    workspaceService.ensureContainer(rowId);

    assertEquals(
        Optional.of(born),
        documents.forWorkspace(rowId),
        "a resumed container was re-configured underneath itself");
    assertEquals(1, source.fetches(), "a resume asked qits-projects again");
  }

  @Test
  public void aFetchThatFailsCreatesTheContainerAnywayAndSaysSoOnTheRow() throws Exception {
    // The settled failure policy, and the half that keeps it honest. Refusing to create the
    // workspace would trade a configuration outage for a work outage; a silent fallback would be
    // green-while-dead. So: the workspace is provisioned, it holds no document, and the row names
    // the reason where the read model can answer it beside runtimeError.
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    Long rowId = workspaceIds.of(repoId, "feat");
    source.failWith("qits-projects unreachable at http://qits-projects:8080");

    workspaceService.ensureContainer(rowId);

    assertTrue(
        documents.forWorkspace(rowId).isEmpty(),
        "a container that could not get its document must carry none, not half of one");
    String recorded = errorOf(rowId);
    assertNotNull(recorded, "the fallback was silent, which is how green-while-dead happens");
    assertTrue(recorded.contains("qits-projects"), recorded);
  }

  @Test
  public void aBodyThatIsNotADocumentIsAFailureAndNotADocument() throws Exception {
    // An error page, a proxy's HTML, a 200 from something that is not qits-projects. The container
    // is still created — the policy does not change with the shape of the failure — and the row
    // still says why, because a container born with half a document is worse off than one born with
    // nothing: nothing has a documented meaning and half a document does not.
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    Long rowId = workspaceIds.of(repoId, "feat");
    source.answering("<html>502</html>");

    workspaceService.ensureContainer(rowId);

    assertTrue(documents.forWorkspace(rowId).isEmpty());
    assertNotNull(errorOf(rowId));
  }

  @Test
  public void aLaterProvisionThatSucceedsClearsTheRecordedFailure() throws Exception {
    // The pair is written atomically at every provision — a document with no error, or an error
    // with no document — so a workspace does not keep claiming a failure its current container did
    // not have. This is also how an edit takes effect: the next container is born with the newer
    // document.
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    Long rowId = workspaceIds.of(repoId, "feat");
    source.failWith("qits-projects unreachable");
    workspaceService.ensureContainer(rowId);
    assertNotNull(errorOf(rowId));

    source.reset();
    source.wire();
    // Delete the container and ensure again — the plainest spelling of "the next container", and
    // the same path a recreate takes through provisionContainer.
    workspaceService.deleteContainer(rowId);
    workspaceService.ensureContainer(rowId);

    assertEquals(Optional.of(FakeAgentConfigurationSource.DOCUMENT), documents.forWorkspace(rowId));
    assertNull(errorOf(rowId), "the row still claims a failure its current container did not have");
    assertFalse(source.answered().isEmpty());
  }
}
