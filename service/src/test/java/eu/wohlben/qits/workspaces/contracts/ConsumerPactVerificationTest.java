package eu.wohlben.qits.workspaces.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against qits-workspaces</b> (epic qits-112), qits-projects'
 * {@code ConsumerPactVerificationTest} for this provider.
 *
 * <p>The pacts come off the test classpath ({@link ClasspathPactLoader}): a consumer publishes
 * {@code pacts/<consumer>_qits-workspaces-service.json} in a jar, and this repository pins that jar
 * as a test dependency (today: qits-landing-app's). Each interaction runs against this {@code
 * @QuarkusTest} over real HTTP as the {@code %test} dev user.
 *
 * <p>Every {@code @State} method delegates to {@link ProviderStates}. {@link #target} fails a state
 * this provider does not answer for, and an interaction without {@code comments.references.qits-call}
 * and {@code qits-trigger}.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
class ConsumerPactVerificationTest {

  /** The provider as a consumer pact names it: the repository name. */
  static final String PROVIDER = "qits-workspaces-service";

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "', which qits-workspaces does not answer for — it answers for "
                + states.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    try {
      context.verifyInteraction();
    } finally {
      states.cleanUp();
    }
  }

  @State(ProviderStates.A_PROJECT_WITH_WORKSPACES_BOUND_TO_WORK_ITEMS)
  Map<String, String> aProjectWithWorkspacesBoundToWorkItems() {
    return states.params(ProviderStates.A_PROJECT_WITH_WORKSPACES_BOUND_TO_WORK_ITEMS);
  }

  @State(ProviderStates.A_WORK_ITEM_WITH_NO_WORKSPACES)
  Map<String, String> aWorkItemWithNoWorkspaces() {
    return states.params(ProviderStates.A_WORK_ITEM_WITH_NO_WORKSPACES);
  }
}
