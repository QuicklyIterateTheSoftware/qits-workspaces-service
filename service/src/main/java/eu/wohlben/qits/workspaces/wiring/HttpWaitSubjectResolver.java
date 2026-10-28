package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.WaitSubjectResolver;
import eu.wohlben.qits.workspaces.control.WaitSubjects;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * {@link WaitSubjectResolver} over the subjects' own doors (epic qits-1681, "Resolution at open"):
 *
 * <ul>
 *   <li>a release request: qits-projects' {@code getProjectReleaseRequest} ({@link
 *       ProjectsReleaseRequests}), under the agent's project — its id, qualified id, {@code state}
 *       and {@code updatedAt};
 *   <li>a work item or its comments: qits-projects' {@code getWork} ({@link ProjectsWorkItems}) —
 *       its id, qualified id and, for an item, {@code status} since {@code updatedAt};
 *   <li>a run: qits-ci's {@code getRun} ({@link CiRuns}) — its id, {@code status} since {@code
 *       finishedAt ?: startedAt ?: createdAt}, and when it should have finished ({@link
 *       #expectedBy}).
 * </ul>
 *
 * Every failure is a {@link WaitSubjectResolver.Unresolvable} naming it in one sentence: a 404 is
 * "no such subject", another status is what the provider answered, a transport failure (a timeout,
 * a refused connection) is "did not answer". Each call carries the {@code qits} oidc client's bearer.
 * {@code @DefaultBean}, so a suite's double wins without ambiguity.
 */
@ApplicationScoped
@DefaultBean
public class HttpWaitSubjectResolver implements WaitSubjectResolver {

  @Inject @RestClient ProjectsReleaseRequests releaseRequests;

  @Inject @RestClient ProjectsWorkItems workItems;

  @Inject @RestClient CiRuns runs;

  @Inject IdpProjectsBearer bearer;

  /** A predicted run is overdue after this many times its predicted duration (plus the grace). */
  @ConfigProperty(name = "qits.workspaces.attention.run-overdue-factor", defaultValue = "2")
  double overdueFactor;

  /** Added to a predicted run's expected finish. */
  @ConfigProperty(name = "qits.workspaces.attention.run-overdue-grace", defaultValue = "PT15M")
  Duration overdueGrace;

  /** A run qits-ci predicts nothing for is expected to have finished this long after its creation. */
  @ConfigProperty(name = "qits.workspaces.attention.run-unpredicted-after", defaultValue = "PT2H")
  Duration unpredictedAfter;

  @Override
  public Optional<Resolved> resolve(WaitSubjects.Kind kind, String ref, String projectId) {
    Objects.requireNonNull(kind, "kind");
    return switch (kind) {
      case RELEASE_REQUEST -> Optional.of(releaseRequest(ref, projectId));
      case WORK_ITEM, COMMENTS -> Optional.of(workItem(kind, ref));
      case CI_RUN -> Optional.of(run(ref));
      case OTHER -> Optional.empty();
    };
  }

  private Resolved releaseRequest(String ref, String projectId) {
    if (projectId == null || projectId.isBlank()) {
      throw new Unresolvable(
          "The release request " + ref + " was not looked up: qits-projects did not name the agent's project.");
    }
    ProjectsReleaseRequests.RequestAnswer answer =
        call(
            "qits-projects",
            "release request " + ref,
            () -> releaseRequests.get(authorization(), projectId, ref));
    ProjectsReleaseRequests.Request request = answer == null ? null : answer.request();
    if (request == null || request.id() == null) {
      throw new Unresolvable("qits-projects answered no release request for " + ref + ".");
    }
    return new Resolved(request.id(), request.qualifiedId(), request.state(), request.updatedAt(), null);
  }

  private Resolved workItem(WaitSubjects.Kind kind, String ref) {
    ProjectsWorkItems.WorkItem item =
        call("qits-projects", "work item " + ref, () -> workItems.get(authorization(), ref));
    if (item == null || item.id() == null) {
      throw new Unresolvable("qits-projects answered no work item for " + ref + ".");
    }
    if (kind == WaitSubjects.Kind.COMMENTS) {
      return new Resolved(item.id(), item.qualifiedId(), null, null, null);
    }
    return new Resolved(item.id(), item.qualifiedId(), item.status(), item.updatedAt(), null);
  }

  private Resolved run(String ref) {
    CiRuns.Run run = call("qits-ci", "run " + ref, () -> runs.get(authorization(), ref));
    if (run == null || run.id() == null) {
      throw new Unresolvable("qits-ci answered no run for " + ref + ".");
    }
    Instant stateAt =
        run.finishedAt() != null
            ? run.finishedAt()
            : run.startedAt() != null ? run.startedAt() : run.createdAt();
    return new Resolved(
        run.id(),
        null,
        run.status(),
        stateAt,
        expectedBy(run, Instant.now(), overdueFactor, overdueGrace, unpredictedAfter));
  }

  /**
   * When a run should have finished. With a prediction ({@code expectedStepDurationsMillis}
   * non-empty): {@code (startedAt ?: now) + factor × Σ steps + grace}. Without:
   * {@code (createdAt ?: now) + unpredicted}.
   */
  static Instant expectedBy(
      CiRuns.Run run, Instant now, double factor, Duration grace, Duration unpredicted) {
    List<Long> steps = run.expectedStepDurationsMillis();
    if (steps != null && !steps.isEmpty()) {
      long predicted = steps.stream().filter(Objects::nonNull).mapToLong(Long::longValue).sum();
      Instant from = run.startedAt() != null ? run.startedAt() : now;
      return from.plusMillis(Math.round(factor * predicted)).plus(grace);
    }
    return (run.createdAt() != null ? run.createdAt() : now).plus(unpredicted);
  }

  /** The call, with every failure as one sentence naming the provider and the subject. */
  private static <T> T call(String provider, String subject, Supplier<T> call) {
    try {
      return call.get();
    } catch (WebApplicationException http) {
      int status = http.getResponse() == null ? 0 : http.getResponse().getStatus();
      if (status == 404) {
        throw new Unresolvable(provider + " knows no " + subject + ".", http);
      }
      throw new Unresolvable(provider + " answered " + status + " for the " + subject + ".", http);
    } catch (Unresolvable already) {
      throw already;
    } catch (RuntimeException transport) {
      throw new Unresolvable(
          provider + " did not answer for the " + subject + ": " + describe(transport), transport);
    }
  }

  private static String describe(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    return root.getClass().getSimpleName() + (message == null ? "" : " (" + message + ")");
  }

  private String authorization() {
    return bearer.authorization().orElse(null);
  }
}
