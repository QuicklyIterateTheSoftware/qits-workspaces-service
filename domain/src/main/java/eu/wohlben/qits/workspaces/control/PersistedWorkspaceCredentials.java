package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;

/**
 * The shipped {@link WorkspaceCredentials}: a DIRECT row's workspace token (qits-1084) lives on the
 * workspace row, so it is read back from there.
 *
 * <p><b>The value is stored, and that is the design rather than an oversight.</b> The container's
 * spec has to be reproducible at every ensure — see {@link WorkspaceCredentials} for what a spec
 * that is not costs — and a value qits-idp hands out once cannot be asked for again. Storing it is
 * also what the model already accepts: the token's lifetime IS the container's, the container
 * carries the same value in its own environment for as long as it runs, and a teardown deletes the
 * token and clears its columns in the same breath. What must never happen is a token outliving the
 * thing it authenticates, and nothing here lets one.
 *
 * <p>Reads through the ACTIVE finder deliberately: a resolved row's token is already deleted, and
 * answering with one would put a dead token into a container's environment.
 */
@ApplicationScoped
public class PersistedWorkspaceCredentials implements WorkspaceCredentials {

  @Inject WorkspaceRepository workspaces;

  /** The row's workspace token, when it holds one with a value and a subject (qits-1084). */
  @Override
  @Transactional
  public Optional<WorkspaceToken> tokenFor(Long rowId) {
    if (rowId == null) {
      return Optional.empty();
    }
    return workspaces
        .findActiveById(rowId)
        .filter(
            w ->
                w.holdsToken()
                    && present(w.commissionedToken)
                    && present(w.commissionedTokenSubject))
        .map(
            w ->
                new WorkspaceToken(
                    w.commissionedTokenId, w.commissionedToken, w.commissionedTokenSubject));
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }
}
