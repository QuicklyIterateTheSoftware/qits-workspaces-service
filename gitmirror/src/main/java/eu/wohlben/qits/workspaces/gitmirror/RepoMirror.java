package eu.wohlben.qits.workspaces.gitmirror;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One repository's local mirror, and every git operation this service performs on it.
 *
 * <p>Three kinds of call live here and the distinction is the whole design:
 *
 * <ul>
 *   <li><b>Wire reads</b> — {@link #remoteBranchSha}, {@link #remoteBranches}. {@code ls-remote}
 *       against the git host: authoritative, no objects transferred, and correct even when the
 *       mirror has never been fetched. Everything that decides whether a branch <i>exists</i> asks
 *       these, because a stale mirror answering "gone" would abandon a live workspace.
 *   <li><b>Local reads</b> — {@link #isAncestor}, {@link #aheadBehind}, {@link #previewMerge}. They
 *       need objects, so they run in the mirror and the caller refreshes it first. A slightly stale
 *       ahead/behind is a slightly stale number on a screen; that is the whole exposure.
 *   <li><b>Writes</b> — {@link #push} and the worktree's own push. There is no other kind. Nothing
 *       in this class writes a ref anywhere but through receive-pack, which is the property the
 *       whole module exists to establish.
 * </ul>
 */
public final class RepoMirror {

  /**
   * The header a push carries so the git host can publish its SCM events under the cause that made
   * the push happen — a release, an arriving domain event, whatever the caller was running because
   * of.
   *
   * <p><b>Spelled as a literal here rather than imported.</b> The name belongs to {@code
   * eu.wohlben.qits.eventstream.CausationHeader}, and this module has no Quarkus in it at all — the
   * property the module exists to keep. A test in the deployable asserts the two strings are equal,
   * so the duplication cannot drift unnoticed.
   */
  public static final String CAUSATION_HEADER = "X-Qits-Causation-Id";

  private final GitCli cli;
  private final GitRemotes remotes;
  private final String repoId;
  private final Path gitDir;
  private final Path worktreeRoot;
  private final Duration networkTimeout;
  private final Duration freshness;
  private final Supplier<String> causationId;

  private final ReentrantLock fetchLock = new ReentrantLock();
  private volatile long fetchedAtMillis = 0L;

  RepoMirror(
      GitCli cli,
      GitRemotes remotes,
      String repoId,
      Path root,
      Duration networkTimeout,
      Duration freshness,
      Supplier<String> causationId) {
    this.cli = cli;
    this.remotes = remotes;
    this.repoId = repoId;
    this.gitDir = root.resolve("mirrors").resolve(repoId + ".git");
    this.worktreeRoot = root.resolve("worktrees").resolve(repoId);
    this.networkTimeout = networkTimeout;
    this.freshness = freshness;
    this.causationId = causationId == null ? () -> null : causationId;
  }

  public String repoId() {
    return repoId;
  }

  /** The mirror's bare git directory. */
  public Path gitDir() {
    return gitDir;
  }

  // -----------------------------------------------------------------------------------------
  // the mirror's lifecycle
  // -----------------------------------------------------------------------------------------

  /** Fetch when the mirror is older than the freshness window; clone it first if it is absent. */
  public void refresh() {
    if (Files.isDirectory(gitDir)
        && System.currentTimeMillis() - fetchedAtMillis < freshness.toMillis()) {
      return;
    }
    refreshNow();
  }

  /**
   * Fetch unconditionally, cloning first if the mirror is absent — what every flow that is about to
   * <em>write</em> calls, because a preflight against a stale object store is a preflight against
   * the wrong repository.
   */
  public void refreshNow() {
    fetchLock.lock();
    try {
      if (!Files.isDirectory(gitDir)) {
        cloneMirror();
      } else {
        fetch();
      }
      fetchedAtMillis = System.currentTimeMillis();
    } finally {
      fetchLock.unlock();
    }
  }

  /**
   * Mark the mirror stale, so the next {@link #refresh()} fetches whatever the freshness window
   * would otherwise have let it skip. Called after every accepted push: the git host has just moved
   * a ref this mirror cannot know about.
   */
  public void markStale() {
    fetchedAtMillis = 0L;
  }

  private void cloneMirror() {
    try {
      Files.createDirectories(gitDir.getParent());
    } catch (IOException e) {
      throw new GitMirrorException("Could not create the mirror directory " + gitDir.getParent(), e);
    }
    // --mirror rather than --bare: it sets refs/*:refs/* as the fetch refspec, so one `git fetch
    // --prune` below keeps branches AND tags identical to the host's. Tags matter — the release
    // flow's uniqueness check is "does this tag already exist", and it is only true of a mirror
    // that has them.
    GitCli.Result result =
        wire("Could not clone the mirror of " + repoId, null, "git", "clone", "--mirror", "--quiet",
            remotes.fetchUrl(repoId), gitDir.toString());
    if (result.exitCode() != 0) {
      // A half-written directory would make the next attempt take the fetch branch and fail
      // differently, which is a worse error than this one.
      deleteQuietly(gitDir);
      throw new GitMirrorException(
          "Could not clone the mirror of " + repoId + ": " + result.output());
    }
  }

  private void fetch() {
    GitCli.Result result =
        wire(
            "Could not fetch the mirror of " + repoId,
            gitDir,
            "git",
            "fetch",
            "--prune",
            "--quiet",
            remotes.fetchUrl(repoId),
            "+refs/*:refs/*");
    if (result.exitCode() != 0) {
      throw new GitMirrorException(
          "Could not fetch the mirror of " + repoId + ": " + result.output());
    }
  }

  // -----------------------------------------------------------------------------------------
  // wire reads
  // -----------------------------------------------------------------------------------------

  /**
   * The sha the git host currently holds for a branch, or empty when it has no such branch.
   *
   * <p>{@code ls-remote} rather than a mirror read on purpose. This is what "does the branch still
   * exist" is decided by, and that decision abandons a workspace when the answer is no — so it must
   * come from the repository of record and never from a cache that may be one fetch behind.
   */
  public Optional<String> remoteBranchSha(String branch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      return Optional.empty();
    }
    GitCli.Result result =
        wire(
            "Could not read " + branch + " of " + repoId,
            null,
            "git",
            "ls-remote",
            "--heads",
            "--end-of-options",
            remotes.fetchUrl(repoId),
            "refs/heads/" + branch);
    if (result.exitCode() != 0) {
      throw new GitMirrorException(
          "Could not read '" + branch + "' of " + repoId + ": " + result.output());
    }
    return result
        .output()
        .lines()
        .map(String::trim)
        .filter(line -> line.endsWith("\trefs/heads/" + branch))
        .map(line -> line.substring(0, line.indexOf('\t')))
        .findFirst();
  }

  /** Whether the git host has this branch. */
  public boolean remoteHasBranch(String branch) {
    return remoteBranchSha(branch).isPresent();
  }

  /** Every branch the git host holds, short-named. */
  public List<String> remoteBranches() {
    GitCli.Result result =
        wire(
            "Could not list the branches of " + repoId,
            null,
            "git",
            "ls-remote",
            "--heads",
            remotes.fetchUrl(repoId));
    if (result.exitCode() != 0) {
      throw new GitMirrorException(
          "Could not list the branches of " + repoId + ": " + result.output());
    }
    List<String> branches = new ArrayList<>();
    result
        .output()
        .lines()
        .map(String::trim)
        .filter(line -> line.contains("\trefs/heads/"))
        .forEach(line -> branches.add(line.substring(line.indexOf("\trefs/heads/") + 12)));
    return branches;
  }

  // -----------------------------------------------------------------------------------------
  // local reads — the caller refreshes first
  // -----------------------------------------------------------------------------------------

  /** Resolve a revision in the mirror, or empty when it names nothing there. */
  public Optional<String> resolve(String rev) {
    GitCli.Result result =
        local("git", "rev-parse", "--verify", "--quiet", "--end-of-options", rev);
    return result.exitCode() == 0 && !result.output().isBlank()
        ? Optional.of(result.output().trim())
        : Optional.empty();
  }

  /** Whether {@code ancestor} is already reachable from {@code descendant}. */
  public boolean isAncestor(String ancestor, String descendant) {
    return local("git", "merge-base", "--is-ancestor", "--end-of-options", ancestor, descendant)
            .exitCode()
        == 0;
  }

  /**
   * How far {@code branch} is ahead of and behind {@code parent}, both named as they are in the
   * mirror. {@link AheadBehind#UNKNOWN} when git could not resolve one of them.
   */
  public AheadBehind aheadBehind(String parent, String branch) {
    // `--left-right --count A...B` prints "<behind>\t<ahead>": commits in A not B, then B not A.
    GitCli.Result result =
        local("git", "rev-list", "--left-right", "--count", parent + "..." + branch);
    if (result.exitCode() != 0) {
      return AheadBehind.UNKNOWN;
    }
    String[] parts = result.output().trim().split("\\s+");
    if (parts.length != 2) {
      return AheadBehind.UNKNOWN;
    }
    try {
      return new AheadBehind(Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
    } catch (NumberFormatException e) {
      return AheadBehind.UNKNOWN;
    }
  }

  /**
   * The real three-way merge, in the object store, with no working tree involved ({@code merge-tree
   * --write-tree}). It exits 1 to report conflicts, which is an answer rather than a failure, so the
   * usual conflict costs no worktree at all.
   */
  public MergeOutcome previewMerge(String target, String source) {
    GitCli.Result result =
        local(
            "git",
            "merge-tree",
            "--write-tree",
            "--name-only",
            "--end-of-options",
            target,
            source);
    if (result.exitCode() == 0) {
      return MergeOutcome.clean(result.output());
    }
    if (result.exitCode() == 1) {
      return MergeOutcome.conflicted(conflictedFiles(result.output()), result.output());
    }
    throw new GitMirrorException(
        "Could not preview merging '"
            + source
            + "' into '"
            + target
            + "' ["
            + result.exitCode()
            + "]: "
            + result.output());
  }

  /**
   * The conflicting paths out of a conflicted {@code merge-tree --write-tree --name-only} output:
   * the lines between the written tree OID and the blank separator before the informational
   * messages.
   */
  static List<String> conflictedFiles(String mergeTreeOutput) {
    List<String> files = new ArrayList<>();
    String[] lines = mergeTreeOutput.split("\n", -1);
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].isBlank()) {
        break;
      }
      files.add(lines[i].trim());
    }
    return files;
  }

  // -----------------------------------------------------------------------------------------
  // writes — every one of them a push
  // -----------------------------------------------------------------------------------------

  /**
   * Push from the mirror. The objects are already on the host for every refspec built out of a ref
   * the mirror fetched, so these pushes carry almost no bytes; what they carry is the git host's
   * announcement of the new refs, which is the point.
   */
  public PushOutcome push(PushSpec spec) {
    return push(gitDir, spec);
  }

  /**
   * The one place every push here is built, mirror and worktree alike, and therefore the one place
   * the causation header is attached. This service pushes to {@link GitRemotes#pushUrl} and nowhere
   * else, so there is no external remote that could pick it up by accident.
   */
  PushOutcome push(Path cwd, PushSpec spec) {
    List<String> argv = new ArrayList<>(List.of("git"));
    causeHeader().ifPresent(header -> argv.addAll(List.of("-c", header)));
    argv.addAll(List.of("push", "--porcelain"));
    spec.options().forEach(option -> argv.add("--push-option=" + option));
    if (spec.atomic()) {
      argv.add("--atomic");
    }
    argv.add(remotes.pushUrl(repoId));
    spec.refs().forEach(ref -> argv.add(ref.refspec()));
    GitCli.Result result =
        wire("The push to " + repoId + " failed", cwd, argv.toArray(String[]::new));
    if (result.exitCode() == 0) {
      markStale();
      return new PushOutcome(true, result.output());
    }
    return new PushOutcome(false, result.output());
  }

  /**
   * Create a branch at another branch's tip — the operation that used to be {@code git branch} in
   * the served bare, which fired no {@code post-receive} and so produced no CI run for any workspace
   * anyone has ever created.
   *
   * <p>Pushed by ref name rather than by sha: the mirror was just refreshed, so {@code
   * refs/heads/<from>} is the tip the host has, and naming it keeps the create honest if the two
   * ever disagree — the push is refused rather than resurrecting an old commit.
   *
   * <p>An ordinary push, carrying no push option: CI is gated only by release requests now, so
   * there is nothing left for a push to suppress.
   */
  public PushOutcome createBranch(String branch, String from) {
    return push(PushSpec.of(PushSpec.Ref.branch("refs/heads/" + from, branch)));
  }

  /** Delete a branch on the git host. */
  public PushOutcome deleteBranch(String branch) {
    return push(PushSpec.of(PushSpec.Ref.deleteBranch(branch)));
  }

  /**
   * The {@code -c http.extraHeader=…} value for this push, or empty when nothing caused it.
   *
   * <p><b>The id has to parse as a UUID or no header is sent.</b> That is not a formality: the value
   * is interpolated into an HTTP header, so anything carrying a newline would be header injection,
   * and a cause is advisory — a release must never fail because the thing that caused it could not
   * be named. Parsing is therefore the check <em>and</em> the sanitiser, and it costs nothing, since
   * every real value is {@code CausationScope.current().toString()}.
   */
  private Optional<String> causeHeader() {
    return causeHeaderFor(causationId.get());
  }

  /** {@link #causeHeader()}'s whole decision, as a function of the id, so the suite can drive it. */
  static Optional<String> causeHeaderFor(String cause) {
    if (cause == null || cause.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          "http.extraHeader=" + CAUSATION_HEADER + ": " + UUID.fromString(cause.trim()));
    } catch (IllegalArgumentException notAnId) {
      return Optional.empty();
    }
  }

  // -----------------------------------------------------------------------------------------
  // worktrees
  // -----------------------------------------------------------------------------------------

  /**
   * A <b>detached</b> worktree on the mirror, at {@code atRef}.
   *
   * <p>Detached is the safety property, and it survives the move from the bare: no branch ref exists
   * in this worktree, so nothing between here and the push can move one. What the move <i>removes</i>
   * is the hazard that made the old shape delicate — a linked worktree shares its repository's ref
   * store, and when that store was the served bare, a {@code git tag} inside the worktree landed in
   * the repository qits-artifacts serves with no push at all. Here the shared store is this cache,
   * which nothing serves.
   *
   * <p>Prune first, always. A crashed run leaves the registration behind and {@code worktree add}
   * then refuses forever — a failure that outlives the process that caused it. Prune drops the
   * registration but never the files, so a surviving directory is removed by hand and the
   * registration re-pruned.
   */
  public MirrorWorktree worktree(String name, String atRef) {
    Path path = worktreeRoot.resolve(slug(name));
    try {
      Files.createDirectories(worktreeRoot);
    } catch (IOException e) {
      throw new GitMirrorException("Could not create the worktree directory " + worktreeRoot, e);
    }
    local("git", "worktree", "prune");
    if (Files.exists(path)) {
      deleteQuietly(path);
      local("git", "worktree", "prune");
    }
    GitCli.Result added =
        local("git", "worktree", "add", "--detach", path.toString(), atRef);
    if (added.exitCode() != 0) {
      throw new GitMirrorException(
          "Could not create a worktree at '" + atRef + "' of " + repoId + ": " + added.output());
    }
    return new MirrorWorktree(this, cli, path);
  }

  /**
   * A branch name may hold a {@code /} — that is the whole point of {@code task/…} — so it cannot be
   * a directory name as it stands. Two branches could slug to one name, but only two running at once
   * would collide and the repository lease forbids that.
   */
  static String slug(String name) {
    return name.replaceAll("[^A-Za-z0-9._-]", "-");
  }

  void removeWorktree(Path path) {
    local("git", "worktree", "remove", "--force", path.toString());
    if (Files.exists(path)) {
      deleteQuietly(path);
    }
    local("git", "worktree", "prune");
  }

  /**
   * Drop a tag ref from the mirror. Cache hygiene and nothing more — the mirror is not served, so a
   * tag here names nothing to anybody, and the next fetch would prune it anyway. It exists so a run
   * that tagged and then failed to push does not refuse its own repository's next attempt at the
   * same version out of a local leftover.
   */
  public void deleteLocalTag(String tag) {
    local("git", "tag", "-d", tag);
  }

  // -----------------------------------------------------------------------------------------
  // plumbing
  // -----------------------------------------------------------------------------------------

  /** A local git call in the mirror: unbounded, because a bound would only turn slow into broken. */
  GitCli.Result local(String... argv) {
    try {
      return cli.run(gitDir.toFile(), Map.of(), null, null, argv);
    } catch (Exception e) {
      throw new GitMirrorException("git " + String.join(" ", argv) + " failed in " + gitDir, e);
    }
  }

  /** A git call that talks to the git host, and therefore carries a deadline. */
  private GitCli.Result wire(String what, Path cwd, String... argv) {
    try {
      return cli.run(
          cwd == null ? null : cwd.toFile(), Map.of(), null, networkTimeout, platformArgv(argv));
    } catch (Exception e) {
      throw new GitMirrorException(what + ": " + e.getMessage(), e);
    }
  }

  private String[] platformArgv(String... argv) {
    boolean http = java.util.Arrays.stream(argv).anyMatch(arg -> arg.startsWith("http://") || arg.startsWith("https://"));
    if (!http) {
      return argv;
    }
    String header = remotes.httpExtraHeader().orElse(null);
    if (header == null || header.isBlank()) {
      throw new GitMirrorException("No machine bearer is available for qits-githost");
    }
    if (argv.length == 0 || !"git".equals(argv[0])) {
      throw new GitMirrorException("A qits-githost command must start with git");
    }
    List<String> secured = new ArrayList<>(List.of("git", "-c", "http.extraHeader=" + header));
    secured.addAll(List.of(argv).subList(1, argv.length));
    return secured.toArray(String[]::new);
  }

  private static void deleteQuietly(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    } catch (IOException ignored) {
      // best effort — the next run prunes and retries
    }
  }
}
