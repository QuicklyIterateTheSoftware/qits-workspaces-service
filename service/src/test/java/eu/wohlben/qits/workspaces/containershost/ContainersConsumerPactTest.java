package eu.wohlben.qits.workspaces.containershost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.DslPart;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.containers.client.ContainersAnswer;
import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Envelope;
import eu.wohlben.qits.workspaces.control.TestWorkspaceContainerFactory;
import eu.wohlben.qits.workspaces.testing.contracts.ConsumerPacts;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-containers contract</b>: the place and volume doors {@link
 * WorkspaceContainers} reaches through {@code qits-containers-client}, each made by the real adapter
 * and the real client against a pact mock server. Plain JUnit, as {@code WorkspaceContainersTest}:
 * the client is a plain class.
 *
 * <p><b>Every row waits on qits-containers.</b> It publishes no golden masters yet, so each test is
 * disabled naming the provider state it needs. The operationIds are proposed here: its resources
 * declare none. Each state is expected to carry {@code owner}, {@code workload} ({@code workspace},
 * {@link WorkspaceContainers#WORKLOAD}) and {@code ref} — a lowercase name, which {@link
 * WorkspaceContainers#refOf} keeps as it is — and, for a volume, {@code name}.
 *
 * <p><b>What is read.</b> The adapter reads {@code state.observed} and {@code detail} of a place and
 * {@code containerName} and {@code state.observed} of a listing; for a stop, a delete and the volume
 * doors it reads only whether the call succeeded — but the client binds every 2xx body to its record
 * and refuses one that does not bind, so those rows ask for a JSON object and no field of it. A
 * touch answers no body at all.
 */
@Disabled("needs qits-containers-service golden masters — each row names its provider state")
class ContainersConsumerPactTest {

  static final String NO_CONTAINER = "no workspace container at the place";
  static final String RUNNING = "a running workspace container";
  static final String A_VOLUME = "a workspace volume exists";
  static final String NO_VOLUME = "no workspace volume of that name";

  static final Trigger CREATE = Trigger.operation("createWorkspace");

  private static final String REPO = "repo12345678abc";

  @Test
  @Disabled(
      "needs provider state 'no workspace container at the place' for ensureContainer in"
          + " qits-containers-service")
  void ensureContainer() {
    Map<String, String> params = GoldenMasters.params(GoldenMasters.CONTAINERS, NO_CONTAINER);
    EnsureRequest request = adapter("http://unused", params).ensureRequest(REPO, "work", 1L, "main", "0parent");
    run(
        NO_CONTAINER,
        "ensureContainer",
        CREATE,
        GoldenMasters.exactBody(wire(request)),
        List.of("$.state.observed", "$.detail"),
        url -> {
          WorkspaceContainers adapter = adapter(url, params);
          ContainersAnswer<Envelope> answer =
              adapter.containers.ensure(
                  params.get("owner"), params.get("workload"), params.get("ref"), request);
          assertTrue(answer.succeeded(), String.valueOf(answer));
          assertTrue(answer.value().state().observed() != null, "the place says what it found");
        });
  }

  @Test
  @Disabled(
      "needs provider state 'a running workspace container' for getContainer in"
          + " qits-containers-service")
  void getContainer() {
    Map<String, String> params = GoldenMasters.params(GoldenMasters.CONTAINERS, RUNNING);
    run(
        RUNNING,
        "getContainer",
        CREATE,
        null,
        List.of("$.state.observed"),
        url -> assertTrue(adapter(url, params).isRunning(params.get("ref"))));
  }

  @Test
  @Disabled(
      "needs provider state 'a running workspace container' for listWorkloadContainers in"
          + " qits-containers-service")
  void listWorkloadContainers() {
    Map<String, String> params = GoldenMasters.params(GoldenMasters.CONTAINERS, RUNNING);
    run(
        RUNNING,
        "listWorkloadContainers",
        Trigger.operation("listWorkspaces"),
        null,
        List.of("$.containers[*].containerName", "$.containers[*].state.observed"),
        url -> {
          ContainersAnswer<List<Envelope>> answer =
              adapter(url, params)
                  .containers
                  .list(params.get("owner"), params.get("workload"), Duration.ofSeconds(5));
          assertTrue(answer.succeeded(), String.valueOf(answer));
          assertFalse(answer.value().isEmpty(), "the recording holds the running container");
          answer.value().forEach(e -> assertTrue(e.containerName() != null));
        });
  }

  @Test
  @Disabled(
      "needs provider state 'a running workspace container' for stopContainer in"
          + " qits-containers-service")
  void stopContainer() {
    Map<String, String> params = GoldenMasters.params(GoldenMasters.CONTAINERS, RUNNING);
    run(
        RUNNING,
        "stopContainer",
        Trigger.operation("stopWorkspace"),
        null,
        GoldenMasters.A_JSON_BODY,
        url -> adapter(url, params).stop(params.get("ref")));
  }

  @Test
  @Disabled(
      "needs provider state 'a running workspace container' for touchContainer in"
          + " qits-containers-service")
  void touchContainer() {
    Map<String, String> params = GoldenMasters.params(GoldenMasters.CONTAINERS, RUNNING);
    run(
        RUNNING,
        "touchContainer",
        Trigger.schedule("EditorKeepalive"),
        null,
        List.of(),
        url -> adapter(url, params).touch(params.get("ref")));
  }

  @Test
  @Disabled(
      "needs provider state 'a running workspace container' for deleteContainer in"
          + " qits-containers-service")
  void deleteContainer() {
    Map<String, String> params = GoldenMasters.params(GoldenMasters.CONTAINERS, RUNNING);
    run(
        RUNNING,
        "deleteContainer",
        Trigger.operation("deleteWorkspace"),
        null,
        GoldenMasters.A_JSON_BODY,
        url -> adapter(url, params).rm(params.get("ref")));
  }

  @Test
  @Disabled(
      "needs provider state 'no workspace volume of that name' for ensureVolume in"
          + " qits-containers-service")
  void ensureVolume() {
    Map<String, String> params = GoldenMasters.params(GoldenMasters.CONTAINERS, NO_VOLUME);
    run(
        NO_VOLUME,
        "ensureVolume",
        CREATE,
        null,
        GoldenMasters.A_JSON_BODY,
        url ->
            assertTrue(
                adapter(url, params)
                    .containers
                    .ensureVolume(params.get("owner"), params.get("name"), Duration.ofSeconds(5))
                    .succeeded()));
  }

  @Test
  @Disabled(
      "needs provider state 'a workspace volume exists' for deleteVolume in qits-containers-service")
  void deleteVolume() {
    Map<String, String> params = GoldenMasters.params(GoldenMasters.CONTAINERS, A_VOLUME);
    run(
        A_VOLUME,
        "deleteVolume",
        Trigger.operation("deleteWorkspace"),
        null,
        GoldenMasters.A_JSON_BODY,
        url ->
            assertTrue(
                adapter(url, params)
                    .containers
                    .deleteVolume(params.get("owner"), params.get("name"), Duration.ofSeconds(5))
                    .succeeded()));
  }

  // --- support -----------------------------------------------------------------------------------

  private static void run(
      String state,
      String operationId,
      Trigger trigger,
      DslPart requestBody,
      List<String> consumes,
      Consumer<String> call) {
    ConsumerPacts.verify(
        ConsumerPacts.pact(
            GoldenMasters.CONTAINERS,
            builder ->
                GoldenMasters.interaction(
                    builder,
                    GoldenMasters.CONTAINERS,
                    state,
                    operationId,
                    trigger,
                    requestBody,
                    consumes)),
        call);
  }

  /** The adapter as {@code WorkspaceContainersTest} builds it, owned by the state's owner. */
  private static WorkspaceContainers adapter(String url, Map<String, String> params) {
    assertEquals(WorkspaceContainers.WORKLOAD, params.get("workload"));
    WorkspaceContainers containers = new WorkspaceContainers();
    containers.containerFactory = TestWorkspaceContainerFactory.persistent();
    containers.owner = params.get("owner");
    containers.editorIdleStopAfter = Optional.empty();
    containers.launchPatience = Duration.ofSeconds(1);
    containers.containers =
        new ContainersClient(url, Duration.ofSeconds(5), Duration.ofSeconds(5), Optional::empty);
    return containers;
  }

  /**
   * The ensure body exactly as the client puts it on the wire: captured off a stub, because the
   * client's serialiser is its own and the pact must hold what is sent, not a re-serialisation.
   */
  private static com.fasterxml.jackson.databind.JsonNode wire(EnsureRequest request) {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.fallback(500, "{}");
      new ContainersClient(stub.url(), Duration.ofSeconds(5), Duration.ofSeconds(5), Optional::empty)
          .ensure("owner", "workspace", "ref", request);
      return new ObjectMapper().readTree(stub.last().body());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
