package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.NotFoundException;
import java.util.Optional;

/**
 * The owning application's repository registry, narrowed to what the workspaces context actually
 * needs of it: does this repository exist, which project owns it, and what is its main branch.
 *
 * <p>A workspace has no meaning without a repository to branch from, but this context deliberately
 * holds no foreign key into the repositories tables (see {@link
 * eu.wohlben.qits.workspaces.entity.Workspace#repositoryId}). This port is the seam that replaces
 * it: the two facts are pulled through an interface instead of a join, so the workspaces schema
 * stays independently migratable and this jar carries no repositories code.
 *
 * <p>Unlike the workspace-daemon SPIs, which are injected as {@code Instance<T>} because a daemon
 * genuinely may not be connected, this one is a <strong>mandatory</strong> {@code @Inject}: an
 * application that pulls this jar in without implementing it is misconfigured, and should fail at
 * startup rather than 404 every workspace at runtime.
 */
public interface RepositoryLookup {

  /**
   * The repository facts this context reads.
   *
   * <p>{@code projectId} is here for one caller and one reason: {@code SoftwareRelease} names the
   * project a release belongs to, and the flow that publishes it holds a repository id and nothing
   * else. It is nullable — a registry that does not answer with one costs the event a field, never
   * the release.
   */
  record RepositoryView(String id, String projectId, String mainBranch) {}

  /** The repository behind {@code repoId}, or empty when it does not exist. */
  Optional<RepositoryView> find(String repoId);

  /** {@link #find} or 404 — the guard nearly every workspace entry point opens with. */
  default RepositoryView require(String repoId) {
    return find(repoId).orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
  }
}
