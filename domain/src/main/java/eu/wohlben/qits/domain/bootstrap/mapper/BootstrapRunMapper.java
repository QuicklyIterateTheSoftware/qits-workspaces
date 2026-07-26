package eu.wohlben.qits.domain.bootstrap.mapper;

import eu.wohlben.qits.domain.bootstrap.dto.BootstrapRunDto;
import eu.wohlben.qits.domain.bootstrap.entity.BootstrapRun;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface BootstrapRunMapper {

  BootstrapRunDto toDto(BootstrapRun run);
}
