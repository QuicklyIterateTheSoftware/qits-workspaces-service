package eu.wohlben.qits.workspaces.testing.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Runs consumer code against a pact-jvm mock server that answers one pact, and fails naming what
 * went wrong. The programmatic runner, for the reasons {@code ProjectsConsumerPactTest} gives: a
 * {@code @QuarkusTest} cannot take the extension's mock-server parameter, and one mock server per
 * interaction keeps rows with the same request apart.
 */
public final class ConsumerPacts {

  private ConsumerPacts() {}

  /** A pact of this service against {@code provider}, holding what {@code interactions} adds. */
  public static V4Pact pact(GoldenMasters.Provider provider, UnaryOperator<PactBuilder> interactions) {
    return interactions
        .apply(new PactBuilder(GoldenMasters.CONSUMER, provider.repository(), PactSpecVersion.V4))
        .toPact();
  }

  /** Run {@code call} with the mock server's base url; fail unless every interaction was met. */
  public static void verify(V4Pact pact, Consumer<String> call) {
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            pact,
            MockProviderConfig.createDefault(PactSpecVersion.V4),
            (mockServer, context) -> {
              call.accept(mockServer.getUrl());
              return null;
            });
    if (!(result instanceof PactVerificationResult.Ok)) {
      String detail =
          result instanceof PactVerificationResult.Error error
              ? "error: " + error.getError()
              : result.getDescription() + " — " + result;
      fail(pact.getProvider().getName() + " pact failed: " + detail);
    }
  }
}
