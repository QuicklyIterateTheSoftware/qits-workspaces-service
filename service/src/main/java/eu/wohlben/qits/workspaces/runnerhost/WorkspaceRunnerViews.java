package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunnerCapabilities;
import eu.wohlben.qits.workspaces.mapper.WorkspaceRunnerMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

/**
 * A runner as the runners page reads it: the row and the runner's last report (the domain's
 * mapper), with what only this module knows laid beside them — whether it is connected and since
 * when, the pinned version it is upgraded to, how many workspaces it runs, owns and queues, and
 * the login commands for its node (withheld until the runner has proven the current workspace
 * image is there — {@link RunnerLoginCommand}).
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
    var said = WorkspaceRunnerCapabilities.decode(runner.capabilities);
    String volume = WorkspaceRunnerCapabilities.text(said, WorkspaceRunnerCapabilities.DOT_CLAUDE_VOLUME);
    WorkspaceRunnerDto.Login login = WorkspaceRunnerMapper.login(said);
    Instant connectedSince = registry.connectedSince(runner.id);
    return runners.view(
        runner,
        new WorkspaceRunnerMapper.Live(
            registry.connected(runner.id),
            connectedSince,
            pins.version(),
            counts.running(),
            counts.owned(),
            counts.queued(),
            loginCommand.claude(volume, login, connectedSince, runner.registeredAt),
            loginCommand.kimi(volume, login, connectedSince, runner.registeredAt),
            loginCommand.pending(volume, login, connectedSince, runner.registeredAt)));
  }

  /** Every runner, by name. */
  public List<WorkspaceRunnerDto> views() {
    return runners.list().stream().map(this::view).toList();
  }
}
