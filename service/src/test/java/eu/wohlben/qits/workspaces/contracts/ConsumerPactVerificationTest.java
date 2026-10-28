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
import au.com.dius.pact.provider.junitsupport.filter.InteractionFilter;
import au.com.dius.pact.provider.junitsupport.loader.PactFilter;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against qits-workspaces</b> (epic qits-112), qits-projects'
 * {@code ConsumerPactVerificationTest} for this provider.
 *
 * <p>The pacts come off the test classpath ({@link ClasspathPactLoader}): a consumer publishes
 * {@code pacts/<consumer>_qits-workspaces-service.json} in a jar, and this repository pins that jar
 * as a test dependency (today: qits-landing-app's, qits-projects', qits-artifacts',
 * qits-orchestrator's and the qits CLI's; qits-bootstrap-cli calls this service no more). Each
 * interaction runs against this {@code @QuarkusTest} over real HTTP as the {@code %test} dev user.
 *
 * <p>Every {@code @State} method delegates to {@link ProviderStates}. {@link #target} fails a state
 * this provider does not answer for, and an interaction without {@code comments.references.qits-call}
 * and {@code qits-trigger}.
 *
 * <p><b>State-param stand-ins in the body are this run's values</b> ({@link
 * StateParamRequestBody}, qits-1230): a request body string that is exactly the golden masters'
 * {@code "{name}"} placeholder, or exactly a param's pinned example, becomes what the {@code @State}
 * returned before the request is sent. A consumer replays the recorded body as the golden masters
 * hold it ({@code createRunner}'s {@code "name": "{runnerName}"}), and pact-jvm's own {@code
 * ProviderState} generators reach only a path or a value the consumer marked. Nothing else about
 * the request, and nothing about the response or a matching rule, is touched.
 *
 * <p><b>It verifies the HTTP interactions and only those</b> (qits-950): {@link #HTTP_ONLY} keeps
 * every {@code Synchronous/HTTP} interaction (a V3 request/response one reads as that too) and
 * drops every other kind before an invocation is made of it. A consumer pact jar may carry message
 * interactions — qits-workspaces-runner-daemon's did, for the frames this service sent its
 * runners — and an {@link HttpTestTarget} cannot run one ({@code UnsupportedOperationException:
 * Only request/response interactions…}); those are a message verifier's. {@code
 * ConsumerPactVerificationFilterTest} holds both halves.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
@PactFilter(
    value = ConsumerPactVerificationTest.HTTP_ONLY,
    filter = InteractionFilter.ByInteractionType.class)
class ConsumerPactVerificationTest {

  /** The provider as a consumer pact names it: the repository name. */
  static final String PROVIDER = "qits-workspaces-service";

  /** The one interaction type this verifier runs: pact-jvm's V4 name for request/response. */
  static final String HTTP_ONLY = "Synchronous/HTTP";

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
  void consumerPactHolds(PactVerificationContext context, ClassicHttpRequest request)
      throws IOException {
    try {
      withThisRunsStateParams(context, request);
      context.verifyInteraction();
    } finally {
      states.cleanUp();
    }
  }

  /** The body's state-param stand-ins, swapped for this run's values — see the class comment. */
  private static void withThisRunsStateParams(
      PactVerificationContext context, ClassicHttpRequest request) throws IOException {
    HttpEntity entity = request.getEntity();
    if (entity == null) {
      return;
    }
    Map<String, Object> pinned = new HashMap<>();
    context.getInteraction().getProviderStates().forEach(state -> pinned.putAll(state.getParams()));
    Map<String, Object> executed = context.getExecutionContext();
    Object returned = executed == null ? null : executed.get("providerState");
    if (!(returned instanceof Map<?, ?> stateValues)) {
      return;
    }
    @SuppressWarnings("unchecked")
    Map<String, ?> values = (Map<String, ?>) stateValues;
    var rewritten = StateParamRequestBody.rewrite(EntityUtils.toByteArray(entity), pinned, values);
    if (rewritten != null) {
      request.setEntity(
          new ByteArrayEntity(
              rewritten.body(),
              entity.getContentType() == null
                  ? ContentType.APPLICATION_JSON
                  : ContentType.parse(entity.getContentType())));
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

  @State(ProviderStates.AN_AGENT_WITH_AN_OPEN_WAIT)
  Map<String, String> anAgentWithAnOpenWait() {
    return states.params(ProviderStates.AN_AGENT_WITH_AN_OPEN_WAIT);
  }

  @State(ProviderStates.AN_AGENT_WAITING_ON_A_RELEASE_REQUEST)
  Map<String, String> anAgentWaitingOnAReleaseRequest() {
    return states.params(ProviderStates.AN_AGENT_WAITING_ON_A_RELEASE_REQUEST);
  }

  @State(ProviderStates.AN_AGENT_WAITING_ON_A_CI_RUN)
  Map<String, String> anAgentWaitingOnACiRun() {
    return states.params(ProviderStates.AN_AGENT_WAITING_ON_A_CI_RUN);
  }

  @State(ProviderStates.A_PROJECT_WITH_AGENTS_NEEDING_ATTENTION)
  Map<String, String> aProjectWithAgentsNeedingAttention() {
    return states.params(ProviderStates.A_PROJECT_WITH_AGENTS_NEEDING_ATTENTION);
  }

  @State(ProviderStates.REPOSITORIES_WITH_MERGED_BRANCHES)
  Map<String, String> repositoriesWithMergedBranches() {
    return states.params(ProviderStates.REPOSITORIES_WITH_MERGED_BRANCHES);
  }

  @State(ProviderStates.A_CONFIGURED_WORKSPACE_IMAGE)
  Map<String, String> aConfiguredWorkspaceImage() {
    return states.params(ProviderStates.A_CONFIGURED_WORKSPACE_IMAGE);
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

  @State(ProviderStates.A_SLOT_RUNNING_ON_A_TARGET)
  Map<String, String> aSlotRunningOnATarget() {
    return states.params(ProviderStates.A_SLOT_RUNNING_ON_A_TARGET);
  }

  @State(ProviderStates.A_STOPPED_SLOT)
  Map<String, String> aStoppedSlot() {
    return states.params(ProviderStates.A_STOPPED_SLOT);
  }

  @State(ProviderStates.A_SLOT_QITS_SYSTEM_CANNOT_BE_ASKED_ABOUT)
  Map<String, String> aSlotQitsSystemCannotBeAskedAbout() {
    return states.params(ProviderStates.A_SLOT_QITS_SYSTEM_CANNOT_BE_ASKED_ABOUT);
  }

  @State(ProviderStates.LEGACY_A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE)
  Map<String, String> aSlotRunningOnARunnerInService() {
    return states.params(ProviderStates.LEGACY_A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE);
  }

  @State(ProviderStates.LEGACY_A_STOPPED_SLOT_ON_A_RUNNER_IN_SERVICE)
  Map<String, String> aStoppedSlotOnARunnerInService() {
    return states.params(ProviderStates.LEGACY_A_STOPPED_SLOT_ON_A_RUNNER_IN_SERVICE);
  }
}
