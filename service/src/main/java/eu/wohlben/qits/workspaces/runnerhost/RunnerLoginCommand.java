package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.workspaces.error.DomainException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.regex.Pattern;

/**
 * The one command an operator runs on a runner's node to log its agent home in (qits-859). The
 * platform never sees or moves a login secret: the operator runs the CLI's own sign-in against the
 * node's {@code dot-claude} volume, with the workspace image the runner already pulled, and every
 * workspace on that runner shares the result.
 *
 * <pre>
 * docker run --rm -it -v &lt;dotClaudeVolume&gt;:/claude-home -e HOME=/claude-home \
 *   -e CLAUDE_CONFIG_DIR=/claude-home &lt;registry.qits.&lt;d&gt;/qits/workspace:&lt;pin&gt;&gt; claude
 * </pre>
 *
 * (one line). It is the same {@code claude} launch the in-workspace sign-in terminal runs; Kimi's
 * ends {@code kimi login} instead. The image is {@link WorkspaceRunnerAddresses#workspaceImage},
 * the one a {@code take} and an {@code estate} name.
 *
 * <p><b>Null until the volume is known</b> — the runner reports it in its {@code inventory} — and
 * null for a volume name docker would not accept: the command is pasted into a shell by a person,
 * so a name the runner reported is spliced in only when it is a plain docker volume name. A
 * deployment with no public domain has no image to name, and answers null too.
 */
@ApplicationScoped
public class RunnerLoginCommand {

  /** Where the node's agent home is mounted, as the runner's login probe mounts it. */
  static final String HOME = "/claude-home";

  /** A docker volume name: what a runner may report and still be spliced into a shell line. */
  private static final Pattern VOLUME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}");

  @Inject WorkspaceRunnerAddresses addresses;

  /** The Claude login command for {@code dotClaudeVolume}, or null. */
  public String claude(String dotClaudeVolume) {
    return compose(dotClaudeVolume, "claude");
  }

  /** The Kimi login command for {@code dotClaudeVolume}, or null. */
  public String kimi(String dotClaudeVolume) {
    return compose(dotClaudeVolume, "kimi login");
  }

  private String compose(String volume, String command) {
    if (!splicable(volume)) {
      return null;
    }
    String image;
    try {
      image = addresses.workspaceImage();
    } catch (DomainException unconfigured) {
      return null;
    }
    return command(volume, image, command);
  }

  /** Whether {@code volume} is a plain docker volume name, safe to splice into a shell line. */
  static boolean splicable(String volume) {
    return volume != null && VOLUME.matcher(volume).matches();
  }

  /** The command's one shape, for {@code volume}, {@code image} and the CLI's own words. */
  static String command(String volume, String image, String command) {
    return "docker run --rm -it -v "
        + volume
        + ":"
        + HOME
        + " -e HOME="
        + HOME
        + " -e CLAUDE_CONFIG_DIR="
        + HOME
        + " "
        + image
        + " "
        + command;
  }
}
