package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The runner suites' rows: registered runners and RUNNER workspace rows, written straight through
 * the domain and taken back by {@link #clear}. A plain helper rather than a bean, so it is no
 * bean for every other {@code @QuarkusTest} of the module.
 */
public final class RunnerRows {

  private final WorkspaceRunners runners = Arc.container().instance(WorkspaceRunners.class).get();
  private final WorkspaceRunnerRepository runnerRepository =
      Arc.container().instance(WorkspaceRunnerRepository.class).get();
  private final WorkspaceRepository workspaces =
      Arc.container().instance(WorkspaceRepository.class).get();

  private final List<UUID> createdRunners = new ArrayList<>();
  private final List<Long> rows = new ArrayList<>();

  /** A registered runner whose client is {@code clientId}, quarantined as registration leaves it. */
  public WorkspaceRunner registered(String clientId, int slots) {
    UUID id = UUID.randomUUID();
    runners.create(
        id, "r-" + id.toString().substring(0, 8), null, slots, "token-" + id, "sub-" + id);
    createdRunners.add(id);
    return runners.markRegistered(id, clientId, null);
  }

  /** {@link #registered}, then greenlit: a runner in service. */
  public WorkspaceRunner eligible(String clientId, int slots) {
    registered(clientId, slots);
    return runners.greenlight(createdRunners.get(createdRunners.size() - 1));
  }

  /** A runner declared and not registered yet, holding registration token subject {@code sub}. */
  public WorkspaceRunner declared(String sub, int slots) {
    UUID id = UUID.randomUUID();
    WorkspaceRunner runner =
        runners.create(id, "r-" + id.toString().substring(0, 8), null, slots, "token-" + id, sub);
    createdRunners.add(id);
    return runner;
  }

  /** Remembers a runner a test created another way, so {@link #clear} takes it too. */
  public void track(UUID runnerId) {
    createdRunners.add(runnerId);
  }

  /** An ACTIVE RUNNER row QUEUED for {@code runnerId} (null: never placed). */
  public Long queued(UUID runnerId, Instant queuedAt) {
    return insert(
        w -> {
          w.runnerId = runnerId;
          w.runtimeStatus = WorkspaceRuntimeStatus.QUEUED;
          w.queuedAt = queuedAt;
        });
  }

  /** An ACTIVE RUNNER row on {@code runnerId} in {@code status}. */
  public Long placedOn(UUID runnerId, WorkspaceRuntimeStatus status) {
    return insert(
        w -> {
          w.runnerId = runnerId;
          w.runtimeStatus = status;
        });
  }

  /** The workspace image version a runner is told: the running application's own. */
  public String imageVersion() {
    return Arc.container()
        .instance(eu.wohlben.qits.workspaces.control.WorkspaceContainerFactory.class)
        .get()
        .imageVersion();
  }

  public Workspace read(Long rowId) {
    return QuarkusTransaction.requiringNew().call(() -> workspaces.findById(rowId));
  }

  public WorkspaceRunner runner(UUID runnerId) {
    return QuarkusTransaction.requiringNew().call(() -> runnerRepository.findById(runnerId));
  }

  /** Resolves every row this helper wrote and deletes every runner. */
  public void clear() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                rows.forEach(
                    id ->
                        workspaces
                            .findByIdOptional(id)
                            .ifPresent(w -> w.status = WorkspaceStatus.ABANDONED)));
    QuarkusTransaction.requiringNew()
        .run(() -> createdRunners.forEach(runnerRepository::deleteById));
    rows.clear();
    createdRunners.clear();
  }

  private Long insert(Consumer<Workspace> shape) {
    Long id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace workspace = new Workspace();
                  String label = "w" + UUID.randomUUID().toString().substring(0, 8);
                  workspace.workspaceId = label;
                  workspace.repositoryId = "repo-" + label;
                  workspace.branch = label;
                  workspace.status = WorkspaceStatus.ACTIVE;
                  workspace.placement = WorkspacePlacement.RUNNER;
                  workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                  // What a RUNNER start writes before it queues (qits-625): no row is claimable
                  // without its workspace token.
                  workspace.commissionedTokenId = "tok-id-" + label;
                  workspace.commissionedTokenSubject = "tok-workspace-" + label;
                  workspace.commissionedToken = "qits_tok_" + label;
                  shape.accept(workspace);
                  workspaces.persist(workspace);
                  workspaces.flush();
                  return workspace.id;
                });
    rows.add(id);
    return id;
  }
}
