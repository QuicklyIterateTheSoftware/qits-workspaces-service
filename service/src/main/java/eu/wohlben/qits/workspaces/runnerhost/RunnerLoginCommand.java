package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.workspaces.control.WorkspaceContainerFactory;
import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto;
import eu.wohlben.qits.workspaces.error.DomainException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one command an operator runs on a runner's node to log its agent home in (qits-859). The
 * platform never sees or moves a login secret: the operator runs the CLI's own sign-in against the
 * node's {@code dot-claude} volume, with the workspace image the runner already pulled, and every
 * workspace on that runner shares the result.
 *
 * <pre>
 * docker run --rm -it --user 1000 --entrypoint claude \
 *   -v &lt;dotClaudeVolume&gt;:&lt;claudeMount&gt; -e HOME=&lt;claudeMount&gt; \
 *   -e CLAUDE_CONFIG_DIR=&lt;claudeMount&gt;/.claude \
 *   &lt;registry.qits.&lt;d&gt;/qits/workspace:&lt;pin&gt;&gt;
 * </pre>
 *
 * (one line). The agent home is laid out EXACTLY as a workspace container has it (qits-945) —
 * literally so, rather than by two copies agreeing: {@code claudeMount}, {@code HOME} and
 * {@code CLAUDE_CONFIG_DIR}/{@code KIMI_CODE_HOME} are all read off the injected {@link
 * WorkspaceContainerFactory} at the moment the command is composed, {@link
 * WorkspaceContainerFactory#claudeMount()} and {@link WorkspaceContainerFactory#homeEnv} — the same
 * two methods {@code RunnerWorkspaceSpecs} composes a RUNNER container's own environment and mount
 * from. A login with {@code CLAUDE_CONFIG_DIR=/claude-home} while a deployment's mount was
 * {@code /claude-home} too — what shipped first, as a literal copied beside the real one — wrote
 * its credentials one directory above where every workspace reads them, so the runner's probe
 * (which read the same wrong place) said PRESENT while every launch answered {@code
 * not-signed-in}. Two copies of one value is what let that drift; there is now one.
 *
 * <p>The workspace image's ENTRYPOINT is the Java workspace daemon, not a shell, so the launch
 * overrides it with {@code --entrypoint claude} rather than naming {@code claude} as an
 * argument — the mistake that shipped live 2026-10-05, which started the daemon with "claude" on
 * its command line and left the operator staring at Java logs instead of a sign-in prompt.
 * {@code --user 1000} matters for the same reason the image's lack of a {@code USER} does: the
 * image runs as root by default, and a root-owned login would be unreadable by the uid-1000
 * workspace containers that are meant to share it. Kimi's is the same shape with
 * {@code --entrypoint kimi}, {@code KIMI_CODE_HOME} in place of {@code CLAUDE_CONFIG_DIR} (its
 * value {@code /claude-home/.kimi-code}, beside {@code .claude}, the mount point qits-coding-agents'
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

  /** A docker volume name: what a runner may report and still be spliced into a shell line. */
  private static final Pattern VOLUME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}");

  @Inject WorkspaceRunnerAddresses addresses;

  @Inject WorkspaceContainerFactory factory;

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
        dotClaudeVolume, "claude", "CLAUDE_CONFIG_DIR", null, login, connectedSince, registeredAt);
  }

  /** The Kimi login command for {@code dotClaudeVolume}, under the same conditions. */
  public String kimi(
      String dotClaudeVolume,
      WorkspaceRunnerDto.Login login,
      Instant connectedSince,
      Instant registeredAt) {
    return compose(
        dotClaudeVolume, "kimi", "KIMI_CODE_HOME", "login", login, connectedSince, registeredAt);
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
    // The agent home as this deployment's own workspace containers have it, never a second
    // literal: the mount point is the factory's, and CLAUDE_CONFIG_DIR/KIMI_CODE_HOME are read off
    // the same homeEnv a RUNNER container's own spec is composed with (RunnerWorkspaceSpecs).
    Map<String, String> home = new LinkedHashMap<>();
    factory.homeEnv(true, false, false, home::put);
    return command(volume, image, entrypoint, envKey, home.get(envKey), factory.claudeMount(), trailingArg);
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
   * The command's one shape, for {@code volume}, {@code image}, the overriding {@code entrypoint},
   * the harness's own agent-home env var ({@code envKey}={@code envValue}), and {@code home} — the
   * mount target and {@code HOME}, both the caller's to supply so this method carries no literal of
   * its own. {@code trailingArg} is appended after {@code image} when present (Kimi's {@code
   * login}); null for Claude, whose entrypoint override needs no argument.
   */
  static String command(
      String volume, String image, String entrypoint, String envKey, String envValue, String home,
      String trailingArg) {
    String base =
        "docker run --rm -it --user 1000 --entrypoint "
            + entrypoint
            + " -v "
            + volume
            + ":"
            + home
            + " -e HOME="
            + home
            + " -e "
            + envKey
            + "="
            + envValue
            + " "
            + image;
    return trailingArg == null ? base : base + " " + trailingArg;
  }
}
