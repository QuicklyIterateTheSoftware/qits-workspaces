package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Resolves a {@code repoId}/{@code workspaceId} pair to the active {@link Workspace}, 404ing on an
 * unknown repository or workspace. Shared by every per-workspace entry point so the two-lookup
 * shape and its 404 messages stay in lockstep instead of being copy-pasted per service.
 */
@ApplicationScoped
public class WorkspaceResolver {

  @Inject RepositoryLookup repositories;

  @Inject WorkspaceRepository workspaceRepository;

  /** The active workspace behind {@code repoId}/{@code workspaceId}, or 404. */
  public Workspace resolveActive(String repoId, String workspaceId) {
    repositories.require(repoId);
    return workspaceRepository
        .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + workspaceId));
  }
}
