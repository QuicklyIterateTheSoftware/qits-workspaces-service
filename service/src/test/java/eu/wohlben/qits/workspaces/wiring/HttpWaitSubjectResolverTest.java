package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.WaitSubjectResolver;
import eu.wohlben.qits.workspaces.control.WaitSubjectResolver.Resolved;
import eu.wohlben.qits.workspaces.control.WaitSubjects.Kind;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpWaitSubjectResolver}'s mapping (epic qits-1681) over stub clients: each kind, a 404, a
 * timeout, another status, and the expected-finish arithmetic with and without a prediction. The
 * wire itself — the paths, the bodies the real generated clients bind — is the contract tables'
 * ({@code ProjectsContract}, {@code CiContract}).
 */
class HttpWaitSubjectResolverTest {

  private static final Instant T0 = Instant.parse("2026-10-28T09:00:00Z");

  private final List<String> calls = new ArrayList<>();

  private HttpWaitSubjectResolver resolver(
      ProjectsReleaseRequests requests, ProjectsWorkItems items, CiRuns runs) {
    HttpWaitSubjectResolver resolver = new HttpWaitSubjectResolver();
    resolver.releaseRequests = requests;
    resolver.workItems = items;
    resolver.runs = runs;
    resolver.bearer =
        new IdpProjectsBearer() {
          @Override
          public Optional<String> authorization() {
            return Optional.of("Bearer t");
          }
        };
    resolver.overdueFactor = 2;
    resolver.overdueGrace = Duration.ofMinutes(15);
    resolver.unpredictedAfter = Duration.ofHours(2);
    return resolver;
  }

  private static <T> T fail() {
    throw new AssertionError("not this client");
  }

  @Test
  void aReleaseRequestIsReadUnderTheAgentsProject() {
    HttpWaitSubjectResolver resolver =
        resolver(
            (auth, project, ref) -> {
              calls.add(auth + " " + project + " " + ref);
              return new ProjectsReleaseRequests.RequestAnswer(
                  new ProjectsReleaseRequests.Request("u-1", "qits-x-rr-3", "PENDING", T0));
            },
            (auth, ref) -> fail(),
            (auth, ref) -> fail());
    Resolved r = resolver.resolve(Kind.RELEASE_REQUEST, "qits-x-rr-3", "p-1").orElseThrow();
    assertEquals(new Resolved("u-1", "qits-x-rr-3", "PENDING", T0, null), r);
    assertEquals(List.of("Bearer t p-1 qits-x-rr-3"), calls);
  }

  @Test
  void aReleaseRequestWithNoKnownProjectIsNotAsked() {
    HttpWaitSubjectResolver resolver =
        resolver((auth, project, ref) -> fail(), (auth, ref) -> fail(), (auth, ref) -> fail());
    WaitSubjectResolver.Unresolvable e =
        assertThrows(
            WaitSubjectResolver.Unresolvable.class,
            () -> resolver.resolve(Kind.RELEASE_REQUEST, "qits-x-rr-3", null));
    assertTrue(e.getMessage().contains("project"), e.getMessage());
  }

  @Test
  void aWorkItemHasItsStatusAndItsCommentsHaveNone() {
    HttpWaitSubjectResolver resolver =
        resolver(
            (auth, project, ref) -> fail(),
            (auth, ref) -> new ProjectsWorkItems.WorkItem("u-7", "qits-1702", "IMPLEMENTING", T0),
            (auth, ref) -> fail());
    assertEquals(
        new Resolved("u-7", "qits-1702", "IMPLEMENTING", T0, null),
        resolver.resolve(Kind.WORK_ITEM, "qits-1702", null).orElseThrow());
    assertEquals(
        new Resolved("u-7", "qits-1702", null, null, null),
        resolver.resolve(Kind.COMMENTS, "qits-1702", null).orElseThrow());
  }

  @Test
  void aRunHasItsStatusSinceItsLatestInstantAndAnExpectedFinish() {
    CiRuns.Run running = new CiRuns.Run("r-1", "RUNNING", T0, T0.plusSeconds(60), null, List.of(60_000L, 120_000L));
    CiRuns.Run finished =
        new CiRuns.Run("r-2", "SUCCESS", T0, T0.plusSeconds(60), T0.plusSeconds(600), null);
    CiRuns.Run queued = new CiRuns.Run("r-3", "QUEUED", T0, null, null, List.of());
    HttpWaitSubjectResolver resolver =
        resolver(
            (auth, project, ref) -> fail(),
            (auth, ref) -> fail(),
            (auth, ref) ->
                switch (ref) {
                  case "r-1" -> running;
                  case "r-2" -> finished;
                  default -> queued;
                });

    Resolved r1 = resolver.resolve(Kind.CI_RUN, "r-1", null).orElseThrow();
    assertEquals("r-1", r1.subjectId());
    assertNull(r1.subjectQualifiedId());
    assertEquals("RUNNING", r1.state());
    assertEquals(T0.plusSeconds(60), r1.stateAt());
    // started 09:01, 2 × 3 min predicted, + 15 min grace
    assertEquals(Instant.parse("2026-10-28T09:22:00Z"), r1.expectedBy());

    Resolved r2 = resolver.resolve(Kind.CI_RUN, "r-2", null).orElseThrow();
    assertEquals(T0.plusSeconds(600), r2.stateAt());
    assertEquals(T0.plus(Duration.ofHours(2)), r2.expectedBy(), "no prediction: createdAt + 2 h");

    Resolved r3 = resolver.resolve(Kind.CI_RUN, "r-3", null).orElseThrow();
    assertEquals(T0, r3.stateAt(), "neither started nor finished: since its creation");
    assertEquals(T0.plus(Duration.ofHours(2)), r3.expectedBy(), "an empty prediction is none");
  }

  @Test
  void expectedByCountsFromNowForAPredictedRunNotStartedYet() {
    Instant now = Instant.parse("2026-10-28T12:00:00Z");
    CiRuns.Run queued = new CiRuns.Run("r", "QUEUED", T0, null, null, List.of(30_000L));
    assertEquals(
        now.plusSeconds(60).plus(Duration.ofMinutes(15)),
        HttpWaitSubjectResolver.expectedBy(queued, now, 2, Duration.ofMinutes(15), Duration.ofHours(2)));
    assertEquals(
        now.plusSeconds(90).plus(Duration.ofMinutes(5)),
        HttpWaitSubjectResolver.expectedBy(queued, now, 3, Duration.ofMinutes(5), Duration.ofHours(2)),
        "the factor and the grace are configuration");
    CiRuns.Run unpredicted = new CiRuns.Run("r", "QUEUED", T0, null, null, null);
    assertEquals(
        T0.plus(Duration.ofHours(4)),
        HttpWaitSubjectResolver.expectedBy(unpredicted, now, 2, Duration.ofMinutes(15), Duration.ofHours(4)));
  }

  @Test
  void a404IsNoSuchSubjectAndATimeoutIsNoAnswer() {
    HttpWaitSubjectResolver resolver =
        resolver(
            (auth, project, ref) -> {
              throw new WebApplicationException(503);
            },
            (auth, ref) -> {
              throw new ProcessingException(new SocketTimeoutException("Read timed out"));
            },
            (auth, ref) -> {
              throw new WebApplicationException(404);
            });
    WaitSubjectResolver.Unresolvable missing =
        assertThrows(
            WaitSubjectResolver.Unresolvable.class, () -> resolver.resolve(Kind.CI_RUN, "r-9", null));
    assertEquals("qits-ci knows no run r-9.", missing.getMessage());

    WaitSubjectResolver.Unresolvable timeout =
        assertThrows(
            WaitSubjectResolver.Unresolvable.class,
            () -> resolver.resolve(Kind.WORK_ITEM, "qits-1702", null));
    assertTrue(
        timeout.getMessage().startsWith("qits-projects did not answer for the work item qits-1702: SocketTimeoutException"),
        timeout.getMessage());

    WaitSubjectResolver.Unresolvable refused =
        assertThrows(
            WaitSubjectResolver.Unresolvable.class,
            () -> resolver.resolve(Kind.RELEASE_REQUEST, "qits-x-rr-3", "p-1"));
    assertEquals("qits-projects answered 503 for the release request qits-x-rr-3.", refused.getMessage());
  }

  @Test
  void anOtherWaitResolvesNothing() {
    HttpWaitSubjectResolver resolver =
        resolver((auth, project, ref) -> fail(), (auth, ref) -> fail(), (auth, ref) -> fail());
    assertTrue(resolver.resolve(Kind.OTHER, null, null).isEmpty());
  }
}
