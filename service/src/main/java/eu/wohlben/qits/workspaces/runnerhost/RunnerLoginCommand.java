package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto;
import eu.wohlben.qits.workspaces.error.DomainException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * The one command an operator runs on a runner's node to log its agent home in (qits-859). The
 * platform never sees or moves a login secret: the operator runs the CLI's own sign-in against the
 * node's {@code dot-claude} volume, with the workspace image the runner already pulled, and every
 * workspace on that runner shares the result.
 *
 * <pre>
 * docker run --rm -it --user 1000 --entrypoint claude \
 *   -v &lt;dotClaudeVolume&gt;:/claude-home -e HOME=/claude-home -e CLAUDE_CONFIG_DIR=/claude-home \
 *   &lt;registry.qits.&lt;d&gt;/qits/workspace:&lt;pin&gt;&gt;
 * </pre>
 *
 * (one line). The workspace image's ENTRYPOINT is the Java workspace daemon, not a shell, so the
 * launch overrides it with {@code --entrypoint claude} rather than naming {@code claude} as an
 * argument — the mistake that shipped live 2026-10-05, which started the daemon with "claude" on
 * its command line and left the operator staring at Java logs instead of a sign-in prompt.
 * {@code --user 1000} matters for the same reason the image's lack of a {@code USER} does: the
 * image runs as root by default, and a root-owned login would be unreadable by the uid-1000
 * workspace containers that are meant to share it. Kimi's is the same shape with
 * {@code --entrypoint kimi}, {@code KIMI_CODE_HOME} in place of {@code CLAUDE_CONFIG_DIR} (its
 * value one segment deeper, {@code /claude-home/.kimi-code}, the mount point qits-coding-agents'
 * {@code AgentLaunchService} hands every Kimi launch), and a trailing {@code login} argument. The
 * image is {@link WorkspaceRunnerAddresses#workspaceImage}, the one a {@code take} and an
 * {@code estate} name.
 *
 * <p><b>Null until the volume is known</b> — the runner reports it in its {@code inventory} — and
 * null for a volume name docker would not accept: the command is pasted into a shell by a person,
 * so a name the runner reported is spliced in only when it is a plain docker volume name. A
 * deployment with no public domain has no image to name, and answers null too.
 *
 * <p><b>Null until the runner has proven the CURRENT pinned workspace image is on its node</b>
 * (qits-859, the race an operator hit live 2026-10-05: the login command appeared the instant the
 * volume was known, while the runner was still pulling the image, so the pasted command failed
 * with "unauthorized: client credentials required" against the node's own unauthenticated
 * docker). The only proof available without a protocol change is the runner's own login probe,
 * which runs {@code docker run … <workspaceImage> auth status} and can only answer at all once
 * that image is local — so a login that settled with at least one harness {@code PRESENT} or
 * {@code ABSENT} (never both {@code UNKNOWN}, which means the probe could not run) at or after the
 * runner's current session began is read as the image being there. "Session began" is the
 * runner's latest {@code connectedSince}, or its registration when it holds no socket right now.
 */
@ApplicationScoped
public class RunnerLoginCommand {

  /** Where the node's agent home is mounted, as the runner's login probe mounts it. */
  static final String HOME = "/claude-home";

  /** A docker volume name: what a runner may report and still be spliced into a shell line. */
  private static final Pattern VOLUME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}");

  /**
   * Where Kimi's agent home is mounted beneath {@link #HOME}, as {@code AgentLaunchService}
   * mounts it.
   */
  private static final String KIMI_CODE_HOME = HOME + "/.kimi-code";

  @Inject WorkspaceRunnerAddresses addresses;

  /**
   * The Claude login command for {@code dotClaudeVolume}, or null while it is unknown, the login
   * has not yet proven the current image is on the node, or the deployment has no public domain.
   */
  public String claude(
      String dotClaudeVolume,
      WorkspaceRunnerDto.Login login,
      Instant connectedSince,
      Instant registeredAt) {
    return compose(
        dotClaudeVolume, "claude", "CLAUDE_CONFIG_DIR", HOME, null, login, connectedSince,
        registeredAt);
  }

  /** The Kimi login command for {@code dotClaudeVolume}, under the same conditions. */
  public String kimi(
      String dotClaudeVolume,
      WorkspaceRunnerDto.Login login,
      Instant connectedSince,
      Instant registeredAt) {
    return compose(
        dotClaudeVolume, "kimi", "KIMI_CODE_HOME", KIMI_CODE_HOME, "login", login, connectedSince,
        registeredAt);
  }

  /**
   * Whether the commands are withheld for a reported volume: known, but not yet proven. False when
   * the volume is unknown — there is nothing to wait for, only something not yet reported.
   */
  public boolean pending(
      String dotClaudeVolume, WorkspaceRunnerDto.Login login, Instant connectedSince,
      Instant registeredAt) {
    return splicable(dotClaudeVolume) && !proven(login, connectedSince, registeredAt);
  }

  private String compose(
      String volume,
      String entrypoint,
      String envKey,
      String envValue,
      String trailingArg,
      WorkspaceRunnerDto.Login login,
      Instant connectedSince,
      Instant registeredAt) {
    if (!splicable(volume) || !proven(login, connectedSince, registeredAt)) {
      return null;
    }
    String image;
    try {
      image = addresses.workspaceImage();
    } catch (DomainException unconfigured) {
      return null;
    }
    return command(volume, image, entrypoint, envKey, envValue, trailingArg);
  }

  /**
   * Whether {@code login} shows the runner successfully probed the CURRENT image: at least one
   * harness answered {@code PRESENT} or {@code ABSENT} (both {@code UNKNOWN} means the probe could
   * not run at all — no proof), checked at or after whichever of {@code connectedSince} /
   * {@code registeredAt} is known to describe the runner's current session.
   */
  static boolean proven(WorkspaceRunnerDto.Login login, Instant connectedSince, Instant registeredAt) {
    if (login == null || login.checkedAt() == null) {
      return false;
    }
    if (!answered(login.claude()) && !answered(login.kimi())) {
      return false;
    }
    Instant since = connectedSince != null ? connectedSince : registeredAt;
    return since != null && !login.checkedAt().isBefore(since);
  }

  private static boolean answered(String harness) {
    return "PRESENT".equals(harness) || "ABSENT".equals(harness);
  }

  /** Whether {@code volume} is a plain docker volume name, safe to splice into a shell line. */
  static boolean splicable(String volume) {
    return volume != null && VOLUME.matcher(volume).matches();
  }

  /**
   * The command's one shape, for {@code volume}, {@code image}, the overriding {@code entrypoint}
   * and the harness's own agent-home env var ({@code envKey}={@code envValue}). {@code trailingArg}
   * is appended after {@code image} when present (Kimi's {@code login}); null for Claude, whose
   * entrypoint override needs no argument.
   */
  static String command(
      String volume, String image, String entrypoint, String envKey, String envValue,
      String trailingArg) {
    String base =
        "docker run --rm -it --user 1000 --entrypoint "
            + entrypoint
            + " -v "
            + volume
            + ":"
            + HOME
            + " -e HOME="
            + HOME
            + " -e "
            + envKey
            + "="
            + envValue
            + " "
            + image;
    return trailingArg == null ? base : base + " " + trailingArg;
  }
}
