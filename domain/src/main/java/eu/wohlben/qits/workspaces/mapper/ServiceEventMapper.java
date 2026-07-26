package eu.wohlben.qits.workspaces.mapper;

import eu.wohlben.qits.workspaces.dto.ServiceEventDto;
import eu.wohlben.qits.workspaces.entity.ServiceEvent;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface ServiceEventMapper {

  ServiceEventDto toDto(ServiceEvent event);
}
