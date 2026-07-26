package eu.wohlben.qits.workspaces.mapper;

import eu.wohlben.qits.workspaces.dto.BootstrapRunDto;
import eu.wohlben.qits.workspaces.entity.BootstrapRun;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface BootstrapRunMapper {

  BootstrapRunDto toDto(BootstrapRun run);
}
