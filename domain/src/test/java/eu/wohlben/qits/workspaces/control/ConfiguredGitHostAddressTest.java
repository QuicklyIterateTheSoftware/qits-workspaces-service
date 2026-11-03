package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The shipped git-host address composes the <b>public</b> {@code /git/<projectId>/<repoName>} route
 * — never the storage-UUID {@code /git/<repoId>} one qits-githost answers a 403 — and fails loudly
 * when a row id cannot be resolved to that pair.
 *
 * <p>A plain unit test: {@link ConfiguredGitHostAddress} is {@code @DefaultBean} and loses to {@code
 * FakeGitHostAddress} in every {@code @QuarkusTest}, so the shipped bean is only exercised by
 * building it directly and setting its collaborators.
 */
class ConfiguredGitHostAddressTest {

  private static ConfiguredGitHostAddress addressWith(String host, RepositoryLookup lookup) {
    ConfiguredGitHostAddress address = new ConfiguredGitHostAddress();
    address.gitHostUrl = host;
    address.repositories = lookup;
    return address;
  }

  /** A lookup that answers one fixed view for one id, empty otherwise. */
  private static RepositoryLookup lookup(String repoId, RepositoryLookup.RepositoryView view) {
    return id -> repoId.equals(id) ? Optional.of(view) : Optional.empty();
  }

  @Test
  void composesThePublicProjectScopedRoute() {
    ConfiguredGitHostAddress address =
        addressWith(
            "https://githost.qits.test.wohlben.eu",
            lookup(
                "row-uuid-123",
                new RepositoryLookup.RepositoryView(
                    "row-uuid-123", "qits-workspaces", "qits", "main")));

    assertEquals(
        "https://githost.qits.test.wohlben.eu/git/qits/qits-workspaces",
        address.fetchUrl("row-uuid-123"));
  }

  @Test
  void readAndWriteAddressesAreTheSame() {
    ConfiguredGitHostAddress address =
        addressWith(
            "https://githost.qits.test.wohlben.eu",
            lookup(
                "row-uuid-123",
                new RepositoryLookup.RepositoryView(
                    "row-uuid-123", "qits-workspaces", "qits", "main")));

    assertEquals(address.fetchUrl("row-uuid-123"), address.pushUrl("row-uuid-123"));
  }

  @Test
  void trimsTrailingSlashesOnTheHost() {
    ConfiguredGitHostAddress address =
        addressWith(
            "https://githost.qits.test.wohlben.eu///",
            lookup(
                "r1",
                new RepositoryLookup.RepositoryView("r1", "qits-projects", "qits", "main")));

    assertEquals(
        "https://githost.qits.test.wohlben.eu/git/qits/qits-projects", address.fetchUrl("r1"));
  }

  @Test
  void failsWhenTheRepositoryIsNotInTheRegistry() {
    ConfiguredGitHostAddress address =
        addressWith("https://githost.qits.test.wohlben.eu", id -> Optional.empty());

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> address.fetchUrl("missing"));
    assertTrue(
        thrown.getMessage().contains("missing"),
        "the failure names the unresolvable repository id");
  }

  @Test
  void failsRatherThanFallBackToTheUuidRouteWhenTheNameIsMissing() {
    // A view with no project id / name cannot be name-addressed. Falling back to /git/<repoId>
    // would only reproduce the 403, so this must throw instead.
    ConfiguredGitHostAddress address =
        addressWith(
            "https://githost.qits.test.wohlben.eu",
            lookup("r1", new RepositoryLookup.RepositoryView("r1", null, null, "main")));

    assertThrows(IllegalStateException.class, () -> address.fetchUrl("r1"));
  }
}
