package eu.wohlben.qits.workspaces.testing.contracts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.containershost.ContainersContract;
import eu.wohlben.qits.workspaces.daemonhost.WorkspaceDaemonContract;
import eu.wohlben.qits.workspaces.wiring.IdpContract;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * <b>The committed consumer pacts against qits-idp, qits-containers and qits-workspace-daemon</b>
 * (qits-1149), one {@link ContractTable} each. Plain JUnit: building and writing a pact needs no
 * application. qits-projects' file is {@code ProjectsPactFileTest}'s.
 *
 * <p>Per table: the committed {@code pacts/qits-workspaces-service_<provider>.json} is what the live
 * rows write (compared by default, rewritten under {@code -Dgolden.update=true}); every interaction
 * carries both references; no pending row names a (state, operation) the pinned golden masters now
 * record — that row is switched on instead; and the pending rows are listed, as a skip naming each
 * one's provider state.
 */
class ConsumerPactFilesTest {

  static Stream<ContractTable> tables() {
    return Stream.of(IdpContract.TABLE, ContainersContract.TABLE, WorkspaceDaemonContract.TABLE);
  }

  @ParameterizedTest
  @MethodSource("tables")
  void theCommittedPactIsWhatTheLiveRowsWrite(ContractTable table) throws IOException {
    table.compareOrWritePactFile();
  }

  @ParameterizedTest
  @MethodSource("tables")
  void everyInteractionCarriesBothReferences(ContractTable table) throws IOException {
    table.assertEveryInteractionCarriesBothReferences();
  }

  @ParameterizedTest
  @MethodSource("tables")
  void noPendingRowIsRecordedAlready(ContractTable table) {
    List<String> recorded = table.recordedPending();
    assertTrue(
        recorded.isEmpty(),
        table.provider().repository()
            + " records these now — switch them on (drop .waiting()) and rewrite the pact file:\n  "
            + String.join("\n  ", recorded));
  }

  @ParameterizedTest
  @MethodSource("tables")
  void pendingRows(ContractTable table) {
    List<String> waiting = new ArrayList<>();
    table.pending().forEach(row -> waiting.add(row.description() + ": " + table.needs(row)));
    Assumptions.assumeTrue(
        waiting.isEmpty(),
        waiting.size() + " row(s) wait:\n  " + String.join("\n  ", waiting));
  }
}
