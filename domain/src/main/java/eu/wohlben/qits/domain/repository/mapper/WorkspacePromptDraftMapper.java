package eu.wohlben.qits.domain.repository.mapper;

import eu.wohlben.qits.domain.repository.dto.WorkspacePromptDraftDto;
import eu.wohlben.qits.domain.repository.entity.WorkspacePromptDraft;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface WorkspacePromptDraftMapper {

  WorkspacePromptDraftDto toDto(WorkspacePromptDraft entity);
}
