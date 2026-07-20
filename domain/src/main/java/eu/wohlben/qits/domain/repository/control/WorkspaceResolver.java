package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.repository.entity.Workspace;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Resolves a {@code repoId}/{@code workspaceId} pair to the active {@link Workspace}, 404ing on an
 * unknown repository or workspace. Shared by the prompt-draft and prompt-attachment services (and
 * any future per-workspace endpoint) so the two-lookup shape and its 404 messages stay in lockstep
 * instead of being copy-pasted per service.
 */
@ApplicationScoped
public class WorkspaceResolver {

  @Inject RepositoryRepository repositoryRepository;

  @Inject WorkspaceRepository workspaceRepository;

  /** The active workspace behind {@code repoId}/{@code workspaceId}, or 404. */
  public Workspace resolveActive(String repoId, String workspaceId) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
    return workspaceRepository
        .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + workspaceId));
  }
}
