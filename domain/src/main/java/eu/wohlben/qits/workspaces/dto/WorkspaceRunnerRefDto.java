package eu.wohlben.qits.workspaces.dto;

import java.util.UUID;

/**
 * The runner a RUNNER workspace is placed on, as a workspace row shows it.
 *
 * @param id the runner's id
 * @param name its name; null when the runner row is gone (a resolved workspace keeps the id)
 */
public record WorkspaceRunnerRefDto(UUID id, String name) {}
