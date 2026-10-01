package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import io.quarkus.test.junit.QuarkusTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-projects contract</b>: a real {@link HttpRepositoryLookup}, with
 * the real generated REST clients, pointed at a pact-jvm mock server that answers exactly what
 * {@link ProjectsContract}'s interaction for that row promises — and the row's own assertions on
 * what the lookup made of it. A row whose request the client does not make, or whose answer it
 * cannot bind, fails here; the committed pact file is checked by {@code ProjectsPactFileTest}.
 *
 * <p><b>{@code @QuarkusTest}, not plain JUnit, and that is forced rather than chosen.</b> The REST
 * client is generated at augmentation: outside a running application {@code
 * QuarkusRestClientBuilder.build} throws "The Reactive REST Client needs to be built within the
 * context of a Quarkus application with a valid ArC (CDI) context running". A hand-rolled client
 * would prove a contract for a client that does not ship. It boots the DEFAULT profile — the same
 * application {@code HttpRepositoryLookupTest} already boots — so it adds no {@code @TestProfile}
 * and no metaspace (TestProfileBudgetRules).
 *
 * <p><b>pact-jvm's programmatic runner, not {@code PactConsumerTestExt}</b>, for two reasons. The
 * extension hands its mock server in as a test-method parameter, and a {@code @QuarkusTest} method
 * runs in Quarkus' classloader, which deep-copies such arguments across. And the extension serves
 * every interaction of a pact from ONE mock server, which cannot tell apart the five rows that send
 * the identical {@code GET /projects/api/repositories/{id}} — only their trigger differs, so all but
 * the first would read as "never called". One mock server per row sidesteps both.
 */
@QuarkusTest
public class ProjectsConsumerPactTest {

  @Test
  public void everyRowOfTheContractIsWhatTheLookupAsksAndUnderstands() {
    assertFalse(ProjectsContract.CASES.isEmpty());
    List<String> failures = new ArrayList<>();
    for (ProjectsContract.Case row : ProjectsContract.CASES) {
      PactVerificationResult result =
          ConsumerPactRunnerKt.runConsumerTest(
              ProjectsContract.pact(List.of(row)),
              MockProviderConfig.createDefault(PactSpecVersion.V4),
              (mockServer, context) -> {
                row.call()
                    .run(
                        HttpRepositoryLookupTest.lookupAgainst(mockServer.getUrl()),
                        GoldenMasters.params(row.state()));
                return null;
              });
      if (!(result instanceof PactVerificationResult.Ok)) {
        failures.add(row.description() + " [" + row.state() + "]: " + describe(result));
      }
    }
    if (!failures.isEmpty()) {
      fail(failures.size() + " contract row(s) failed:\n  " + String.join("\n  ", failures));
    }
  }

  private static String describe(PactVerificationResult result) {
    if (result instanceof PactVerificationResult.Error error) {
      return "error: " + error.getError();
    }
    return result.getDescription() + " — " + result;
  }
}
