package eu.wohlben.qits.workspaces.mapper;

import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.entity.Workspace;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface WorkspaceMapper {

  @Mapping(source = "parent", target = "parent")
  @Mapping(target = "branch", ignore = true)
  @Mapping(target = "ahead", ignore = true)
  @Mapping(target = "behind", ignore = true)
  @Mapping(target = "conflictsWithParent", ignore = true)
  @Mapping(target = "clean", ignore = true)
  @Mapping(target = "daemonConnectedAt", ignore = true)
  @Mapping(target = "daemonVersion", ignore = true)
  @Mapping(target = "daemonBuildTime", ignore = true)
  @Mapping(target = "daemonOutdated", ignore = true)
  WorkspaceDto toDto(Workspace entity);
}
