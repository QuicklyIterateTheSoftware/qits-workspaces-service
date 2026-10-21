package eu.wohlben.qits.workspaces.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Nothing;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.workspaces.control.FakeWorkspaceDaemonProvisioner;
import eu.wohlben.qits.workspaces.control.RunnerClaims;
import eu.wohlben.qits.workspaces.control.WorkspaceAddressPlanes;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspacesrunner.protocol.Estate;
import eu.wohlben.qits.workspacesrunner.protocol.Take;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusMock;
import io.vertx.core.Vertx;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * <b>What a {@link RunnerFramesContract} row needs to feed a frame to this service</b> (qits-950):
 * the real socket, real rows and the beans whose effect it asserts — {@code
 * RunnerReservationsTest}'s arrangement, for one row at a time.
 *
 * <p>Opened by {@link RunnerFramesConsumerPactTest} before each row (it holds the socket's URI, which
 * only a {@code @TestHTTPResource} knows on the test's random port) and closed after it: the sockets
 * it dialled are hung up, the rows taken back and the fake daemon provisioner reset. A row reads it
 * through {@link #current()}, which fails outside that test.
 */
final class RunnerFrameHarness implements AutoCloseable {

  /** The version every runner says hello in: the pin, so it is greeted rather than upgraded. */
  static final String PIN = WorkspacesRunnerBinary.VERSION;

  private static final ObjectMapper JSON = new ObjectMapper();

  private static volatile RunnerFrameHarness current;

  final RunnerRows rows;
  final WorkspaceRunnerRegistry registry =
      Arc.container().instance(WorkspaceRunnerRegistry.class).get();
  final WorkspaceRunnerHealth health = Arc.container().instance(WorkspaceRunnerHealth.class).get();
  final WorkspaceRunnerViews views = Arc.container().instance(WorkspaceRunnerViews.class).get();
  final WorkspaceService workspaces = Arc.container().instance(WorkspaceService.class).get();
  final RunnerClaims claims = Arc.container().instance(RunnerClaims.class).get();
  final FakeWorkspaceDaemonProvisioner provisioner =
      Arc.container().instance(FakeWorkspaceDaemonProvisioner.class).get();

  private final Vertx vertx;
  private final URI endpoint;
  private final List<FakeWorkspacesRunner> dialled = new ArrayList<>();

  private RunnerFrameHarness(Vertx vertx, URI endpoint) {
    this.vertx = vertx;
    this.endpoint = endpoint;
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.withDomain(WorkspaceRunnerAddressesFixture.DOMAIN),
        WorkspaceRunnerAddresses.class);
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.planesWithDomain(WorkspaceRunnerAddressesFixture.DOMAIN),
        WorkspaceAddressPlanes.class);
    this.rows = new RunnerRows();
  }

  /** Opens the harness every row of this test reads, until {@link #close}. */
  static RunnerFrameHarness open(Vertx vertx, URI endpoint) {
    RunnerFrameHarness opened = new RunnerFrameHarness(vertx, endpoint);
    current = opened;
    return opened;
  }

  static RunnerFrameHarness current() {
    RunnerFrameHarness open = current;
    if (open == null) {
      throw new IllegalStateException(
          "a RunnerFramesContract row runs inside RunnerFramesConsumerPactTest, which opens the"
              + " harness");
    }
    return open;
  }

  /** A unique client id, so no two rows' runners meet. */
  static String clientId(String what) {
    return "wr-pact-" + what + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  FakeWorkspacesRunner dial(String clientId) throws Exception {
    FakeWorkspacesRunner runner =
        FakeWorkspacesRunner.connect(
            vertx,
            endpoint,
            DaemonMachineTokens.tokenWithRoles(
                clientId, Set.of(WorkspaceRunnerSocket.RUNNER_ROLE), "qits-platform"));
    dialled.add(runner);
    return runner;
  }

  /** Dialled and greeted at the pin as a runner in service, read up to its backlog. */
  FakeWorkspacesRunner greeted(String clientId) throws Exception {
    FakeWorkspacesRunner runner = dial(clientId);
    runner.send(FakeWorkspacesRunner.hello(PIN, List.of()));
    runner.expect(Ack.class);
    runner.expect(Estate.class);
    runner.expect(Backlog.class);
    return runner;
  }

  /** The answer to a reserve: the first {@code take} or {@code nothing}, past any push. */
  static RunnerMessage answer(FakeWorkspacesRunner runner) throws Exception {
    long deadline = System.nanoTime() + FakeWorkspacesRunner.SOON.toNanos();
    while (System.nanoTime() < deadline) {
      RunnerMessage next = runner.poll(Duration.ofMillis(100));
      if (next instanceof Take || next instanceof Nothing) {
        return next;
      }
    }
    return fail("neither take nor nothing arrived");
  }

  void awaitStatus(Long rowId, WorkspaceRuntimeStatus status) throws InterruptedException {
    eventually(() -> rows.read(rowId).runtimeStatus, s -> s == status, "row " + rowId);
    assertEquals(status, rows.read(rowId).runtimeStatus);
  }

  /** {@code read} once it satisfies {@code done}, or its last value after {@link FakeWorkspacesRunner#SOON}. */
  static <T> T eventually(Supplier<T> read, Predicate<T> done, String what)
      throws InterruptedException {
    long deadline = System.nanoTime() + FakeWorkspacesRunner.SOON.toNanos();
    T value = read.get();
    while (!done.test(value) && System.nanoTime() < deadline) {
      Thread.sleep(50);
      value = read.get();
    }
    return value;
  }

  /** {@code read} stays satisfying {@code holds} for {@code quiet}: an absence, asserted by waiting. */
  static <T> void throughout(Duration quiet, Supplier<T> read, Predicate<T> holds, String what)
      throws InterruptedException {
    long deadline = System.nanoTime() + quiet.toNanos();
    while (System.nanoTime() < deadline) {
      T value = read.get();
      if (!holds.test(value)) {
        fail(what + " changed: " + value);
      }
      Thread.sleep(50);
    }
  }

  /** The runner row's capabilities column, parsed: what its frames left there. */
  JsonNode capabilities(UUID runnerId) {
    String column = rows.runner(runnerId).capabilities;
    try {
      return column == null ? JSON.createObjectNode() : JSON.readTree(column);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    try {
      dialled.forEach(FakeWorkspacesRunner::close);
      dialled.clear();
      provisioner.reset();
      rows.clear();
    } finally {
      if (Objects.equals(current, this)) {
        current = null;
      }
    }
  }
}
