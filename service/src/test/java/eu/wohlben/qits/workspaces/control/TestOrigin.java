package eu.wohlben.qits.workspaces.control;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;

/**
 * Builds a bare origin for a synthetic repository, in the layout this context expects: {@code
 * <dataDir>/<repoId>/origin}.
 *
 * <p>Replaces the monorepo's {@code RepositoryService.cloneRepository(...)} against the {@code
 * /fixtures/testing-repo.git} bare, which is derived from git submodules by an antrun step at build
 * time. Neither the service nor the submodules exist here, and reproducing them would drag the
 * repositories context and a fixture-derivation build step into a repo whose whole point is not to
 * need them. Two commits on {@code master} plus a diverging {@code feature} branch is everything the
 * workspace tests actually assert against.
 *
 * <p>Pair it with {@link FakeRepositoryLookup#register}, which is what makes the id resolvable.
 */
public final class TestOrigin {

  private TestOrigin() {}

  /**
   * Create a bare origin under {@code dataDir} and return its generated repository id.
   *
   * <p>The result has: {@code master} with two commits; {@code feature}, forked from the first
   * commit and carrying a commit {@code master} does not have, so the two tips are distinguishable
   * and a merge between them is a real merge.
   */
  public static String create(String dataDir) throws Exception {
    return create(dataDir, true);
  }

  /**
   * As {@link #create(String)}, but {@code withFeatureBranch=false} leaves {@code master} alone in
   * the ref namespace.
   *
   * <p>A branch literally named {@code feature} blocks every {@code refs/heads/feature/*}, because
   * git's ref namespace is filesystem-like — so the two shapes of a generated branch name
   * ({@code feature/<ts>} vs. the {@code feature-<ts>} fallback) need two different origins to
   * exercise. The monorepo got the second shape from a second fixture bare
   * ({@code testing-repo-quarkus-angular.git}); here it is a flag.
   */
  public static String create(String dataDir, boolean withFeatureBranch) throws Exception {
    return create(dataDir, UUID.randomUUID().toString(), withFeatureBranch);
  }

  /**
   * As {@link #create(String, boolean)}, under a repository id the caller chooses. An origin already
   * there under that id is replaced. The contract states use it: their ids are frozen.
   */
  public static String create(String dataDir, String repoId, boolean withFeatureBranch)
      throws Exception {
    deleteRecursively(Path.of(dataDir, repoId).toAbsolutePath());
    // Absolute: dataDir is relative in tests (target/...), and `git remote add`
    // would otherwise resolve it against the throwaway work dir, not the module.
    Path origin = Path.of(dataDir, repoId, "origin").toAbsolutePath();
    Files.createDirectories(origin.getParent());
    Path work = Files.createTempDirectory("qits-test-origin-");
    try {
      run(origin.getParent().toFile(), "git", "init", "--bare", "-b", "master", origin.toString());
      // Advertise push options, which the production git host does (JGit's
      // ReceivePack.setAllowPushOptions, on both the advertisement and the receive) and a local
      // `git receive-pack` does NOT by default. The integrate flow's push carries
      // `--push-option=qits.release` — the sanctioned door through the protected ref — and a client
      // only sends one if the server offered the capability: without this line the fixture would
      // refuse the exact argv that ships, with "the receiving end does not support push options".
      run(origin.toFile(), "git", "config", "receive.advertisePushOptions", "true");

      run(work.toFile(), "git", "init", "-b", "master");
      run(work.toFile(), "git", "remote", "add", "origin", origin.toString());
      commit(work, "README.md", "# test repo\n", "initial commit");
      run(work.toFile(), "git", "push", "-q", "origin", "master");

      if (withFeatureBranch) {
        // feature forks HERE, so it lacks master's second commit and vice versa.
        run(work.toFile(), "git", "switch", "-q", "-c", "feature");
        commit(work, "feature.txt", "feature side\n", "feature commit");
        run(work.toFile(), "git", "push", "-q", "origin", "feature");
        run(work.toFile(), "git", "switch", "-q", "master");
      }

      commit(work, "master.txt", "master side\n", "second master commit");
      run(work.toFile(), "git", "push", "-q", "origin", "master");
      return repoId;
    } finally {
      deleteRecursively(work);
    }
  }

  /**
   * Commit a file onto an existing branch of an already-created origin, through a throwaway clone.
   *
   * <p>A clone rather than a worktree inside the bare, deliberately: the integrate flow adds and
   * removes its own worktrees there, and a fixture that left one registered would hand the next
   * integrate exactly the stale-worktree bug that flow exists to fix.
   */
  public static void commitOnBranch(
      String dataDir, String repoId, String branch, String file, String content, String message)
      throws Exception {
    Path origin = Path.of(dataDir, repoId, "origin").toAbsolutePath();
    Path work = Files.createTempDirectory("qits-test-commit-");
    try {
      run(work.getParent().toFile(), "git", "clone", "-q", origin.toString(), work.toString());
      run(work.toFile(), "git", "switch", "-q", branch);
      commit(work, file, content, message);
      run(work.toFile(), "git", "push", "-q", "origin", branch);
    } finally {
      deleteRecursively(work);
    }
  }

  private static void commit(Path work, String file, String content, String message)
      throws Exception {
    Path target = work.resolve(file);
    // Nested on purpose: `.config/qits/deployments.yml` is a fixture this suite writes, and a
    // fixture helper that only handled a top-level file would push that directory arithmetic into
    // every caller.
    Files.createDirectories(target.getParent());
    Files.writeString(target, content);
    run(work.toFile(), "git", "add", file);
    run(work.toFile(), "git", "commit", "-q", "-m", message);
  }

  /**
   * Install a {@code pre-receive} hook on the origin that appends {@code <ref> <options>} to {@code
   * push-options.log} for every ref a push moves.
   *
   * <p>This is the only way to see a {@code --push-option} from outside the pushing process, and it
   * is what makes an assertion about which push options a ref arrived under an assertion about the
   * argv that ships rather than about a field. Real receive-pack, real hook, real environment
   * variables — the same ones the git host's own hook reads.
   *
   * <p>Install it before the push under test; it records every push after that, workspace branch
   * creates included, so read it back by ref.
   */
  public static void recordPushOptions(String dataDir, String repoId) throws Exception {
    Path origin = Path.of(dataDir, repoId, "origin").toAbsolutePath();
    Path log = origin.resolve("push-options.log");
    Path hook = origin.resolve("hooks").resolve("pre-receive");
    Files.createDirectories(hook.getParent());
    Files.writeString(
        hook,
        """
        #!/bin/sh
        opts=""
        i=0
        while [ "$i" -lt "${GIT_PUSH_OPTION_COUNT:-0}" ]; do
          eval "value=\\$GIT_PUSH_OPTION_$i"
          opts="$opts $value"
          i=$((i + 1))
        done
        while read -r old new ref; do
          echo "$ref$opts" >> LOG
        done
        exit 0
        """
            .replace("LOG", log.toString()));
    Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  /**
   * The push options one ref was moved with, or null when no recorded push moved it. Empty when it
   * was pushed with none.
   */
  public static List<String> pushOptionsFor(String dataDir, String repoId, String ref)
      throws Exception {
    Path log = Path.of(dataDir, repoId, "origin", "push-options.log").toAbsolutePath();
    if (!Files.exists(log)) {
      return null;
    }
    for (String line : Files.readAllLines(log)) {
      String[] words = line.trim().split("\\s+");
      if (words.length > 0 && words[0].equals(ref)) {
        return List.of(words).subList(1, words.length);
      }
    }
    return null;
  }

  /** Read one file directly from a branch in the bare fixture. */
  public static String fileAtBranch(String dataDir, String repoId, String branch, String file)
      throws Exception {
    Path origin = Path.of(dataDir, repoId, "origin").toAbsolutePath();
    Process process =
        new ProcessBuilder(
                "git", "--git-dir=" + origin, "show", branch + ":" + file)
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    if (process.waitFor() != 0) {
      throw new IllegalStateException("Could not read " + file + " from " + branch + ": " + output);
    }
    return output;
  }

  /** Run a git command, failing the test with its combined output when it exits non-zero. */
  private static void run(File cwd, String... argv) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd).redirectErrorStream(true);
    // Deterministic identity and no user/system config, so a developer's ~/.gitconfig (hooks,
    // signing, a default branch name, templates) cannot change what these fixtures look like.
    pb.environment().put("GIT_AUTHOR_NAME", "qits-test");
    pb.environment().put("GIT_AUTHOR_EMAIL", "qits-test@local");
    pb.environment().put("GIT_COMMITTER_NAME", "qits-test");
    pb.environment().put("GIT_COMMITTER_EMAIL", "qits-test@local");
    pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    pb.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new IllegalStateException("git " + String.join(" ", argv) + " failed:\n" + out);
    }
  }

  public static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                  // best effort — it is a temp dir
                }
              });
    }
  }
}
