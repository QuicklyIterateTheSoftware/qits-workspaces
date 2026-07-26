package eu.wohlben.qits.domain.service.mapper;

import eu.wohlben.qits.domain.service.dto.ServiceEventDto;
import eu.wohlben.qits.domain.service.entity.ServiceEvent;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface ServiceEventMapper {

  ServiceEventDto toDto(ServiceEvent event);
}
