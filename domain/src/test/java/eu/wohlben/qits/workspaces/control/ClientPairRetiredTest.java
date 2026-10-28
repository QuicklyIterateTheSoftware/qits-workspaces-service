package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The hourly client pair stays retired (qits-876): no container this service composes carries
 * {@code QITS_COMMISSIONED_CLIENT_ID}/{@code _SECRET}, the record that carried it is gone, and the
 * port can no longer be asked for a client.
 *
 * <p>Every container holds a non-expiring {@code QITS_TOKEN} or no credential at all, and a DIRECT
 * provision with an issuer but no edge plane is refused ({@link DirectWorkspaceTokenTest}). What is
 * deliberately kept is asserted beside what is gone: the port still lists and gives back idp
 * clients, because the commission reconcile and the retired runner's give-back use exactly that.
 *
 * <p>Plain JUnit over the golden test's fixture, so every spec asserted here is the pinned one.
 */
class ClientPairRetiredTest {

  /** The production sources, read as text: a grep is the honest test for "nobody names it". */
  private static final Path MAIN = Path.of("src/main/java");

  private static final String PAIR_PREFIX = "QITS_COMMISSIONED_CLIENT_";

  @Test
  void theCredentialRecordIsGone() {
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName("eu.wohlben.qits.workspaces.control.WorkspaceCredential"));
  }

  @Test
  void thePortMintsNoClientAndTheLookupReadsNoPair() {
    List<String> commissionerMethods =
        Arrays.stream(CredentialCommissioner.class.getMethods()).map(Method::getName).toList();
    assertFalse(commissionerMethods.contains("commission"), commissionerMethods.toString());
    // Kept on purpose: the reconcile and LegacyRunnerClients give clients back through these.
    assertEquals(
        List.of(true, true),
        List.of(commissionerMethods.contains("list"), commissionerMethods.contains("decommission")));
    List<String> lookupMethods =
        Arrays.stream(WorkspaceCredentials.class.getMethods()).map(Method::getName).toList();
    assertEquals(List.of("tokenFor"), lookupMethods);
  }

  @Test
  void noDirectSpecCarriesThePair() {
    WorkspacePostures admin = rowId -> true;
    for (boolean holdsToken : List.of(false, true)) {
      for (WorkspacePostures posture : List.of(admin, (WorkspacePostures) rowId -> false)) {
        WorkspaceContainerFactory f = WorkspaceContainerFactoryGoldenSpecTest.factory();
        f.postures = StubInstance.of(posture);
        if (holdsToken) {
          f.credentials =
              WorkspaceContainerFactoryGoldenSpecTest.tokenHolding("qits_tok_8", "tok-workspace-8");
        }
        assertNoPair(
            f.forWorkspace("repo12345678abc", "admin", 8L, "admin/recovery", "main", null).env());
      }
    }
  }

  @Test
  void noLeaseSpecCarriesThePair() {
    Workspace row = new Workspace();
    row.id = 7L;
    row.repositoryId = "repo12345678abc";
    row.workspaceId = "work";
    row.placement = WorkspacePlacement.TARGET;
    row.commissionedTokenId = "tok-id-7";
    row.commissionedTokenSubject = "tok-workspace-7";
    row.commissionedToken = "qits_tok_seven";
    RunnerWorkspaceSpecs specs = new RunnerWorkspaceSpecs();
    specs.factory = WorkspaceContainerFactoryGoldenSpecTest.factory();

    assertNoPair(
        specs
            .compose(row, WorkspaceAddressPlane.of("wohlben.eu", List.of("registry.dev.localhost:8080")))
            .env());
  }

  @Test
  void noProductionSourceNamesThePairVariables() throws IOException {
    List<String> offenders;
    try (Stream<Path> files = Files.walk(MAIN)) {
      offenders =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(ClientPairRetiredTest::namesThePair)
              .map(p -> MAIN.relativize(p).toString())
              .toList();
    }
    assertEquals(List.of(), offenders, "a production class composes the client pair again");
  }

  private static boolean namesThePair(Path file) {
    try {
      return Files.readString(file).contains(PAIR_PREFIX);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void assertNoPair(Map<String, String> env) {
    for (String key : env.keySet()) {
      assertFalse(key.startsWith(PAIR_PREFIX), key + " in " + env);
    }
  }
}
