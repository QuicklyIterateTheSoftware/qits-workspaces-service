package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;

/**
 * The shipped {@link AgentConfigurationDocuments}: the document lives on the workspace row, so it is
 * read back from there.
 *
 * <p>Reads through the ACTIVE finder, like {@link PersistedWorkspaceCredentials} and {@link
 * PersistedWorkspacePostures} beside it — a resolved workspace has no container to describe.
 *
 * <p>A blank column is no document rather than an empty one: a container told to read a document
 * that is not there fails at boot, and "nothing was stored" must reach the harness library as the
 * absence it is, which its shipped defaults answer.
 */
@ApplicationScoped
public class PersistedAgentConfigurationDocuments implements AgentConfigurationDocuments {

  @Inject WorkspaceRepository workspaces;

  @Override
  @Transactional
  public Optional<String> forWorkspace(Long rowId) {
    if (rowId == null) {
      return Optional.empty();
    }
    return workspaces
        .findActiveById(rowId)
        .map(w -> w.agentConfiguration)
        .filter(document -> document != null && !document.isBlank());
  }
}
