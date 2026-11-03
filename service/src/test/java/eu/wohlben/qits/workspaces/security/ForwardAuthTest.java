package eu.wohlben.qits.workspaces.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * <b>A forwarded identity header authenticates nothing</b> (epic qits-543, qits-1372). The edge
 * used to assert {@code X-Qits-User} / {@code X-Qits-Roles} and qits-auth-core's forward-auth
 * turned them into the caller; forward-auth leaves the library with qits-1338, so a caller is what
 * its bearer says and a request with nothing but those headers is anonymous. Until qits-1338 lands,
 * the suite's test config renames the headers forward-auth reads and blanks its dev user, which is
 * what this proves today; afterwards it proves the same thing with no config at all.
 *
 * <p>Every assertion is about <em>who the request is</em>, against the unguarded identity echo,
 * never about a status code: the guarded doors' refusals are their own tests'.
 *
 * <p>This is the one test here that still sends the pair, on purpose.
 */
@QuarkusTest
class ForwardAuthTest {

  @Test
  void theForwardedHeaderPairIsIgnored() {
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .get("/workspaces/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(true));
  }

  @Test
  void noCredentialIsAnonymousAndStillServed() {
    // Anonymous is "no name for the audit row", not a security state — the echo proceeds.
    given()
        .when()
        .get("/workspaces/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(true));
  }
}
