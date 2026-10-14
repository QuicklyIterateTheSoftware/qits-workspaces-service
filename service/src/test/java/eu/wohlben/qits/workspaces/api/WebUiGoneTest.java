package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * This service serves no web client (qits-1245): the workspace pages are qits-landing-app's. The
 * deployed SPA answered at the ROOT of this service's vhost — {@code /}, {@code /index.html}, its
 * deep links and its bundle assets — with the bare {@code /workspaces} redirecting to {@code
 * /workspaces/}. Every one of those is a plain 404 now: not a redirect, not a page. The machine
 * surface under {@code /workspaces} is untouched; {@link #theApiStillAnswers} is the canary here and
 * the API's and the container proxy's own tests cover the rest.
 */
@QuarkusTest
class WebUiGoneTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/",
        "/index.html",
        "/main.js",
        "/styles.css",
        "/favicon.ico",
        "/runners",
        "/agents/42",
        "/qits",
        "/qits/runners",
        "/qits/repositories/r1/workspaces/41",
        "/projects/qits/workspaces",
        "/workspaces",
        "/workspaces/",
      })
  void noClientPathIsServed(String path) {
    given()
        .redirects()
        .follow(false)
        .when()
        .get(path)
        .then()
        .statusCode(404)
        .body(not(containsString("app-root")));
  }

  @Test
  void theApiStillAnswers() {
    // The OpenAPI document is under the machine surface and needs no identity: if the 404s above
    // came from a service that answers nothing at all, this is what would say so.
    given().when().get("/workspaces/q/openapi").then().statusCode(200);
  }
}
