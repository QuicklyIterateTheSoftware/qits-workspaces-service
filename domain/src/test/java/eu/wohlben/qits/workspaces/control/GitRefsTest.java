package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.workspaces.error.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The C1/C2 rules for a Git ref list, offline. They are qits-idp's rules on purpose: a list this
 * service accepts and the idp then refused would fall back to an unscoped commission.
 */
class GitRefsTest {

  @Test
  void exactRefsAndTrailingPatternsAreAccepted() {
    List<String> refs = List.of("refs/heads/epic/e", "refs/heads/task/e/*", "refs/heads/x");
    assertEquals(refs, GitRefs.validated(refs));
    assertEquals(List.of(), GitRefs.validated(List.of()), "empty is 'may push nothing'");
  }

  @Test
  void everyEntryMustBeABranchRef() {
    assertThrows(BadRequestException.class, () -> GitRefs.validated(List.of("refs/tags/v1")));
    assertThrows(BadRequestException.class, () -> GitRefs.validated(List.of("epic/e")));
    assertThrows(BadRequestException.class, () -> GitRefs.validated(List.of("refs/heads/")));
    List<String> withNull = new ArrayList<>();
    withNull.add(null);
    assertThrows(BadRequestException.class, () -> GitRefs.validated(withNull));
  }

  @Test
  void aStarIsOnlyATrailingSlashStar() {
    assertThrows(BadRequestException.class, () -> GitRefs.validated(List.of("refs/heads/*x")));
    assertThrows(BadRequestException.class, () -> GitRefs.validated(List.of("refs/heads/a*")));
    assertThrows(
        BadRequestException.class, () -> GitRefs.validated(List.of("refs/heads/a/*/b")));
  }

  @Test
  void theSizeLimitsAndDuplicatesAreRefused() {
    String longRef = "refs/heads/" + "a".repeat(GitRefs.MAX_LENGTH);
    assertThrows(BadRequestException.class, () -> GitRefs.validated(List.of(longRef)));
    List<String> many = new ArrayList<>();
    for (int i = 0; i <= GitRefs.MAX_ENTRIES; i++) {
      many.add("refs/heads/b" + i);
    }
    assertThrows(BadRequestException.class, () -> GitRefs.validated(many));
    assertThrows(
        BadRequestException.class,
        () -> GitRefs.validated(List.of("refs/heads/a", "refs/heads/a")));
  }

  @Test
  void theDefaultIsTheWorkspacesOwnBranch() {
    assertEquals(List.of("refs/heads/ticket/fix-login"), GitRefs.defaultFor("ticket/fix-login"));
  }

  @Test
  void theStoredFormReadsBackAndNarrowsInOrder() {
    List<String> refs = List.of("refs/heads/epic/e", "refs/heads/task/e/a", "refs/heads/task/e/b");
    String stored = GitRefs.write(refs);
    assertEquals("[\"refs/heads/epic/e\",\"refs/heads/task/e/a\",\"refs/heads/task/e/b\"]", stored);
    assertEquals(refs, GitRefs.read(stored));
    assertEquals(
        List.of("refs/heads/epic/e", "refs/heads/task/e/b"),
        GitRefs.without(refs, "refs/heads/task/e/a"));
    assertEquals(List.of(), GitRefs.read(GitRefs.write(List.of())));
  }
}
