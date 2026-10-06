package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.LegacyDirectRows;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Inject;
import java.sql.SQLTransientConnectionException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The workspace-daemon proxy holds through a postgres cutover instead of reporting a live workspace
 * as gone.
 *
 * <p><b>Why this seam and not another.</b> {@code /workspaces/container/{id}/*} is the only path by
 * which anything reaches a daemon's HTTP API — the file browser, the commands surface, both
 * interactive websockets. Its lookup reads the workspace row, and the row read is the whole
 * difference between "here is your daemon" and a 404. A connection severed mid-flight used to
 * surface as an exception on that read, so a database blip took every open workspace down and said
 * "no workspace here" while doing it. {@code DbRetry} at the caller of {@code
 * DaemonProxyTargets.resolve} turns that into a held request.
 *
 * <p><b>The double is a real repository with one wound.</b> {@link FlakyWorkspaceRepository} extends
 * the production repository and delegates everything; only an armed lookup throws, and it throws
 * the shape a severed connection actually arrives in — Hibernate's {@code JDBCConnectionException}
 * over a {@code SQLTransientConnectionException} carrying postgres' {@code 57P01}. A double that
 * threw a bare {@code RuntimeException} would pass against a retry that swallowed everything, which
 * is precisely the retry nobody wants.
 */
@QuarkusTest
public class ContainerProxyDbPatienceTest {

  /**
   * A workspace repository that loses its connection on the next {@code n} lookups and is otherwise
   * the real one. Static state because the test and the bean the container hands out are different
   * objects; it is reset before every test.
   *
   * <p><b>{@code @Vetoed} is load-bearing.</b> Quarkus adds a scope to every {@code
   * PanacheRepository} implementor it finds, so without it this double would be a second bean of
   * type {@code WorkspaceRepository} — an ambiguous dependency that fails the build at {@code
   * ArcProcessor#validate}, for every test at once. It is never a bean; {@code QuarkusMock} installs
   * the instance for the length of one test.
   */
  @Vetoed
  public static class FlakyWorkspaceRepository extends WorkspaceRepository {

    private static final AtomicInteger woundedLookups = new AtomicInteger();
    private static final AtomicInteger lookups = new AtomicInteger();

    static void loseTheConnection(int times) {
      woundedLookups.set(times);
    }

    static void reset() {
      woundedLookups.set(0);
      lookups.set(0);
    }

    static int lookups() {
      return lookups.get();
    }

    @Override
    public Optional<Workspace> findActiveById(Long id) {
      lookups.incrementAndGet();
      if (woundedLookups.getAndUpdate(left -> left > 0 ? left - 1 : 0) > 0) {
        throw new JDBCConnectionException(
            "Unable to acquire JDBC Connection",
            new SQLTransientConnectionException(
                "terminating connection due to administrator command", "57P01"));
      }
      return super.findActiveById(id);
    }
  }

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @Inject WorkspaceService workspaceService;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @BeforeEach
  void installTheWoundedRepository() {
    FlakyWorkspaceRepository.reset();
    QuarkusMock.installMockForType(new FlakyWorkspaceRepository(), WorkspaceRepository.class);
  }

  /** A workspace row with no container: enough to be resolved, and reached without a fake daemon. */
  private Long workspaceWithoutContainer() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "patience", "master", "patience"));
    return workspaceIds.of(repoId, "patience");
  }

  @Test
  public void aConnectionLostMidLookupIsHeldRatherThanAnswered404() throws Exception {
    Long id = workspaceWithoutContainer();

    // The cutover lands between the request arriving and the row being read.
    FlakyWorkspaceRepository.loseTheConnection(1);
    int lookupsBefore = FlakyWorkspaceRepository.lookups();

    given()
        .get("/workspaces/container/" + id + "/files")
        .then()
        // Not 404. The workspace is alive and the answer says what is actually wrong with it — the
        // container is not running — which is only reachable if the second attempt read the row.
        .statusCode(502)
        .body(containsString("not running"));

    assertEquals(
        2,
        FlakyWorkspaceRepository.lookups() - lookupsBefore,
        "the lookup must be retried exactly once: one severed attempt, then the real answer");
  }

  @Test
  public void aWorkspaceThatGenuinelyIsNotThereStill404sOnTheFirstAttempt() throws Exception {
    int lookupsBefore = FlakyWorkspaceRepository.lookups();

    given()
        .get("/workspaces/container/999999/files")
        .then()
        .statusCode(404)
        .body(containsString("No workspace here."));

    // An absent row is an ANSWER, not a failure — the retry never sees it, so a 404 costs one
    // query and no wait. This is the assertion that keeps the retry narrow: widen it to "anything
    // that went wrong" and every unknown id would sit on the deadline before 404ing.
    assertEquals(
        1,
        FlakyWorkspaceRepository.lookups() - lookupsBefore,
        "a genuine absence must not be retried");
  }
}
