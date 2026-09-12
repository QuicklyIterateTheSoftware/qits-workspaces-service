package eu.wohlben.qits.workspaces.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Git refs a workspace's credential may push, in the shape of contract C1
 * (principal-bound-git-refs-plan.md): a list of exact refs ({@code refs/heads/ticket/x}) and prefix
 * patterns ending in {@code /*}.
 *
 * <p>Pure functions and no state. The rules here are the ones qits-idp applies to a commission
 * (C2), so a list this service accepts is a list the idp accepts too. A list the idp refused would
 * fall back to an unscoped commission, which is the wide answer — so the door refuses a bad list
 * first, with a 400 the caller can read.
 *
 * <p>Stored on {@link Workspace#gitRefs} as a JSON array of strings. {@code String[]} crosses the
 * mapper, so no class needs native-image registration.
 *
 * <p><b>A list never lets an agent push the repository's default branch.</b> That branch moves only
 * through a release request. So a workspace on the default branch (a main workspace) gets an empty
 * list, and an entry that covers the default branch is dropped ({@link #withoutDefaultBranch}).
 */
public final class GitRefs {

  /** Every entry starts with this. */
  public static final String HEADS = "refs/heads/";

  /** The most entries a list may carry (C2). */
  public static final int MAX_ENTRIES = 500;

  /** The longest entry (C2). */
  public static final int MAX_LENGTH = 255;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private GitRefs() {}

  /** The ref a branch is: {@code refs/heads/<branch>}. */
  public static String of(String branch) {
    return HEADS + branch;
  }

  /** The list a workspace gets when the creation stated none: its own branch, and nothing else. */
  public static List<String> defaultFor(String branch) {
    return List.of(of(branch));
  }

  /**
   * The list a workspace gets when the creation stated none, with the default-branch rule: its own
   * branch, or nothing when its branch is the repository's default branch.
   *
   * @param defaultBranch the repository's default branch; null or blank when not known, which drops
   *     nothing
   */
  public static List<String> defaultFor(String branch, String defaultBranch) {
    return withoutDefaultBranch(defaultFor(branch), defaultBranch);
  }

  /**
   * {@code refs} without every entry that lets a push to the default branch through: its exact ref,
   * and a {@code /*} pattern that covers it (the githost's matching rule, C3). Order kept.
   *
   * @param defaultBranch the repository's default branch; null or blank when not known, which drops
   *     nothing
   */
  public static List<String> withoutDefaultBranch(List<String> refs, String defaultBranch) {
    if (defaultBranch == null || defaultBranch.isBlank()) {
      return List.copyOf(refs);
    }
    String ref = of(defaultBranch);
    List<String> kept = new ArrayList<>(refs);
    kept.removeIf(entry -> covers(entry, ref));
    return List.copyOf(kept);
  }

  /** Whether {@code entry} lets a push to {@code ref} through: the same ref, or a {@code /*} over it. */
  static boolean covers(String entry, String ref) {
    if (entry.endsWith("/*")) {
      return ref.startsWith(entry.substring(0, entry.length() - 1));
    }
    return entry.equals(ref);
  }

  /**
   * Check a stated list against C1/C2 and return it unchanged.
   *
   * @throws BadRequestException naming the first entry that breaks a rule
   */
  public static List<String> validated(List<String> refs) {
    if (refs.size() > MAX_ENTRIES) {
      throw new BadRequestException(
          "gitRefs has " + refs.size() + " entries; at most " + MAX_ENTRIES + " are allowed");
    }
    Set<String> seen = new HashSet<>();
    for (String ref : refs) {
      if (ref == null || !ref.startsWith(HEADS) || ref.length() == HEADS.length()) {
        throw new BadRequestException(
            "gitRefs entry must start with " + HEADS + " and name something: " + ref);
      }
      if (ref.length() > MAX_LENGTH) {
        throw new BadRequestException(
            "gitRefs entry is longer than " + MAX_LENGTH + " characters: " + ref);
      }
      int star = ref.indexOf('*');
      if (star >= 0 && (star != ref.length() - 1 || !ref.endsWith("/*"))) {
        throw new BadRequestException(
            "gitRefs entry may use * only as a trailing /*: " + ref);
      }
      if (!seen.add(ref)) {
        throw new BadRequestException("gitRefs names an entry twice: " + ref);
      }
    }
    return List.copyOf(refs);
  }

  /**
   * The list a workspace's commission states: what the row stores, or its own branch when the row
   * predates the column — in both cases without the default branch. So a main workspace from
   * before this rule is commissioned with an empty list. Never null.
   *
   * @param defaultBranch the repository's default branch; null or blank when not known, which drops
   *     nothing
   */
  public static List<String> effective(Workspace workspace, String defaultBranch) {
    if (workspace.gitRefs != null) {
      return withoutDefaultBranch(read(workspace.gitRefs), defaultBranch);
    }
    return workspace.branch == null ? List.of() : defaultFor(workspace.branch, defaultBranch);
  }

  /** The stored form: a JSON array of strings. */
  public static String write(List<String> refs) {
    try {
      return MAPPER.writeValueAsString(refs.toArray(String[]::new));
    } catch (Exception e) {
      throw new IllegalStateException("Could not write a Git ref list", e);
    }
  }

  /** The stored form read back. */
  public static List<String> read(String stored) {
    try {
      return List.copyOf(Arrays.asList(MAPPER.readValue(stored, String[].class)));
    } catch (Exception e) {
      throw new IllegalStateException("Could not read the stored Git ref list: " + stored, e);
    }
  }

  /** {@code refs} without {@code ref}, order kept. */
  public static List<String> without(List<String> refs, String ref) {
    List<String> kept = new ArrayList<>(refs);
    kept.removeIf(ref::equals);
    return List.copyOf(kept);
  }
}
