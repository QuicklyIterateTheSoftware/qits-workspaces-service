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

  @State(ProviderStates.NO_WORK_ITEM_HAS_AN_OPEN_WORKSPACE)
  Map<String, String> noWorkItemHasAnOpenWorkspace() {
    return states.params(ProviderStates.NO_WORK_ITEM_HAS_AN_OPEN_WORKSPACE);
  }

  @State(ProviderStates.A_TICKET_TO_DISPATCH)
  Map<String, String> aTicketToDispatch() {
    return states.params(ProviderStates.A_TICKET_TO_DISPATCH);
  }

  @State(ProviderStates.AGENTS_BOUND_TO_WORK_ITEMS)
  Map<String, String> agentsBoundToWorkItems() {
    return states.params(ProviderStates.AGENTS_BOUND_TO_WORK_ITEMS);
  }

  @State(ProviderStates.AN_AGENT_ON_A_TICKET)
  Map<String, String> anAgentOnATicket() {
    return states.params(ProviderStates.AN_AGENT_ON_A_TICKET);
  }

  @State(ProviderStates.AN_AGENT_ON_A_RELEASED_BRANCH)
  Map<String, String> anAgentOnAReleasedBranch() {
    return states.params(ProviderStates.AN_AGENT_ON_A_RELEASED_BRANCH);
  }

  @State(ProviderStates.REPOSITORIES_WITH_MERGED_BRANCHES)
  Map<String, String> repositoriesWithMergedBranches() {
    return states.params(ProviderStates.REPOSITORIES_WITH_MERGED_BRANCHES);
  }

  @State(ProviderStates.A_CONFIGURED_WORKSPACE_IMAGE)
  Map<String, String> aConfiguredWorkspaceImage() {
    return states.params(ProviderStates.A_CONFIGURED_WORKSPACE_IMAGE);
  }

  @State(ProviderStates.NO_RUNNERS)
  Map<String, String> noRunners() {
    return states.params(ProviderStates.NO_RUNNERS);
  }

  @State(ProviderStates.AN_UNREGISTERED_RUNNER)
  Map<String, String> anUnregisteredRunner() {
    return states.params(ProviderStates.AN_UNREGISTERED_RUNNER);
  }

  @State(ProviderStates.A_CONNECTED_RUNNER)
  Map<String, String> aConnectedRunner() {
    return states.params(ProviderStates.A_CONNECTED_RUNNER);
  }

  @State(ProviderStates.A_QUARANTINED_RUNNER)
  Map<String, String> aQuarantinedRunner() {
    return states.params(ProviderStates.A_QUARANTINED_RUNNER);
  }

  @State(ProviderStates.A_PROJECT_WITH_WORKSPACE_SLOTS)
  Map<String, String> aProjectWithWorkspaceSlots() {
    return states.params(ProviderStates.A_PROJECT_WITH_WORKSPACE_SLOTS);
  }

  @State(ProviderStates.A_WORKSPACE_SLOT_HOSTING_AGENTS)
  Map<String, String> aWorkspaceSlotHostingAgents() {
    return states.params(ProviderStates.A_WORKSPACE_SLOT_HOSTING_AGENTS);
  }

  @State(ProviderStates.AN_AGENT_WHOSE_WORKSPACE_IS_STARTING)
  Map<String, String> anAgentWhoseWorkspaceIsStarting() {
    return states.params(ProviderStates.AN_AGENT_WHOSE_WORKSPACE_IS_STARTING);
  }

  @State(ProviderStates.AN_AGENT_WITH_A_SAVED_PROMPT_DRAFT)
  Map<String, String> anAgentWithASavedPromptDraft() {
    return states.params(ProviderStates.AN_AGENT_WITH_A_SAVED_PROMPT_DRAFT);
  }

  @State(ProviderStates.AN_AGENT_WITH_NO_PROMPT_DRAFT)
  Map<String, String> anAgentWithNoPromptDraft() {
    return states.params(ProviderStates.AN_AGENT_WITH_NO_PROMPT_DRAFT);
  }

  @State(ProviderStates.NO_AGENT_WITH_THAT_ID)
  Map<String, String> noAgentWithThatId() {
    return states.params(ProviderStates.NO_AGENT_WITH_THAT_ID);
  }

  @State(ProviderStates.AN_AGENT_WAITING_FOR_A_SLOT)
  Map<String, String> anAgentWaitingForASlot() {
    return states.params(ProviderStates.AN_AGENT_WAITING_FOR_A_SLOT);
  }

  @State(ProviderStates.AN_AGENT_WHOSE_DAEMON_IS_NOT_CONNECTED)
  Map<String, String> anAgentWhoseDaemonIsNotConnected() {
    return states.params(ProviderStates.AN_AGENT_WHOSE_DAEMON_IS_NOT_CONNECTED);
  }

  @State(ProviderStates.AN_AGENT_WITH_LEFTOVER_WORK)
  Map<String, String> anAgentWithLeftoverWork() {
    return states.params(ProviderStates.AN_AGENT_WITH_LEFTOVER_WORK);
  }

  @State(ProviderStates.A_RESOLVED_SLOT_WITH_AN_ARCHIVED_SESSION)
  Map<String, String> aResolvedSlotWithAnArchivedSession() {
    return states.params(ProviderStates.A_RESOLVED_SLOT_WITH_AN_ARCHIVED_SESSION);
  }

  @State(ProviderStates.A_RUNNER_IN_SERVICE)
  Map<String, String> aRunnerInService() {
    return states.params(ProviderStates.A_RUNNER_IN_SERVICE);
  }

  @State(ProviderStates.A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE)
  Map<String, String> aSlotRunningOnARunnerInService() {
    return states.params(ProviderStates.A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE);
  }

  @State(ProviderStates.A_STOPPED_SLOT_ON_A_RUNNER_IN_SERVICE)
  Map<String, String> aStoppedSlotOnARunnerInService() {
    return states.params(ProviderStates.A_STOPPED_SLOT_ON_A_RUNNER_IN_SERVICE);
  }

  @State(ProviderStates.A_SLOT_ON_A_DISCONNECTED_RUNNER)
  Map<String, String> aSlotOnADisconnectedRunner() {
    return states.params(ProviderStates.A_SLOT_ON_A_DISCONNECTED_RUNNER);
  }
}
