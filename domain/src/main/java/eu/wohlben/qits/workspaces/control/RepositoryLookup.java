package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.NotFoundException;
import java.util.List;
import java.util.Optional;

/**
 * The owning application's repository registry, narrowed to what the workspaces context actually
 * needs of it: does this repository exist, which project owns it, what is it called, and what is
 * its main branch.
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
   * <p>{@code projectId} and {@code name} are here for one caller and one reason: {@code
   * SCMRelease} names the project a release belongs to and the repository a CI selection can
   * address, and the flow that publishes it holds a repository id and nothing else. {@code name}
   * is the registered name — the coordinate that is the same on every platform instance, while
   * {@code id} is whatever that instance's registry minted (a manifest repository's id equals its
   * name, a self-seeded one's is a UUID). Both are nullable — a registry that does not answer with
   * one costs the event a field, never the release. The workspace daemon also receives both so its
   * name-addressed clone lets committed relative submodule URLs resolve to sibling repositories.
   *
   * <p>{@code archetype} joined for the web editor, and it is the fifth field for one question:
   * <b>is this repository a project's wrapper</b> ({@code PROJECT}). Paired with {@code mainBranch}
   * it is the whole of {@link WorkspacePostures#isWrapperMain}, which is what decides that a
   * workspace runs the richer editor image rather than the plain one. It is a plain String and not
   * an enum for the reason {@code QitsConfig.RepositorySection}'s is: the vocabulary belongs to
   * whoever owns repositories, and an archetype this context has never heard of must round-trip as
   * "not a wrapper" rather than fail a launch. Nullable like the two above — a registry that does
   * not answer with one costs the editor, never the workspace.
   */
  record RepositoryView(String id, String name, String projectId, String mainBranch, String archetype) {

    /**
     * The four-field form every caller predating the editor spells — no archetype, which reads as
     * "not a wrapper" wherever the question is asked. Kept so a stub or a fixture that only cares
     * about the branch stays a one-line constructor.
     */
    public RepositoryView(String id, String name, String projectId, String mainBranch) {
      this(id, name, projectId, mainBranch, null);
    }

    /** The archetype qits-projects gives a project's wrapper repository — the superproject. */
    public static final String WRAPPER_ARCHETYPE = "PROJECT";

    /** Whether this repository is its project's wrapper. Absent, blank and unknown are all false. */
    public boolean isWrapper() {
      return archetype != null && WRAPPER_ARCHETYPE.equalsIgnoreCase(archetype.trim());
    }
  }

  /** The repository behind {@code repoId}, or empty when it does not exist. */
  Optional<RepositoryView> find(String repoId);

  /**
   * The repository a project-scoped <b>name</b> addresses — {@code (projectId, name)}, the public
   * identity — or empty when that project holds no repository by that name.
   *
   * <p>The row id is opaque and per-instance; the name is the coordinate a pipeline, a clone url
   * and a human all spell. So a caller that has a name must be able to reach a repository without
   * ever learning the id, and this is the seam that lets it: {@code POST /branches/release} takes
   * {@code projectId} + {@code repositoryName} and resolves here.
   *
   * <p><b>Empty means "no such name", never "could not ask"</b> — the same distinction {@link
   * #find} draws and for the same reason: the caller turns empty into a 404, so an unreachable
   * registry has to throw instead of reporting a live repository as absent.
   *
   * <p>A {@code default} for the reason {@link #listByProject} is one: {@link #find} stays the
   * single abstract method, so a stub can still be written as a lambda. Answering empty is a
   * supported implementation here — an embedding with no alias table has no names to resolve, and a
   * caller that addresses by id never asks.
   */
  default Optional<RepositoryView> findByName(String projectId, String name) {
    return Optional.empty();
  }

  /**
   * Every repository registered in a project — what resolves a wrapper's committed submodule urls
   * to repositories this service may branch.
   *
   * <p>A {@code default} only because {@link #find} is the single abstract method a stub is written
   * as a lambda against. Answering empty is not a supported implementation: a project always holds
   * at least the repository being asked about, so {@code WorkspaceService} reads an empty list as a
   * registry that did not answer and refuses — branching a wrapper alone and leaving every
   * submodule behind reads as success and is not.
   */
  default List<RepositoryView> listByProject(String projectId) {
    return List.of();
  }

  /** {@link #find} or 404 — the guard nearly every workspace entry point opens with. */
  default RepositoryView require(String repoId) {
    return find(repoId).orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
  }
}
