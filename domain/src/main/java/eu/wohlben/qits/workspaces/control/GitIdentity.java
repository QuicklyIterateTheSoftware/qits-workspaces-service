package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The configurable platform identity the commits qits makes itself are attributed to — the
 * host-side integration and parent merges ({@code WorkspaceService.mergeIntoTarget}), and any commit
 * made inside a workspace container outside an agent (an action, an ad-hoc {@code git commit}). An
 * agent's harness commits as the agent instead: {@link #forAgent} (qits-1666). First-class because the identity used to live as scattered {@code qits@local}
 * literals — and, on the host-side merge path, as an ambient-{@code ~/.gitconfig} dependency that
 * failed with "Committer identity unknown" in identity-less environments. One config-backed bean
 * means an operator sets {@code qits.git.author-name}/{@code qits.git.author-email} once and every
 * commit path follows. Two delivery forms:
 *
 * <ul>
 *   <li>{@link #envMap()} — the {@code GIT_AUTHOR_*} + {@code GIT_COMMITTER_*} identity env. Set by
 *       {@link WorkspaceContainerFactory} on every workspace container (so every git process in the
 *       container inherits it regardless of cwd or {@code .git/config}) AND applied per-invocation
 *       to the host synthetic-commit calls, scoped to just that {@code git} process — identity env
 *       beats every git config level ({@code -c} included), so it is what actually guarantees
 *       attribution even against an ambient {@code GIT_AUTHOR_*} inherited from the host.
 *   <li>{@link #inlineArgs()} — the {@code -c} form of the same identity, spliced into those host
 *       commit/merge argv to keep it explicit at the call site; a belt-and-suspenders companion to
 *       the env overlay (env is the authoritative one when both are present).
 * </ul>
 */
@ApplicationScoped
public class GitIdentity {

  @ConfigProperty(name = "qits.git.author-name", defaultValue = "qits")
  String name;

  @ConfigProperty(name = "qits.git.author-email", defaultValue = "qits@local")
  String email;

  /**
   * The four identity env vars (author and committer halves, so both sides of a commit are
   * attributed), insertion-ordered so rendered argv stays deterministic.
   */
  public Map<String, String> envMap() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("GIT_AUTHOR_NAME", name);
    env.put("GIT_AUTHOR_EMAIL", email);
    env.put("GIT_COMMITTER_NAME", name);
    env.put("GIT_COMMITTER_EMAIL", email);
    return env;
  }

  /**
   * The identity env of one agent's harness: author and committer {@code agent-<id>
   * <agent-<id>@<platformDomain>>}, so a commit names the agent that made it. Several agents share
   * one workspace container, so this rides in the agent's own harness env, not the container's. A
   * blank {@code platformDomain} (no public domain configured) falls back to the domain of the
   * platform's own address.
   */
  public Map<String, String> forAgent(String agentId, String platformDomain) {
    String user = "agent-" + agentId;
    String domain =
        platformDomain == null || platformDomain.isBlank()
            ? email.substring(email.indexOf('@') + 1)
            : platformDomain;
    Map<String, String> env = new LinkedHashMap<>();
    env.put("GIT_AUTHOR_NAME", user);
    env.put("GIT_AUTHOR_EMAIL", user + "@" + domain);
    env.put("GIT_COMMITTER_NAME", user);
    env.put("GIT_COMMITTER_EMAIL", user + "@" + domain);
    return env;
  }

  /** Inline config overrides for a host git invocation: {@code -c user.email=… -c user.name=…}. */
  public List<String> inlineArgs() {
    return List.of("-c", "user.email=" + email, "-c", "user.name=" + name);
  }

  /**
   * The same identity as the gitmirror module's own record. That module is framework-free and cannot
   * read config, so it takes the pair as a value; this is the one conversion, and it is here so the
   * configured identity stays the single source both delivery forms come from.
   */
  public eu.wohlben.qits.workspaces.gitmirror.CommitIdentity forMirror() {
    return new eu.wohlben.qits.workspaces.gitmirror.CommitIdentity(name, email);
  }
}
