package eu.wohlben.qits.workspaces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The header decision a pin IT's download makes, without a network: a token is a bearer, and no
 * token is no header at all — the internal plane's read stays exactly the anonymous one it was.
 */
class QitsTokenAuthTest {

  private static HttpRequest.Builder request() {
    return HttpRequest.newBuilder(URI.create("http://registry.invalid/artifacts/daemons/d/1")).GET();
  }

  @Test
  void aTokenIsPresentedAsABearer() {
    HttpRequest.Builder request = request();
    QitsTokenAuth.addIfPresent(request, "qits_tok_abc");
    assertEquals(
        List.of("Bearer qits_tok_abc"), request.build().headers().allValues("Authorization"));
  }

  @Test
  void noTokenSendsNoHeader() {
    for (String unset : new String[] {null, "", "  "}) {
      HttpRequest.Builder request = request();
      QitsTokenAuth.addIfPresent(request, unset);
      assertTrue(request.build().headers().map().isEmpty(), "token '" + unset + "'");
    }
  }

  @Test
  void aRefusedReadIsNotReportedAsAnUnpublishedArtifact() {
    String anonymous = QitsTokenAuth.describe(401, null);
    assertTrue(anonymous.contains("refused") && anonymous.contains("no token was sent"), anonymous);
    String withToken = QitsTokenAuth.describe(403, "qits_tok_abc");
    assertTrue(withToken.contains("refused") && withToken.contains("was sent as a bearer"), withToken);
    assertTrue(!withToken.contains("qits_tok_abc"), "the token itself is never printed");
    assertTrue(QitsTokenAuth.describe(404, null).contains("never published"));
  }
}
