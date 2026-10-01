package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;

/**
 * The shipped {@link WorkspaceEntityFacts}: the three {@code V9} columns, read back off the ACTIVE
 * row like {@link PersistedWorkspaceCredentials} and {@link PersistedWorkspacePostures} beside it.
 *
 * <p>A row that has never been told anything — all three columns null, which is every ad-hoc
 * workspace and every row older than {@code V9} — answers empty, so its spec stays byte-identical to
 * the one it had before the columns existed.
 */
@ApplicationScoped
public class PersistedWorkspaceEntityFacts implements WorkspaceEntityFacts {

  @Inject WorkspaceRepository workspaces;

  @Override
  @Transactional
  public Optional<EntityFacts> forWorkspace(Long rowId) {
    if (rowId == null) {
      return Optional.empty();
    }
    return workspaces
        .findActiveById(rowId)
        .filter(w -> w.entityTitle != null || w.entityStatus != null || w.entityBlocked != null)
        .map(
            w ->
                new EntityFacts(
                        w.entityTitle, w.entityStatus, Boolean.TRUE.equals(w.entityBlocked))
                    .normalized());
  }
}
