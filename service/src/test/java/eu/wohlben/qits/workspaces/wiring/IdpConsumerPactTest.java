package eu.wohlben.qits.workspaces.wiring;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-idp contract</b>: every live row of {@link IdpContract}, each
 * made by this service's real code — {@link IdpCredentialCommissioner} over the real generated
 * clients, the shipped {@code qits} oidc client for
 * the peer bearer — against a pact mock server of its own. The committed pact file is {@code
 * ConsumerPactFilesTest}'s.
 *
 * <p><b>A {@code @QuarkusTest}</b>, for {@code ProjectsConsumerPactTest}'s reason: the reactive REST
 * client and the oidc client are built only inside a running application. It boots the default
 * profile, so it adds no {@code @TestProfile}.
 */
@QuarkusTest
public class IdpConsumerPactTest {

  @Test
  public void everyLiveRowIsWhatTheCommissionersAskAndUnderstand() {
    IdpContract.TABLE.verifyLive();
  }
}
