package eu.wohlben.qits.workspaces.contracts;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import eu.wohlben.qits.workspaces.runnerhost.RunnerReservations;
import eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerAddressesFixture;
import eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerRegistry;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>The frames qits-workspaces sends a workspace runner, each from a provider state</b>
 * (qits-950, A1): the table {@link GoldenMasterRecordingTest} records into {@code golden-masters/}
 * under the operation {@code frame:<type>}, and the runner's consumer pact is written against.
 *
 * <p><b>Every frame comes off the socket, never off a constructor.</b> Each state ({@link
 * ProviderStates}) leaves the runner {@code localhost} on a socket that keeps every text it is
 * sent; {@link #send} forgets what the setting up cost, makes the frame happen the way it happens
 * live — the runner's own frame handed to the registry or to {@link RunnerReservations} as {@code
 * WorkspaceRunnerSocket} hands it, or a door pressed over HTTP — and answers the first text of the
 * frame's type that went out. A frame of that type that never left fails the recording.
 *
 * <p><b>The trigger is the {@code qits-trigger} a message pact names</b>: an operation of this
 * service, or the runner's own frame ({@code workspaces-runner:<type>}, an event in the HTTP
 * tables' vocabulary — the spelling {@code ProjectsContract} gives a daemon's frame).
 *
 * <p>The table is {@code RunnerFramesGuardTest}'s other half: every frame the service's code
 * constructs has a row here, and every row a frame it constructs.
 */
@ApplicationScoped
public class RunnerFrames {

  /** What makes a frame go out, and the trigger a pact names for it. */
  public enum Cause {
    /** The runner's {@code hello} at the pinned version, holding the state's {@code workspaceId}. */
    HELLO(runnerFrame(RunnerWire.Type.HELLO)),
    /** The runner's {@code hello} at the state's {@code runnerVersion}, an older one. */
    HELLO_AT_AN_OLDER_VERSION(runnerFrame(RunnerWire.Type.HELLO)),
    /** The runner's {@code reserve}. */
    RESERVE(runnerFrame(RunnerWire.Type.RESERVE)),
    STOP_CONTAINER(Trigger.operation("stopWorkspaceContainer")),
    DELETE_CONTAINER(Trigger.operation("deleteWorkspaceContainer")),
    LOGIN_CHECK(Trigger.operation("requestRunnerLoginCheck")),
    HEALTH_CHECK(Trigger.operation("requestRunnerHealthcheck")),
    GREENLIGHT(Trigger.operation("greenlightRunner")),
    DELETE_RUNNER(Trigger.operation("deleteRunner"));

    private final Trigger trigger;

    Cause(Trigger trigger) {
      this.trigger = trigger;
    }

    public Trigger trigger() {
      return trigger;
    }
  }

  /**
   * One recorded frame.
   *
   * @param state the provider state it is sent in
   * @param type its wire {@code type}, the golden master's operation {@code frame:<type>}
   * @param cause what sends it
   * @param fixedStrings {@code $.a.b} paths, each with the value it is recorded as: what a version
   *     bump moves (the image tags), listed in {@code frozen.strings}
   * @param rowIdsIn {@code $.a.b} paths of strings that carry a row id as a path segment (an
   *     address), recorded with that segment frozen and the rest as it was sent; listed in {@code
   *     frozen.strings}
   */
  public record Frame(
      String state,
      String type,
      Cause cause,
      Map<String, String> fixedStrings,
      List<String> rowIdsIn) {

    Frame(String state, String type, Cause cause) {
      this(state, type, cause, Map.of(), List.of());
    }

    /** The golden master's operation key. */
    public String operationId() {
      return OPERATION_PREFIX + type;
    }

    /** The golden master's file name below its state's directory: no colon, for every checkout. */
    public String fileName() {
      return FILE_PREFIX + type + ".json";
    }

    /** This frame, with the string at {@code path} recorded as {@code value}. */
    Frame fixing(String path, String value) {
      Map<String, String> fixed = new TreeMap<>(fixedStrings);
      fixed.put(path, value);
      return new Frame(state, type, cause, fixed, rowIdsIn);
    }

    /** This frame, with the row id in the address at {@code path} frozen. */
    Frame rowIdIn(String path) {
      List<String> paths = new ArrayList<>(rowIdsIn);
      paths.add(path);
      return new Frame(state, type, cause, fixedStrings, List.copyOf(paths));
    }
  }

  /** The prefix of a frame's operation key in the golden masters' index. */
  public static final String OPERATION_PREFIX = "frame:";

  /** The prefix of a frame's golden master file. */
  static final String FILE_PREFIX = "frame-";

  /** The workspace image an estate names, at {@link GoldenMasterRecordingTest#FIXED_VERSION}. */
  static final String FIXED_WORKSPACE_IMAGE =
      "registry.qits."
          + WorkspaceRunnerAddressesFixture.DOMAIN
          + "/qits/workspace:"
          + GoldenMasterRecordingTest.FIXED_VERSION;

  /** The runner image an upgrade names, at {@link GoldenMasterRecordingTest#FIXED_VERSION}. */
  static final String FIXED_RUNNER_IMAGE =
      "registry.qits."
          + WorkspaceRunnerAddressesFixture.DOMAIN
          + "/"
          + WorkspacesRunnerProtocol.IMAGE_REPOSITORY
          + ":"
          + GoldenMasterRecordingTest.FIXED_VERSION;

  public static final List<Frame> FRAMES =
      List.of(
          // A greeting, in order: ack, estate (a successful read only), backlog.
          new Frame(ProviderStates.A_RUNNER_DIALLING_IN_HOLDING_ITS_WORKSPACE, "ack", Cause.HELLO),
          new Frame(ProviderStates.A_RUNNER_DIALLING_IN_HOLDING_ITS_WORKSPACE, "estate", Cause.HELLO)
              .fixing("$.workspaceImage", FIXED_WORKSPACE_IMAGE),
          new Frame(
              ProviderStates.A_RUNNER_DIALLING_IN_WITH_A_QUEUED_WORKSPACE, "backlog", Cause.HELLO),
          // A quarantined runner's greeting: ack{0}, then why.
          new Frame(ProviderStates.A_QUARANTINED_RUNNER_DIALLING_IN, "quarantined", Cause.HELLO),
          // A runner not at the pin is told to upgrade, and takes nothing more.
          new Frame(
                  ProviderStates.A_RUNNER_DIALLING_IN_AT_AN_OLDER_VERSION,
                  "upgrade",
                  Cause.HELLO_AT_AN_OLDER_VERSION)
              .fixing("$.version", GoldenMasterRecordingTest.FIXED_VERSION)
              .fixing("$.image", FIXED_RUNNER_IMAGE),
          // Reserve is the claim: take or nothing.
          new Frame(
                  ProviderStates.A_RUNNER_IN_SERVICE_WITH_A_QUEUED_WORKSPACE, "take", Cause.RESERVE)
              .fixing("$.spec.image", FIXED_WORKSPACE_IMAGE)
              .rowIdIn("$.spec.env.QITS_WORKSPACE_DAEMON_URL")
              .rowIdIn("$.spec.env.QITS_WORKSPACE_DAEMON_API_BASE_PATH"),
          new Frame(
              ProviderStates.A_RUNNER_IN_SERVICE_WITH_EVERY_SLOT_TAKEN, "nothing", Cause.RESERVE),
          // The verbs routed to the runner that owns the workspace.
          new Frame(
              ProviderStates.A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE, "stop", Cause.STOP_CONTAINER),
          new Frame(
              ProviderStates.A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE,
              "delete",
              Cause.DELETE_CONTAINER),
          // An operator's asks of a runner in service, and its decommission.
          new Frame(ProviderStates.A_RUNNER_IN_SERVICE, "probeLogin", Cause.LOGIN_CHECK),
          new Frame(ProviderStates.A_RUNNER_IN_SERVICE, "healthCheck", Cause.HEALTH_CHECK),
          new Frame(ProviderStates.A_RUNNER_IN_SERVICE, "retire", Cause.DELETE_RUNNER),
          new Frame(
              ProviderStates.A_QUARANTINED_RUNNER_HOLDING_A_SOCKET,
              "reinstated",
              Cause.GREENLIGHT));

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String RUNNERS = "/workspaces/api/runners/{runnerId}";
  private static final String WORKSPACES = "/workspaces/api/workspaces/{workspaceId}";

  @Inject ProviderStates states;

  @Inject WorkspaceRunnerRegistry registry;

  @Inject RunnerReservations reservations;

  /**
   * Make {@code frame} go out in its state, which the caller set up and answered {@code params};
   * the text the runner was sent. Everything the setting up sent is forgotten first.
   */
  public String send(Frame frame, Map<String, String> params) {
    states.forgetRunnerFrames();
    WorkspaceRunnerRegistry.Session session = states.runnerSession();
    switch (frame.cause()) {
      case HELLO ->
          registry.onHello(
              session(session, frame),
              ProviderStates.hello(
                  WorkspacesRunnerBinary.VERSION,
                  params.containsKey("workspaceId")
                      ? List.of(params.get("workspaceId"))
                      : List.of()));
      case HELLO_AT_AN_OLDER_VERSION ->
          registry.onHello(
              session(session, frame), ProviderStates.hello(params.get("runnerVersion"), List.of()));
      case RESERVE -> reservations.onReserve(session(session, frame));
      case STOP_CONTAINER -> press("POST", WORKSPACES + "/stop-container", params, 200);
      case DELETE_CONTAINER -> press("POST", WORKSPACES + "/delete-container", params, 200);
      case LOGIN_CHECK -> press("POST", RUNNERS + "/login-check", params, 202);
      case HEALTH_CHECK -> press("POST", RUNNERS + "/healthcheck", params, 202);
      case GREENLIGHT -> press("POST", RUNNERS + "/greenlight", params, 200);
      case DELETE_RUNNER -> press("DELETE", RUNNERS, params, 204);
    }
    List<String> sent = states.runnerFramesSent();
    for (String text : sent) {
      if (frame.type().equals(typeOf(text))) {
        return text;
      }
    }
    throw new AssertionError(
        "In state '"
            + frame.state()
            + "', "
            + frame.cause()
            + " sent the runner no "
            + frame.type()
            + "; it was sent "
            + sent);
  }

  /** The wire {@code type} of a frame's text. */
  static String typeOf(String text) {
    try {
      return JSON.readTree(text).path(WorkspacesRunnerProtocol.Field.TYPE).asText();
    } catch (IOException e) {
      throw new UncheckedIOException("Not a frame: " + text, e);
    }
  }

  private static WorkspaceRunnerRegistry.Session session(
      WorkspaceRunnerRegistry.Session session, Frame frame) {
    if (session == null) {
      throw new IllegalStateException(
          "State '" + frame.state() + "' leaves no runner on a socket to send " + frame.type());
    }
    return session;
  }

  /** A door pressed as the {@code %test} dev user, which must answer {@code status}. */
  private static void press(String method, String template, Map<String, String> params, int status) {
    String path = template;
    for (Map.Entry<String, String> param : params.entrySet()) {
      path = path.replace("{" + param.getKey() + "}", param.getValue());
    }
    given().noContentType().when().request(method, path).then().statusCode(status);
  }

  private static Trigger runnerFrame(String type) {
    return Trigger.event("workspaces-runner:" + type);
  }
}
