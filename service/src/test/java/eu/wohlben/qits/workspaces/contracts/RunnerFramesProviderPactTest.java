package eu.wohlben.qits.workspaces.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.core.support.json.JsonValue;
import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.filter.InteractionFilter;
import au.com.dius.pact.provider.junitsupport.loader.PactFilter;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies the runner's message pact against the frames this service really sends</b>
 * (qits-950, A1): every {@code Asynchronous/Messages} interaction of a consumer pact against
 * qits-workspaces-service — today qits-workspaces-runner-daemon's {@code
 * pacts/qits-workspaces-runner-daemon_qits-workspaces-service.json}, in its pinned {@code
 * eu.wohlben.qits:qits-workspaces-runner-daemon-pacts-qits-workspaces-service} jar — through
 * pact-jvm's {@link MessageTestTarget}. {@link ConsumerPactVerificationTest} is the HTTP half; the
 * two filters split one loader's pacts between them.
 *
 * <p><b>Each frame is made on the wire in the state the pact names</b> — never a recorded file and
 * never JSON built here. A {@code @State} method sets the state up through {@link ProviderStates}
 * and keeps its params; the {@code @PactVerifyProvider} for the interaction's description then runs
 * {@link RunnerFrames#send} for the {@link RunnerFrames#FRAMES} row of (that state, the {@code
 * qits-call} frame): the runner's own frame handed to the registry or to {@code RunnerReservations},
 * or the door pressed over HTTP, and the text the state's scripted socket was handed — the codec's
 * JSON, byte for byte — answered. That is {@code GoldenMasterRecordingTest}'s path, minus its
 * freezing: pact-jvm matches the live frame against the interaction's rules, which were written from
 * the frozen recording.
 *
 * <p><b>It refuses before a frame is made</b> ({@link #target}): a provider state this service does
 * not answer for, an interaction without {@code comments.references.qits-call} and {@code
 * qits-trigger}, and (in {@link #produce}) a {@code qits-call} naming another app or a frame this
 * service makes in no row of that state, or a {@code qits-trigger} that is not the cause the row
 * records — a pact citing a door the frame does not come from would verify it for the wrong reason.
 * A description no {@code @PactVerifyProvider} answers is pact-jvm's own failure.
 *
 * <p><b>No message interaction at all passes</b> ({@link IgnoreNoPactsToVerify}): the runner pact
 * jar pinned before 2026.1022.101507 holds only the HTTP register pact, which the filter drops, and
 * pact-jvm then runs one placeholder invocation with a null context instead of throwing {@code
 * NoPactsFoundException}. A lost pact jar still fails: {@link ClasspathPactLoader} throws when it
 * finds no file, and {@code ignoreIoErrors} stays false.
 */
@QuarkusTest
@Provider(RunnerFramesProviderPactTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
@PactFilter(
    value = RunnerFramesProviderPactTest.MESSAGES_ONLY,
    filter = InteractionFilter.ByInteractionType.class)
@IgnoreNoPactsToVerify
class RunnerFramesProviderPactTest {

  /** The provider as a consumer pact names it: the repository name. */
  static final String PROVIDER = ConsumerPactVerificationTest.PROVIDER;

  /** The one interaction type this verifier runs: pact-jvm's V4 name for a message. */
  static final String MESSAGES_ONLY = "Asynchronous/Messages";

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  @Inject ProviderStates states;

  @Inject RunnerFrames frames;

  private Interaction interaction;
  private String state;
  private Map<String, String> params;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    if (context == null) {
      return; // No message interaction to verify: pact-jvm's placeholder invocation.
    }
    String consumer = pact.getConsumer().getName();
    for (ProviderState given : interaction.getProviderStates()) {
      if (!states.names().contains(given.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + given.getName()
                + "', which qits-workspaces does not answer for — it answers for "
                + states.names());
      }
    }
    JsonValue references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null
          || !references.isObject()
          || !references.asObject().has(key)
          || !references.asObject().get(key).isObject()) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    this.interaction = interaction;
    this.state = null;
    this.params = null;
    context.setTarget(
        new MessageTestTarget(List.of(getClass().getPackageName()), getClass().getClassLoader()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void theServiceSendsTheFrameThePactSays(PactVerificationContext context) {
    if (context == null) {
      return; // Nothing to verify, as the class comment says.
    }
    try {
      context.verifyInteraction();
    } finally {
      states.cleanUp();
    }
  }

  // --- the states, each set up by ProviderStates ----------------------------------------------

  @State(ProviderStates.A_RUNNER_DIALLING_IN_HOLDING_ITS_WORKSPACE)
  void aRunnerDiallingInHoldingItsWorkspace() {
    setUp(ProviderStates.A_RUNNER_DIALLING_IN_HOLDING_ITS_WORKSPACE);
  }

  @State(ProviderStates.A_RUNNER_DIALLING_IN_WITH_A_QUEUED_WORKSPACE)
  void aRunnerDiallingInWithAQueuedWorkspace() {
    setUp(ProviderStates.A_RUNNER_DIALLING_IN_WITH_A_QUEUED_WORKSPACE);
  }

  @State(ProviderStates.A_RUNNER_DIALLING_IN_AT_AN_OLDER_VERSION)
  void aRunnerDiallingInAtAnOlderVersion() {
    setUp(ProviderStates.A_RUNNER_DIALLING_IN_AT_AN_OLDER_VERSION);
  }

  @State(ProviderStates.A_QUARANTINED_RUNNER_DIALLING_IN)
  void aQuarantinedRunnerDiallingIn() {
    setUp(ProviderStates.A_QUARANTINED_RUNNER_DIALLING_IN);
  }

  @State(ProviderStates.A_QUARANTINED_RUNNER_HOLDING_A_SOCKET)
  void aQuarantinedRunnerHoldingASocket() {
    setUp(ProviderStates.A_QUARANTINED_RUNNER_HOLDING_A_SOCKET);
  }

  @State(ProviderStates.A_RUNNER_IN_SERVICE_WITH_A_QUEUED_WORKSPACE)
  void aRunnerInServiceWithAQueuedWorkspace() {
    setUp(ProviderStates.A_RUNNER_IN_SERVICE_WITH_A_QUEUED_WORKSPACE);
  }

  @State(ProviderStates.A_RUNNER_IN_SERVICE_WITH_EVERY_SLOT_TAKEN)
  void aRunnerInServiceWithEverySlotTaken() {
    setUp(ProviderStates.A_RUNNER_IN_SERVICE_WITH_EVERY_SLOT_TAKEN);
  }

  @State(ProviderStates.A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE)
  void aSlotRunningOnARunnerInService() {
    setUp(ProviderStates.A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE);
  }

  @State(ProviderStates.A_RUNNER_IN_SERVICE)
  void aRunnerInService() {
    setUp(ProviderStates.A_RUNNER_IN_SERVICE);
  }

  private void setUp(String name) {
    state = name;
    params = states.setUp(name).params();
  }

  // --- the frames, one per description ------------------------------------------------------

  @PactVerifyProvider("workspaces-runner:hello: frame:ack")
  MessageAndMetadata ack() {
    return produce();
  }

  @PactVerifyProvider("workspaces-runner:hello: frame:estate")
  MessageAndMetadata estate() {
    return produce();
  }

  @PactVerifyProvider("workspaces-runner:hello: frame:backlog")
  MessageAndMetadata backlog() {
    return produce();
  }

  @PactVerifyProvider("workspaces-runner:hello: frame:quarantined")
  MessageAndMetadata quarantined() {
    return produce();
  }

  @PactVerifyProvider("workspaces-runner:hello: frame:upgrade")
  MessageAndMetadata upgrade() {
    return produce();
  }

  @PactVerifyProvider("workspaces-runner:reserve: frame:take")
  MessageAndMetadata take() {
    return produce();
  }

  @PactVerifyProvider("workspaces-runner:reserve: frame:nothing")
  MessageAndMetadata nothing() {
    return produce();
  }

  @PactVerifyProvider("stopWorkspaceContainer: frame:stop")
  MessageAndMetadata stop() {
    return produce();
  }

  @PactVerifyProvider("deleteWorkspaceContainer: frame:delete")
  MessageAndMetadata delete() {
    return produce();
  }

  @PactVerifyProvider("requestRunnerLoginCheck: frame:probeLogin")
  MessageAndMetadata probeLogin() {
    return produce();
  }

  @PactVerifyProvider("requestRunnerHealthcheck: frame:healthCheck")
  MessageAndMetadata healthCheck() {
    return produce();
  }

  @PactVerifyProvider("deleteRunner: frame:retire")
  MessageAndMetadata retire() {
    return produce();
  }

  @PactVerifyProvider("greenlightRunner: frame:reinstated")
  MessageAndMetadata reinstated() {
    return produce();
  }

  /**
   * The interaction's frame, sent in the state its {@code @State} set up: the {@link
   * RunnerFrames#FRAMES} row for (state, the {@code qits-call} frame), refused unless its cause's
   * trigger is the interaction's {@code qits-trigger}.
   */
  private MessageAndMetadata produce() {
    if (state == null) {
      fail(interaction.getDescription() + ": no @State ran, so there is no state to send it in");
    }
    JsonValue.Object references = interaction.getComments().get("references").asObject();
    Map<String, String> call = strings(references.get("qits-call").asObject());
    Map<String, String> trigger = strings(references.get("qits-trigger").asObject());
    if (!PROVIDER.equals(call.get("app"))) {
      fail(interaction.getDescription() + ": qits-call names " + call + ", not " + PROVIDER);
    }
    String type = call.get("frame");
    RunnerFrames.Frame frame =
        RunnerFrames.FRAMES.stream()
            .filter(f -> f.state().equals(state) && f.type().equals(type))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        interaction.getDescription()
                            + ": qits-workspaces sends no "
                            + type
                            + " in '"
                            + state
                            + "'"));
    Map<String, String> recorded = frame.cause().trigger().reference();
    if (!recorded.equals(trigger)) {
      fail(
          interaction.getDescription()
              + " ["
              + state
              + "]: qits-trigger "
              + trigger
              + ", and qits-workspaces sends it on "
              + recorded);
    }
    String sent = frames.send(frame, params);
    return new MessageAndMetadata(
        sent.getBytes(StandardCharsets.UTF_8), Map.of("contentType", "application/json"));
  }

  /** A references group's string values, by key. */
  private static Map<String, String> strings(JsonValue.Object group) {
    Map<String, String> out = new LinkedHashMap<>();
    group.getEntries().forEach((k, v) -> out.put(k, v.isString() ? v.asString() : v.toString()));
    return out;
  }
}
