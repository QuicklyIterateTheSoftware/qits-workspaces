package eu.wohlben.qits.domain.repository.mapper;

import eu.wohlben.qits.domain.repository.dto.WorkspaceDto;
import eu.wohlben.qits.domain.repository.entity.Workspace;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface WorkspaceMapper {

  @Mapping(source = "parent", target = "parent")
  @Mapping(target = "branch", ignore = true)
  @Mapping(target = "ahead", ignore = true)
  @Mapping(target = "behind", ignore = true)
  @Mapping(target = "conflictsWithParent", ignore = true)
  WorkspaceDto toDto(Workspace entity);
}
