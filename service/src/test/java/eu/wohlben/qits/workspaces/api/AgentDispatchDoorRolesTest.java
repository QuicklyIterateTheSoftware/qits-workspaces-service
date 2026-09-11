package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.workspaces.security.NoDevUserProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Who may press {@code POST /workspaces/api/agent-dispatches}.
 *
 * <p>The door's whole reason to be a class of its own is its role list: {@code qits:admin} beside
 * {@code qits:system}, because the caller is qits-projects dispatching a ticket and a machine's
 * token carries the system role and never the admin one. {@link BranchResolutionController} pins the
 * same pair for the same kind of caller, and {@code WorkspaceController} — where a create verb would
 * otherwise have gone — pins only {@code qits:admin} on its class, which is exactly the 403 this
 * class is here to prove did not happen.
 *
 * <p>Under {@link NoDevUserProfile}, which blanks the {@code %test} dev-user fallback so that an
 * anonymous request really is anonymous. Roles arrive the way the platform edge asserts them for a
 * caller it has authenticated; a machine's idp bearer lands in the same {@code SecurityIdentity} and
 * is judged by the same annotation, so what is proved here is the annotation.
 *
 * <p>Every request below names a repository that does not exist, so nothing is created on any
 * outcome — the interesting status is the one in front of that 404.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
class AgentDispatchDoorRolesTest {

  private static Map<String, Object> aDispatch() {
    return Map.of(
        "repositoryId", "no-such-repository",
        "branch", "ticket/whatever",
        "branchTree", Boolean.FALSE,
        "preamble", "the goal",
        "instruction", "go");
  }

  @Test
  void anonymousIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body(aDispatch())
        .when()
        .post("/workspaces/api/agent-dispatches")
        .then()
        .statusCode(401);
  }

  /** The machine role, and the reason this door exists at all. */
  @Test
  void theSystemRoleIsAdmitted() {
    given()
        .header("X-Qits-User", "qits-projects")
        .header("X-Qits-Roles", "qits:system")
        .contentType(ContentType.JSON)
        .body(aDispatch())
        .when()
        .post("/workspaces/api/agent-dispatches")
        .then()
        // Past the gate: the 404 is the repository, not the caller.
        .statusCode(404);
  }

  /** A person may still press it — the same operator who creates workspaces by hand. */
  @Test
  void theAdminRoleIsAdmitted() {
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .contentType(ContentType.JSON)
        .body(aDispatch())
        .when()
        .post("/workspaces/api/agent-dispatches")
        .then()
        .statusCode(404);
  }

  /** Authenticated, and holding a platform role this door never names. */
  @Test
  void anUnprivilegedRoleIsRefused() {
    given()
        .header("X-Qits-User", "bob")
        .header("X-Qits-Roles", "qits:reader")
        .contentType(ContentType.JSON)
        .body(aDispatch())
        .when()
        .post("/workspaces/api/agent-dispatches")
        .then()
        .statusCode(403);
  }

  /**
   * The read back, and the case that was missing when it first shipped on {@code
   * WorkspaceController}: a machine caller gets a 200 and an answer, not a 403. Nothing about the
   * caller's own never-throw contract can tell those apart, which is how the feature shipped inert.
   */
  @Test
  void theSystemRoleMayReadTheReferences() {
    given()
        .header("X-Qits-User", "qits-projects")
        .header("X-Qits-Roles", "qits:system")
        .when()
        .get("/workspaces/api/agent-dispatches/references?ticketId=t-1")
        .then()
        .statusCode(200);
  }

  /** A person reads it too — the browser half of the same field. */
  @Test
  void theAdminRoleMayReadTheReferences() {
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .get("/workspaces/api/agent-dispatches/references?ticketId=t-1")
        .then()
        .statusCode(200);
  }

  @Test
  void anUnprivilegedRoleMayNotReadTheReferences() {
    given()
        .header("X-Qits-User", "bob")
        .header("X-Qits-Roles", "qits:reader")
        .when()
        .get("/workspaces/api/agent-dispatches/references?ticketId=t-1")
        .then()
        .statusCode(403);
  }
}
