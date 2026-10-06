package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.runner.protocol.Nothing;
import eu.wohlben.qits.workspaces.control.RunnerClaims;
import eu.wohlben.qits.workspaces.control.RunnerLaunchSpec;
import eu.wohlben.qits.workspaces.control.RunnerWorkspaceSpecs;
import eu.wohlben.qits.workspaces.control.WorkspaceAddressPlanes;
import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspacesrunner.protocol.Mount;
import eu.wohlben.qits.workspacesrunner.protocol.Take;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspaceSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * A runner's {@code reserve}, answered (qits-851): <b>reserve is the claim</b>. {@link
 * RunnerClaims#reserveFor} takes at most one QUEUED row for the runner in one compare-and-swap — its
 * sticky rows first, then never-placed ones, refused when the server's count of its live rows
 * fills its slots or when it is quarantined — and the answer is {@code take{rowId, spec}} for that
 * row, or {@code nothing}. qits-ci's {@code RunnerReservations} is the shape, without a driver
 * thread: the runner's {@code launched} or {@code launchFailed} is the rest of the conversation,
 * and the registry takes it.
 *
 * <p><b>The spec is the domain's</b> ({@link RunnerWorkspaceSpecs#compose}), mapped field for field
 * onto the protocol's {@link WorkspaceSpec}, every address and the image from the configured
 * {@link WorkspaceAddressPlanes#plane}. A row whose spec cannot be composed after it was claimed —
 * an {@code EdgePlaneUnconfigured} domain among the reasons — is failed on the spot ({@link
 * RunnerClaims#launchFailed}), so it frees its slot and says why, rather than sitting PROVISIONING.
 *
 * <p><b>The memory limits are read off the runner's row here, at the take</b> (qits-951): the
 * session's copy is the one it was greeted with, and an operator's edit since must reach this
 * launch. A row read fresh per take is qits-ci's reading of a step memory limit when the step
 * starts; a container already running keeps what it was launched with.
 *
 * <p>A draining connection — told to upgrade — takes nothing whatever it asks, and neither does a
 * deployment that knows no public domain to name the image by. After a take, the runner is sent a
 * fresh {@code estate}: the row is its own now.
 */
@ApplicationScoped
public class RunnerReservations {

  private static final Logger LOG = Logger.getLogger(RunnerReservations.class);

  @Inject RunnerClaims claims;

  @Inject RunnerWorkspaceSpecs specs;

  @Inject WorkspaceRunnerAddresses addresses;

  @Inject WorkspaceAddressPlanes planes;

  @Inject WorkspaceRunners runners;

  @Inject WorkspaceRunnerRegistry registry;

  /** Answer {@code session}'s {@code reserve} with {@code take} or {@code nothing}. */
  public void onReserve(WorkspaceRunnerRegistry.Session session) {
    if (session.draining() || !session.greeted()) {
      registry.send(session, new Nothing());
      return;
    }
    if (!addresses.configured()) {
      LOG.warnf(
          "Runner %s asked for work and this deployment knows no public domain to name the"
              + " workspace image by; answering nothing",
          session.runnerName());
      registry.send(session, new Nothing());
      return;
    }
    Optional<Workspace> claimed;
    try {
      claimed = claims.reserveFor(session.runner());
    } catch (RuntimeException e) {
      // A claim that could not be made is a nothing: the runner parks until its next backlog.
      LOG.warnf(e, "Runner %s's reservation failed; answering nothing", session.runnerName());
      registry.send(session, new Nothing());
      return;
    }
    if (claimed.isEmpty()) {
      registry.send(session, new Nothing());
      return;
    }
    Workspace row = claimed.orElseThrow();
    WorkspaceSpec spec;
    try {
      spec = wire(specs.compose(row, planes.plane(), runners.get(row.runnerId)));
    } catch (RuntimeException uncomposable) {
      LOG.errorf(
          "Runner %s took workspace %d and its launch spec could not be composed: %s",
          session.runnerName(), row.id, uncomposable.getMessage());
      claims.launchFailed(
          session.runnerId(), row.id, "the launch spec could not be composed: " + uncomposable);
      registry.send(session, new Nothing());
      return;
    }
    if (!registry.send(session, new Take(row.id, spec))) {
      // Still PROVISIONING on this runner: its next inventory, which will not hold the row, puts it
      // back in the queue — sticky to this runner.
      LOG.warnf(
          "Runner %s took workspace %d and the take did not reach it; its next inventory requeues"
              + " it",
          session.runnerName(), row.id);
    }
    registry.estateChanged(session.runnerId());
  }

  /** The domain's launch spec as the protocol carries it, field for field. */
  static WorkspaceSpec wire(RunnerLaunchSpec spec) {
    List<Mount> mounts = new ArrayList<>();
    for (RunnerLaunchSpec.Mount mount : spec.mounts()) {
      mounts.add(new Mount(Mount.Volume.valueOf(mount.volume().name()), mount.target()));
    }
    RunnerLaunchSpec.Limits limits = spec.limits();
    return new WorkspaceSpec(
        spec.image(),
        spec.env(),
        mounts,
        spec.labels(),
        limits == null ? null : limits.memory(),
        limits == null ? null : limits.memorySwap(),
        limits == null ? null : pids(limits.pids()),
        limits == null ? null : limits.cpus(),
        limits == null ? null : limits.oomScoreAdj(),
        spec.init());
  }

  /** The domain keeps the pids limit as docker's text; the wire carries it as a number. */
  private static Long pids(String pids) {
    if (pids == null || pids.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(pids.trim());
    } catch (NumberFormatException notANumber) {
      throw new IllegalStateException("the configured pids limit '" + pids + "' is not a number");
    }
  }
}
