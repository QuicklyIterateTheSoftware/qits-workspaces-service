package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.install.InstallScript;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.error.DomainException;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The install line a runner is created with and the generic {@code install.sh} it pipes into {@code
 * sh}, both rendered by qits-runner-toolkit's {@link InstallScript} from the toolkit's own template
 * (qits-771, qits-868). <b>There is no copy of the template here</b>: what a runner's host runs is
 * the template the runner's own release was built with, filled with this service's names.
 *
 * <p><b>The identity is the runner's, built the runner's way.</b> {@link #IDENTITY} is constructed
 * from {@link WorkspacesRunnerProtocol}'s roots and {@link WorkspacesRunnerBinary#VERSION}, in
 * {@link RunnerIdentity}'s order, exactly as the runner's {@code WorkspacesRunnerIdentity} is — so
 * the environment variables the line sets, the container and volume names the script creates and
 * the image it starts are the ones the runner reads, and cannot drift.
 *
 * <p>Both renderings carry no secret of the script's own: the line carries the registration token
 * it was minted for (that is its purpose, and it is shown once), the script carries none.
 */
@ApplicationScoped
public class WorkspaceRunnerInstallScript {

  /** Where {@code install.sh} is served, under this service's public origin. */
  public static final String PATH = "/workspaces/api/runners/install.sh";

  /** How the script names the place an operator got the line from. */
  static final InstallScript.InstallPage PAGE =
      new InstallScript.InstallPage("the Workspaces UI's Runners page", List.of());

  /** The workspaces runner's identity at the pinned version — the runner's own, see the javadoc. */
  public static final RunnerIdentity IDENTITY =
      new RunnerIdentity(
          WorkspacesRunnerProtocol.KIND,
          WorkspacesRunnerProtocol.ENV_PREFIX,
          WorkspacesRunnerProtocol.LABEL_ROOT,
          WorkspacesRunnerProtocol.NAME_PREFIX,
          WorkspacesRunnerProtocol.IMAGE_REPOSITORY,
          WorkspacesRunnerProtocol.STATE_DIR,
          WorkspacesRunnerProtocol.REGISTER_PATH,
          WorkspacesRunnerProtocol.SERVICE_NAME,
          WorkspacesRunnerBinary.VERSION,
          WorkspacesRunnerProtocol.CAPABILITY_VERSION,
          WorkspacesRunnerProtocol.VOCABULARY,
          WorkspacesRunnerProtocol.WORK_LABEL_SUFFIX);

  @Inject WorkspaceRunnerAddresses addresses;

  /**
   * 503 unless both renderings would succeed now: a public domain to address the runner by
   * ({@code RUNNER_PLANE_UNCONFIGURED}) and a script the template can be filled into. Asked before
   * anything is minted, so a misconfiguration costs no token.
   */
  public void requireRenderable() {
    addresses.requireConfigured();
    script();
  }

  /** The generic script: this deployment's registry and the pinned runner image, and no secret. */
  public String script() {
    try {
      return InstallScript.script(IDENTITY, PAGE, addresses.registryHost());
    } catch (IllegalStateException unrenderable) {
      throw new DomainException(503, unrenderable.getMessage());
    }
  }

  /**
   * The one line that installs {@code runner}, carrying {@code registrationToken}: it fetches
   * {@link #PATH} with the token and pipes the script into {@code sudo env … sh} with the runner's
   * URL, id, token and slots.
   */
  public String line(WorkspaceRunner runner, String registrationToken) {
    try {
      return InstallScript.line(
          IDENTITY,
          addresses.serviceBase(),
          PATH,
          runner.id.toString(),
          registrationToken,
          runner.slots);
    } catch (IllegalStateException unrenderable) {
      throw new DomainException(503, unrenderable.getMessage());
    }
  }

  /** {@link InstallScript#requireCarriable}: a token qits-idp minted that the line cannot carry. */
  public static void requireCarriable(String registrationToken) {
    InstallScript.requireCarriable(registrationToken);
  }
}
