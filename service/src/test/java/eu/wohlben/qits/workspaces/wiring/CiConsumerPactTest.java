package eu.wohlben.qits.workspaces.wiring;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-ci contract</b> (epic qits-1681): every row of {@link
 * CiContract}, each made by {@link HttpWaitSubjectResolver} over the real generated {@link CiRuns}
 * client against a pact mock server of its own. A {@code @QuarkusTest}, for {@code
 * ProjectsConsumerPactTest}'s reason: the reactive REST client is built only inside a running
 * application.
 */
@QuarkusTest
public class CiConsumerPactTest {

  @Test
  public void everyRowIsWhatTheSubjectLookupAsksAndUnderstands() {
    CiContract.TABLE.verifyLive();
  }
}
