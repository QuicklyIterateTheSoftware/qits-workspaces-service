package eu.wohlben.qits.workspaces.daemonhost;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-workspace-daemon contract</b>: every live row of {@link
 * WorkspaceDaemonContract}, made by the shipped {@link DaemonAgentClient} through a stub tunnel to a
 * pact mock server of its own. A {@code @QuarkusTest}: the client is a bean on the application's
 * Vert.x. The committed pact file is {@code ConsumerPactFilesTest}'s.
 */
@QuarkusTest
public class WorkspaceDaemonConsumerPactTest {

  @Test
  public void everyLiveRowIsWhatTheDaemonClientAsksAndUnderstands() {
    WorkspaceDaemonContract.TABLE.verifyLive();
  }
}
