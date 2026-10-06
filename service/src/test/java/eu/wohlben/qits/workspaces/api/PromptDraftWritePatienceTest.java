package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.persistence.WorkspacePromptDraftRepository;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The prompt-draft WRITE holds through a postgres cutover, and holds it exactly once.
 *
 * <p><b>Why a write test looks nothing like the read one.</b> {@code ContainerProxyDbPatienceTest}
 * proves a lookup is asked again; a write has to prove something stronger — that asking again did
 * not write twice. So the wound here fires <em>after</em> the real statement has run, which is the
 * only arrangement that can tell a retry that re-executed a rolled-back write from one that added a
 * second effect. {@code prompt_version} is the counter that says which happened: the upsert bumps
 * it by one per execution, so a save that survived one severed connection reads {@code 1}, and a
 * double-executed one would read {@code 2}.
 *
 * <p><b>The second case is the narrowness.</b> {@code DbRetry.inNewTx} retries a body failure that
 * is connection-classed and nothing else, so a unique-constraint violation — certain not to have
 * committed, and certain to fail identically for the next fifteen seconds — must surface on the
 * first attempt. Widen the classifier and every rejected write would sit on the deadline first.
 */
@QuarkusTest
public class PromptDraftWritePatienceTest {

  /**
   * The production draft repository with one wound: the upsert runs for real and <em>then</em>
   * throws, so the transaction it is in has genuinely written before it is rolled back.
   *
   * <p>{@code @Vetoed} is load-bearing for the reason {@code ContainerProxyDbPatienceTest} records:
   * Quarkus scopes every {@code PanacheRepository} implementor it finds, so without it this double
   * would be a second bean of the type and fail the build at {@code ArcProcessor#validate}.
   */
  @Vetoed
  public static class FlakyDraftRepository extends WorkspacePromptDraftRepository {

    private static final AtomicInteger upserts = new AtomicInteger();
    private static final AtomicInteger severed = new AtomicInteger();
    private static final AtomicInteger rejected = new AtomicInteger();

    static void reset() {
      upserts.set(0);
      severed.set(0);
      rejected.set(0);
    }

    static void loseTheConnection(int times) {
      severed.set(times);
    }

    static void violateAConstraint(int times) {
      rejected.set(times);
    }

    static int upserts() {
      return upserts.get();
    }

    @Override
    public void upsert(Long workspaceId, String content, String serializedPrompt) {
      super.upsert(workspaceId, content, serializedPrompt);
      upserts.incrementAndGet();
      if (severed.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
        // The shape a cutover actually arrives in: Hibernate's JDBCConnectionException over a
        // SQLTransientConnectionException carrying postgres' 57P01.
        throw new JDBCConnectionException(
            "Unable to acquire JDBC Connection",
            new SQLTransientConnectionException(
                "terminating connection due to administrator command", "57P01"));
      }
      if (rejected.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
        // A real database failure that is not the connection: SQLState 23505.
        throw new PersistenceException(
            new SQLException("duplicate key value violates unique constraint", "23505"));
      }
    }
  }

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @Inject WorkspaceService workspaceService;

  @BeforeEach
  void installTheWoundedRepository() {
    FlakyDraftRepository.reset();
    QuarkusMock.installMockForType(
        new FlakyDraftRepository(), WorkspacePromptDraftRepository.class);
  }

  private Long workspace(String label) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createWorkspace(repoId, label, "master", label);
    return workspaceIds.of(repoId, label);
  }

  private static String base(Long id) {
    return "/workspaces/api/workspaces/" + id + "/prompt-draft";
  }

  @Test
  public void aConnectionLostMidSaveIsRetriedAndTheDraftIsWrittenOnce() throws Exception {
    Long id = workspace("draft-patience");

    // The cutover lands after the upsert's statements have run and before the transaction commits.
    FlakyDraftRepository.loseTheConnection(1);

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SavePromptDraftRequest("{\"a\":1}", "a"))
        .put(base(id))
        .then()
        // Not a 500. This is the autosave path, and a held request is composition the browser is
        // entitled to believe was saved.
        .statusCode(200)
        // ONE, not two. The first attempt's bump was rolled back with its transaction; the second
        // started from an empty table. A retry that had joined the failed transaction's work — or a
        // write that had already committed before the connection died — would read 2 here.
        .body("draft.promptVersion", equalTo(1));

    assertEquals(
        2,
        FlakyDraftRepository.upserts(),
        "the save must be retried exactly once: one severed attempt, then the real write");

    // And the row a later read serves agrees, so "exactly once" is about the database and not about
    // what the response happened to carry.
    given().get(base(id)).then().statusCode(200).body("draft.promptVersion", equalTo(1));
  }

  @Test
  public void aConstraintViolationIsNotRetried() throws Exception {
    Long id = workspace("draft-refusal");

    FlakyDraftRepository.violateAConstraint(1);

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SavePromptDraftRequest("{\"a\":1}", "a"))
        .put(base(id))
        .then()
        .statusCode(500);

    assertEquals(
        1,
        FlakyDraftRepository.upserts(),
        "a failure that is not the connection must surface on the first attempt");

    // Nothing landed: the failed attempt's transaction was rolled back whole, statements included.
    given().get(base(id)).then().statusCode(404);
  }
}
