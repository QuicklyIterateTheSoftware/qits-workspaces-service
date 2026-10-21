package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.testing.contracts.ContractRow;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * <b>The consumer half of the runner's message pact</b> (qits-950, A2): every live row of {@link
 * RunnerFramesContract}, its frame handed to this service over the real socket and the effect
 * asserted. One invocation per row, so each fails on its own and by name.
 *
 * <p>On {@link WorkspaceRunnerSocketTest}'s profile ({@link DaemonControlSocketMachineAuthTest.GateOn},
 * reused: one profile is one Quarkus start), because a runner is named by its bearer's {@code sub}.
 * The parameters are the rows' (description, state) — plain strings, which cross Quarkus' test
 * classloader as they are — and the row is looked up in the table. The committed pact file is
 * {@code ConsumerPactFilesTest}'s.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class RunnerFramesConsumerPactTest {

  @TestHTTPResource(WorkspacesRunnerProtocol.SOCKET_PATH)
  URI endpoint;

  @Inject Vertx vertx;

  private RunnerFrameHarness harness;

  static Stream<Arguments> rows() {
    return RunnerFramesContract.TABLE.live().stream()
        .map(row -> Arguments.of(row.description(), row.state()));
  }

  @BeforeEach
  void open() {
    harness = RunnerFrameHarness.open(vertx, endpoint);
  }

  @AfterEach
  void close() {
    harness.close();
  }

  @ParameterizedTest(name = "{0} [{1}]")
  @MethodSource("rows")
  void theServiceHandlesTheFrameAsTheContractSays(String description, String state)
      throws Exception {
    ContractRow row = RunnerFramesContract.TABLE.row(description, state);
    RunnerFramesContract.TABLE.receive(row);
  }
}
