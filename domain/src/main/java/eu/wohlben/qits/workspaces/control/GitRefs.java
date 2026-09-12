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
   * predates the column. Never null for a row with a branch.
   */
  public static List<String> effective(Workspace workspace) {
    if (workspace.gitRefs != null) {
      return read(workspace.gitRefs);
    }
    return workspace.branch == null ? List.of() : defaultFor(workspace.branch);
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
