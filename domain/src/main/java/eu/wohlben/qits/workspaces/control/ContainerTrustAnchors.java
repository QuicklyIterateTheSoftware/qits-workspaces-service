package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The shipped {@link WorkspaceTrustAnchors}: the bundle a DIRECT row's current container was created
 * with lives on the row ({@code workspace.container_trust_anchors}, {@code V42}), written by {@link
 * #captureForNewContainer} immediately before a container is created and read back at every ensure.
 *
 * <p>Reads through the ACTIVE finder, like {@link PersistedWorkspaceCredentials}: a resolved row has
 * no container to describe.
 */
@ApplicationScoped
public class ContainerTrustAnchors implements WorkspaceTrustAnchors {

  private static final Logger LOG = Logger.getLogger(ContainerTrustAnchors.class);

  @Inject WorkspaceRepository workspaces;

  /** Today's bundle; unresolvable means none, which is a container created without the variable. */
  @Inject Instance<TrustAnchorSource> source;

  @Override
  @Transactional
  public Optional<String> anchorsFor(Long rowId) {
    if (rowId == null) {
      return Optional.empty();
    }
    return workspaces
        .findActiveById(rowId)
        .map(w -> w.containerTrustAnchors)
        .filter(pem -> !pem.isBlank());
  }

  /**
   * Records today's bundle — or none — as the one the container about to be created for {@code
   * rowId} is created with. Called on the creation paths only (a provision's run, a recreate's run),
   * never on a start of a container that exists: that one keeps what it was created with. Never
   * throws: a creation must not fail over trust anchors, so a write that fails leaves the row as it
   * was and the container is described from that.
   */
  public void captureForNewContainer(Long rowId) {
    if (rowId == null) {
      return;
    }
    String pem = currentBundle().orElse(null);
    try {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  workspaces
                      .findActiveById(rowId)
                      .ifPresent(w -> w.containerTrustAnchors = pem));
    } catch (RuntimeException e) {
      LOG.warnf(e, "could not record the trust anchors of workspace %s's new container", rowId);
    }
  }

  private Optional<String> currentBundle() {
    if (source == null || !source.isResolvable()) {
      return Optional.empty();
    }
    try {
      return source.get().pem().filter(pem -> !pem.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf(e, "could not read the trust anchor bundle; the container is created without it");
      return Optional.empty();
    }
  }
}
