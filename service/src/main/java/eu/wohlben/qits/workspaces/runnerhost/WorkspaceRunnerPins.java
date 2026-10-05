package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Which {@code qits-workspaces-runner} version a runner is meant to be: the image an install script
 * rendered now starts, and the version every {@code hello} is compared with (a runner of any other
 * is sent {@code upgrade}). It is {@link WorkspacesRunnerBinary#VERSION} — the version of the
 * protocol jar the root pom pins ({@code qits.workspaces-runner-protocol.version}), which is by
 * construction the tag of the image the same release published. qits-ci's {@code CiRunnerPins} is
 * the shape, without its override key: nothing here is a deployment's to configure, and a pin moves
 * by a gated bump of that property and nowhere else.
 */
@ApplicationScoped
public class WorkspaceRunnerPins {

  /** The runner image's repository in the platform registry, {@code qits/qits-workspaces-runner}. */
  public static final String IMAGE_REPOSITORY = WorkspacesRunnerProtocol.IMAGE_REPOSITORY;

  /** The pinned runner version; never blank (the jar refuses an unfiltered one at class load). */
  public String version() {
    return WorkspacesRunnerBinary.VERSION;
  }
}
