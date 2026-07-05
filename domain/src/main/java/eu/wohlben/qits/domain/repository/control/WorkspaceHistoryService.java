package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.command.mapper.CommandMapper;
import eu.wohlben.qits.domain.command.persistence.CommandRepository;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.repository.dto.WorkspaceEventDto;
import eu.wohlben.qits.domain.repository.dto.WorkspaceHistoryDetailDto;
import eu.wohlben.qits.domain.repository.dto.WorkspaceHistoryDto;
import eu.wohlben.qits.domain.repository.entity.Workspace;
import eu.wohlben.qits.domain.repository.entity.WorkspaceEvent;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceEventRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * Read/edit side of the workspace history: lists all workspaces (active + resolved) for a
 * repository and assembles a single workspace's full record — its narrative, event timeline, and
 * the commands that ran in it. Keyed by the surrogate id, since {@code workspaceId} is reusable
 * once resolved.
 */
@ApplicationScoped
public class WorkspaceHistoryService {

  @Inject WorkspaceRepository workspaceRepository;

  @Inject WorkspaceEventRepository workspaceEventRepository;

  @Inject CommandRepository commandRepository;

  @Inject CommandMapper commandMapper;

  @Transactional
  public List<WorkspaceHistoryDto> list(String repoId) {
    return workspaceRepository.findByRepositoryId(repoId).stream()
        .map(
            wt ->
                new WorkspaceHistoryDto(
                    wt.id, wt.workspaceId, wt.parent, wt.status, wt.createdAt, wt.resolvedAt))
        .toList();
  }

  @Transactional
  public WorkspaceHistoryDetailDto get(String repoId, Long id) {
    Workspace workspace = requireWorkspace(repoId, id);
    List<WorkspaceEventDto> events =
        workspaceEventRepository.findByWorkspaceOrderByAt(id).stream()
            .map(WorkspaceHistoryService::toEventDto)
            .toList();
    var commands =
        commandRepository.findByWorkspace(id).stream().map(commandMapper::toDto).toList();
    return new WorkspaceHistoryDetailDto(
        workspace.id,
        workspace.workspaceId,
        workspace.parent,
        workspace.status,
        workspace.preamble,
        workspace.result,
        workspace.createdAt,
        workspace.resolvedAt,
        events,
        commands);
  }

  /** Edit the markdown narrative; null fields are left unchanged. */
  @Transactional
  public WorkspaceHistoryDetailDto updateNarrative(
      String repoId, Long id, String preamble, String result) {
    Workspace workspace = requireWorkspace(repoId, id);
    if (preamble != null) {
      workspace.preamble = preamble;
    }
    if (result != null) {
      workspace.result = result;
    }
    return get(repoId, id);
  }

  private Workspace requireWorkspace(String repoId, Long id) {
    Workspace workspace = workspaceRepository.findById(id);
    if (workspace == null || !workspace.repository.id.equals(repoId)) {
      throw new NotFoundException("Workspace not found: " + id);
    }
    return workspace;
  }

  private static WorkspaceEventDto toEventDto(WorkspaceEvent e) {
    return new WorkspaceEventDto(e.type, e.branch, e.parent, e.target, e.commit, e.note, e.at);
  }
}
