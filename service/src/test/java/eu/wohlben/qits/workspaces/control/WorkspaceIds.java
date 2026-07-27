package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Test-only lookup from the branch-derived label a fixture creates ({@code "work"}, {@code
 * "master"}) to the workspace's actual id, which is what the service surface takes.
 *
 * <p>It exists because the fixtures name workspaces the way a person does — by their label — while
 * the API addresses them by identity. Resolving here rather than threading the created {@code
 * Workspace} through every helper keeps the tests reading as they did.
 */
@ApplicationScoped
public class WorkspaceIds {

  @Inject WorkspaceRepository workspaces;

  /** The id of the ACTIVE workspace with this label in this repository. */
  @Transactional
  public Long of(String repoId, String label) {
    return workspaces
        .findActiveByRepositoryAndWorkspaceId(repoId, label)
        .orElseThrow(
            () -> new AssertionError("No active workspace '" + label + "' in repository " + repoId))
        .id;
  }
}
