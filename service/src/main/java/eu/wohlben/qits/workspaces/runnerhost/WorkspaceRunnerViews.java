package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunnerCapabilities;
import eu.wohlben.qits.workspaces.mapper.WorkspaceRunnerMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * A runner as the runners page reads it: the row and the runner's last report (the domain's
 * mapper), with what only this module knows laid beside them — whether it is connected and since
 * when, the pinned version it is upgraded to, how many workspaces it runs, owns and queues, and
 * the login commands for its node.
 */
@ApplicationScoped
public class WorkspaceRunnerViews {

  @Inject WorkspaceRunners runners;

  @Inject WorkspaceRunnerRegistry registry;

  @Inject WorkspaceRunnerPins pins;

  @Inject RunnerLoginCommand loginCommand;

  /** {@code runner}, as the page reads it. */
  public WorkspaceRunnerDto view(WorkspaceRunner runner) {
    WorkspaceRunners.Counts counts = runners.counts(runner.id);
    String volume =
        WorkspaceRunnerCapabilities.text(
            WorkspaceRunnerCapabilities.decode(runner.capabilities),
            WorkspaceRunnerCapabilities.DOT_CLAUDE_VOLUME);
    return runners.view(
        runner,
        new WorkspaceRunnerMapper.Live(
            registry.connected(runner.id),
            registry.connectedSince(runner.id),
            pins.version(),
            counts.running(),
            counts.owned(),
            counts.queued(),
            loginCommand.claude(volume),
            loginCommand.kimi(volume)));
  }

  /** Every runner, by name. */
  public List<WorkspaceRunnerDto> views() {
    return runners.list().stream().map(this::view).toList();
  }
}
