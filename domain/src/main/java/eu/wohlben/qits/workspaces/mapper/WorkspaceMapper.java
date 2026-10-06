package eu.wohlben.qits.workspaces.mapper;

import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.entity.Workspace;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface WorkspaceMapper {

  @Mapping(source = "parent", target = "parent")
  @Mapping(target = "branch", ignore = true)
  // The create response is a thin view of the row and has never carried the computed fields; the
  // repository's default branch is one more of them. The caller reads the full shape from
  // GET /workspaces/{id} or the listing.
  @Mapping(target = "repositoryMainBranch", ignore = true)
  @Mapping(target = "ahead", ignore = true)
  @Mapping(target = "behind", ignore = true)
  @Mapping(target = "conflictsWithParent", ignore = true)
  @Mapping(target = "clean", ignore = true)
  @Mapping(target = "daemonConnectedAt", ignore = true)
  @Mapping(target = "daemonVersion", ignore = true)
  @Mapping(target = "daemonBuildTime", ignore = true)
  @Mapping(target = "daemonOutdated", ignore = true)
  // The runner's name is another table's; this thin view carries none. A row is never placed on a
  // runner at create anyway: a runner takes it later, from the queue.
  @Mapping(target = "runner", ignore = true)
  // `editor` is the row's own column and maps by name.
  WorkspaceDto toDto(Workspace entity);
}
