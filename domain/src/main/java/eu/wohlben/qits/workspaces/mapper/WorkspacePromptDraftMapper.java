package eu.wohlben.qits.workspaces.mapper;

import eu.wohlben.qits.workspaces.dto.WorkspacePromptDraftDto;
import eu.wohlben.qits.workspaces.entity.WorkspacePromptDraft;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface WorkspacePromptDraftMapper {

  WorkspacePromptDraftDto toDto(WorkspacePromptDraft entity);
}
