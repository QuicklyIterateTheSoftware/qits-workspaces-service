package eu.wohlben.qits.workspaces.targethost;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-system contract</b> (epic qits-1481): every live row of {@link
 * SystemContract}, each made by {@link TargetLeasePlacement} over the real generated client against
 * a pact mock server of its own. A {@code @QuarkusTest}, for {@code ProjectsConsumerPactTest}'s
 * reason: the reactive REST client is built only inside a running application.
 *
 * <p>While every row waits for the pinned golden masters to record its state, there is nothing to
 * run, and the test says so as a skip rather than passing on an empty table.
 */
@QuarkusTest
public class SystemConsumerPactTest {

  @Test
  public void everyLiveRowIsWhatTheLeasePlacementAsksAndUnderstands() {
    Assumptions.assumeFalse(
        SystemContract.TABLE.live().isEmpty(),
        SystemContract.TABLE.pending().size() + " row(s) wait for qits-system's golden masters");
    SystemContract.TABLE.verifyLive();
  }
}
