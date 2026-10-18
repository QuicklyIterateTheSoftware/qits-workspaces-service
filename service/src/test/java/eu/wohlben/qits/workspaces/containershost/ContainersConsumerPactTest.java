package eu.wohlben.qits.workspaces.containershost;

import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-containers contract</b>: every live row of {@link
 * ContainersContract}, made by the real adapter and the real {@code qits-containers-client} against
 * a pact mock server of its own. Plain JUnit, as {@code WorkspaceContainersTest}: the client is a
 * plain class. The committed pact file is {@code ConsumerPactFilesTest}'s.
 */
class ContainersConsumerPactTest {

  @Test
  void everyLiveRowIsWhatTheAdapterAsksAndUnderstands() {
    ContainersContract.TABLE.verifyLive();
  }
}
