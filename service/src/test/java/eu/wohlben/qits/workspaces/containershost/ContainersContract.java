package eu.wohlben.qits.workspaces.containershost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.containers.client.ContainersAnswer;
import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Envelope;
import eu.wohlben.qits.workspaces.control.TestWorkspaceContainerFactory;
import eu.wohlben.qits.workspaces.testing.contracts.ContractRow;
import eu.wohlben.qits.workspaces.testing.contracts.ContractTable;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Request;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>What qits-workspaces asks qits-containers, and why</b> (qits-1149): the place and volume doors
 * {@link WorkspaceContainers} reaches through {@code qits-containers-client} — the table {@code
 * ContainersConsumerPactTest} runs and {@code ConsumerPactFilesTest} writes to {@code
 * pacts/qits-workspaces-service_qits-containers-service.json}.
 *
 * <p><b>The volume rows are live; the place rows wait.</b> qits-containers records its place states
 * for the {@code editor} workload, and this service only ever asks for {@link
 * WorkspaceContainers#WORKLOAD} ({@code workspace}) — the path carries the workload, so a row bound
 * to an editor recording would pact a request this service never sends. Each place row names a
 * state that is expected to carry {@code owner}, {@code workload: workspace} and {@code ref} (a
 * lowercase name, which {@link WorkspaceContainers#refOf} keeps as it is). A volume's name is a
 * param on both sides, so the client is called with the state's {@code owner} and {@code name}.
 *
 * <p><b>What is read.</b> The adapter reads {@code state.observed} and {@code detail} of a place and
 * {@code containerName} and {@code state.observed} of a listing; for a stop, a delete and the volume
 * doors only whether the call succeeded — but the client binds every 2xx body to its record and
 * refuses one that does not bind, so those rows ask for a JSON object and no field of it.
 */
public final class ContainersContract {

  // Recorded by qits-containers (golden masters 2026.1010.134141).
  static final String NO_VOLUME = "no volume";
  static final String A_VOLUME = "a claimed volume";

  // Not recorded: the same places, for the workspace workload.
  static final String NO_CONTAINER = "no workspace container at the place";
  static final String RUNNING = "a running workspace container";
  static final String IMAGE_MISSING = "no workspace container, and an image the registry does not hold";

  /** A slot is started, and torn down when its agents are gone (D2), by the scheduler. */
  static final Trigger RECONCILE = Trigger.schedule("AgentService.reconcile");

  private static final String REPO = "repo12345678abc";

  public static final ContractTable TABLE =
      new ContractTable(
          GoldenMasters.CONTAINERS,
          List.of(
              ContractRow.of(
                  RECONCILE,
                  NO_VOLUME,
                  "ensureVolume",
                  Request.NONE,
                  GoldenMasters.A_JSON_BODY,
                  (url, params) ->
                      assertTrue(
                          client(url)
                              .ensureVolume(params.get("owner"), params.get("name"), Duration.ofSeconds(5))
                              .succeeded())),
              ContractRow.of(
                  RECONCILE,
                  A_VOLUME,
                  "deleteVolume",
                  Request.NONE,
                  GoldenMasters.A_JSON_BODY,
                  (url, params) ->
                      assertTrue(
                          client(url)
                              .deleteVolume(params.get("owner"), params.get("name"), Duration.ofSeconds(5))
                              .succeeded())),
              // --- recorded since qits-containers' golden masters 2026.1018.185227 -----------
              ContractRow.of(
                  RECONCILE,
                  NO_CONTAINER,
                  "ensureContainer",
                  Request.json(GoldenMasters.exactBody(wire(ensureRequest()))),
                  List.of("$.state.observed", "$.detail"),
                  (url, params) -> {
                    WorkspaceContainers adapter = adapter(url, params);
                    ContainersAnswer<Envelope> answer =
                        adapter.containers.ensure(
                            params.get("owner"),
                            WorkspaceContainers.WORKLOAD,
                            params.get("ref"),
                            ensureRequest());
                    assertTrue(answer.succeeded(), String.valueOf(answer));
                    assertTrue(answer.value().state().observed() != null, "the place says what it found");
                  }),
              // Still waiting. A pin the registry does not hold: 409 IMAGE_MISSING, which the launch
              // does not hold through and fails naming (WorkspaceContainers.holdThrough).
              ContractRow.of(
                      RECONCILE,
                      IMAGE_MISSING,
                      "ensureContainer",
                      Request.json(GoldenMasters.exactBody(wire(ensureRequest()))),
                      List.of("$.code"),
                      (url, params) -> {
                        ContainersAnswer<Envelope> answer =
                            adapter(url, params)
                                .containers
                                .ensure(
                                    params.get("owner"),
                                    WorkspaceContainers.WORKLOAD,
                                    params.get("ref"),
                                    ensureRequest());
                        assertTrue(answer.imageMissing(), String.valueOf(answer));
                      })
                  .waiting(),
              ContractRow.of(
                  Trigger.operation("ensureWorkspaceContainer"),
                  RUNNING,
                  "getContainer",
                  Request.NONE,
                  List.of("$.state.observed"),
                  (url, params) -> assertTrue(adapter(url, params).isRunning(params.get("ref")))),
              // Still waiting: no recording of getContainer against an absent container.
              ContractRow.of(
                      Trigger.operation("ensureWorkspaceContainer"),
                      NO_CONTAINER,
                      "getContainer",
                      Request.NONE,
                      List.of(),
                      (url, params) -> assertFalse(adapter(url, params).exists(params.get("ref"))))
                  .waiting(),
              ContractRow.of(
                  Trigger.operation("listWorkspaces"),
                  RUNNING,
                  "listWorkloadContainers",
                  Request.NONE,
                  List.of("$.containers[*].containerName", "$.containers[*].state.observed"),
                  (url, params) -> {
                    ContainersAnswer<List<Envelope>> answer =
                        adapter(url, params)
                            .containers
                            .list(params.get("owner"), WorkspaceContainers.WORKLOAD, Duration.ofSeconds(5));
                    assertTrue(answer.succeeded(), String.valueOf(answer));
                    assertFalse(answer.value().isEmpty(), "the recording holds the running container");
                  }),
              ContractRow.of(
                  Trigger.operation("stopWorkspaceContainer"),
                  RUNNING,
                  "stopContainer",
                  Request.NONE,
                  GoldenMasters.A_JSON_BODY,
                  (url, params) -> adapter(url, params).stop(params.get("ref"))),
              ContractRow.of(
                  RECONCILE,
                  RUNNING,
                  "deleteContainer",
                  Request.NONE,
                  GoldenMasters.A_JSON_BODY,
                  (url, params) -> adapter(url, params).rm(params.get("ref")))));

  private ContainersContract() {}

  private static ContainersClient client(String url) {
    return new ContainersClient(url, Duration.ofSeconds(5), Duration.ofSeconds(5), Optional::empty);
  }

  /** The adapter as {@code WorkspaceContainersTest} builds it, owned by the state's owner. */
  private static WorkspaceContainers adapter(String url, Map<String, String> params) {
    assertEquals(WorkspaceContainers.WORKLOAD, params.get("workload"));
    WorkspaceContainers containers = new WorkspaceContainers();
    containers.containerFactory = TestWorkspaceContainerFactory.persistent();
    containers.owner = params.get("owner");
    containers.launchPatience = Duration.ofSeconds(1);
    containers.containers = client(url);
    return containers;
  }

  /** The spec a workspace's start sends, as the adapter composes it. */
  private static EnsureRequest ensureRequest() {
    WorkspaceContainers adapter = new WorkspaceContainers();
    adapter.containerFactory = TestWorkspaceContainerFactory.persistent();
    return adapter.ensureRequest(REPO, "work", 1L, "main", "0parent");
  }

  /**
   * The ensure body exactly as the client puts it on the wire: captured off a stub, because the
   * client's serialiser is its own and the pact must hold what is sent, not a re-serialisation.
   */
  private static JsonNode wire(EnsureRequest request) {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.fallback(500, "{}");
      client(stub.url()).ensure("owner", "workspace", "ref", request);
      return new ObjectMapper().readTree(stub.last().body());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
