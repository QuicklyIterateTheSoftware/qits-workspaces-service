package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.error.MoveRefusals;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * The one gate in front of moving a regular DIRECT workspace onto a runner (qits-776). The move is
 * a <b>recreation from the branch</b>, never an adoption: the DIRECT container and its volume are
 * destroyed and a runner clones the branch afresh. So the gate's whole question is "would that
 * lose anything", and a row passes only when nothing can be lost:
 *
 * <ol>
 *   <li>it is regular — an admin or editor row is DIRECT by design ({@code NOT_REGULAR}, 400);
 *   <li>it is still DIRECT ({@code ALREADY_MOVED}, 409);
 *   <li>it is not PROVISIONING ({@code PROVISIONING}, 400);
 *   <li>its DIRECT container and volume are BOTH absent — a trivial pass, there is nothing to lose
 *       — or else:
 *   <li>the daemon reports an explicit CLEAN, recreate's rule ({@link
 *       WorkspaceService#reportedCleanliness}): dirty is {@code DIRTY}, no report {@code UNKNOWN};
 *   <li>and every commit is on the git host ({@link WorkspaceService#isFullyPushed}: the daemon's
 *       head equals {@code ls-remote} of the branch; otherwise {@code UNPUSHED}).
 * </ol>
 *
 * <p>Both rules are {@link WorkspaceService}'s, reused rather than copied, so a change to "what
 * counts as clean" or "what counts as pushed" moves recreate, cleanup and the move together.
 */
@ApplicationScoped
public class MoveGate {

  @Inject WorkspaceService workspaces;

  @Inject ContainerRuntime containers;

  /** How a row passed: trivially (no container, no volume) or on a clean, pushed tree. */
  public enum Passage {
    NOTHING_TO_LOSE,
    CLEAN_AND_PUSHED
  }

  /**
   * The cheap half — posture, placement, runtime status — which reads only the row. The door runs
   * it before it brings a stopped container up, so a request that can never pass is refused before
   * anything is started.
   */
  public void checkPosture(Workspace row) {
    if (row.admin || row.editor) {
      throw MoveRefusals.notRegular(row.workspaceId);
    }
    if (row.placement != WorkspacePlacement.DIRECT) {
      throw MoveRefusals.alreadyMoved(row.workspaceId);
    }
    if (row.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING) {
      throw MoveRefusals.provisioning(row.workspaceId);
    }
  }

  /** The whole gate: answers how the row passed, or throws the refusal. */
  public Passage check(Workspace row) {
    checkPosture(row);
    String container = containers.containerName(row.workspaceId, row.repositoryId);
    if (!containers.exists(container) && !containers.workspaceVolumeExists(row.workspaceId)) {
      return Passage.NOTHING_TO_LOSE;
    }
    Optional<Boolean> clean = workspaces.reportedCleanliness(row.id);
    if (!clean.equals(Optional.of(Boolean.TRUE))) {
      throw MoveRefusals.notClean(row.workspaceId, clean.isPresent());
    }
    if (!workspaces.isFullyPushed(row)) {
      throw MoveRefusals.unpushed(row.workspaceId);
    }
    return Passage.CLEAN_AND_PUSHED;
  }
}
