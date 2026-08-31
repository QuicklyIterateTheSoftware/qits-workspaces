package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The one lookup the editor proxy performs: an editor origin → the workspace that serves it, or
 * nothing.
 *
 * <p>The sibling of {@link DaemonProxyTargets}, deliberately the same shape and with the same
 * posture. <b>Nothing about the request selects an address.</b> The forwarded host names a project
 * label; the label selects a repository out of this service's own rows and the registry it already
 * depends on; the repository selects a workspace row. What that row's container is called and what
 * port to reach it on are derived from the row exactly as they are for the daemon proxy — a
 * component of a request that could name an origin would be an SSRF primitive aimed at everything on
 * the platform network.
 *
 * <p><b>An unknown label is nothing, and nothing is a 404 with no connection made.</b> Not a
 * redirect, not a default project, not the only project this platform happens to have. The caller
 * answers before it dials, so a name nobody registered costs one query and reaches no container.
 *
 * <h2>How a label reaches a repository, and why it is shaped like this</h2>
 *
 * <p>There is no route from a project slug to a project: the registry answers repositories by id and
 * by {@code (projectId, name)}, and this context holds no project table. What it holds instead is the
 * rule qits-projects names a wrapper by — {@code <slug>-<slug>}, immutable because the slug is —
 * so the label alone <em>recognises</em> the wrapper among the repositories this service already has
 * root workspaces for. That candidate set is one id per repository somebody has opened a main
 * workspace for, which is tens of rows on a real platform and not a scan of anything.
 *
 * <p><b>The registry half is remembered and the row half never is.</b> A project's wrapper repository
 * does not change — the slug is {@code updatable = false} and the name is derived from it — so
 * recognising it once is recognising it for good, and a warm resolution is a map read plus one
 * indexed query. The workspace row is re-read every time, because that half <em>does</em> move: a
 * main workspace can be discarded and created again, and a cached row id would send the proxy at a
 * workspace that no longer exists. Negative answers are not remembered at all — a project registered
 * a minute from now must resolve then.
 */
@ApplicationScoped
public class EditorProxyTargets {

  private static final Logger LOG = Logger.getLogger(EditorProxyTargets.class);

  @Inject WorkspaceRepository workspaces;

  @Inject RepositoryLookup repositories;

  /** Project label → the wrapper repository's id. Immutable facts; see the class note. */
  private final Map<String, String> wrappers = new ConcurrentHashMap<>();

  /**
   * The workspace an editor origin addresses.
   *
   * @param workspaceRowId the row — the id every route, the ports and the container name use
   * @param repositoryId the wrapper repository the workspace branches
   * @param workspaceId the branch-derived label the container name is built from
   * @param projectLabel the label the host named, echoed back so a log line says which project
   */
  public record EditorTarget(
      Long workspaceRowId, String repositoryId, String workspaceId, String projectLabel) {}

  /**
   * Resolve an editor origin to its workspace, or empty.
   *
   * <p>{@code @Transactional} because the row reads need a session and the caller is a raw Vert.x
   * route with none — which is also why the route runs this on a worker thread, exactly as {@code
   * ServiceProxyRoute} runs its supervisor lookup off the event loop.
   *
   * @param forwardedHost the raw {@code X-Forwarded-Host}; the first entry is the one that counts
   */
  @Transactional
  public Optional<EditorTarget> resolve(String forwardedHost) {
    Optional<String> label = EditorHost.projectLabel(forwardedHost);
    if (label.isEmpty()) {
      return Optional.empty();
    }
    return resolveLabel(label.get());
  }

  /** The same resolution from a label already parsed — what the host parsing hands over. */
  @Transactional
  public Optional<EditorTarget> resolveLabel(String projectLabel) {
    Optional<String> repositoryId = wrapperRepository(projectLabel);
    if (repositoryId.isEmpty()) {
      return Optional.empty();
    }
    return mainWorkspace(repositoryId.get())
        .map(
            workspace ->
                new EditorTarget(
                    workspace.id, workspace.repositoryId, workspace.workspaceId, projectLabel));
  }

  /**
   * The project's wrapper repository, recognised by the name its slug derives — remembered once
   * found, never remembered when not.
   */
  private Optional<String> wrapperRepository(String projectLabel) {
    String remembered = wrappers.get(projectLabel);
    if (remembered != null) {
      return Optional.of(remembered);
    }
    String wrapperName = EditorHost.wrapperRepositoryName(projectLabel);
    for (String repositoryId : workspaces.activeRootRepositoryIds()) {
      Optional<RepositoryLookup.RepositoryView> view;
      try {
        view = repositories.find(repositoryId);
      } catch (RuntimeException unreachable) {
        // "Could not ask" is not "not there", and the difference matters here the way it does
        // everywhere this port is read — but there is no caller to tell, because the answer this
        // method gives is a 404 either way. Say so in the log and keep looking: another candidate
        // may resolve, and a blip must not be remembered as an absence.
        LOG.debugf(
            "could not read repository %s while resolving the editor origin for project %s: %s",
            repositoryId, projectLabel, unreachable.getMessage());
        continue;
      }
      if (view.isEmpty()) {
        continue;
      }
      RepositoryLookup.RepositoryView repository = view.get();
      if (repository.isWrapper()
          && repository.name() != null
          && wrapperName.equalsIgnoreCase(repository.name())) {
        wrappers.put(projectLabel, repositoryId);
        return Optional.of(repositoryId);
      }
    }
    return Optional.empty();
  }

  /**
   * The wrapper's main workspace — the ACTIVE row claiming that repository's main branch, which is
   * the per-project singleton {@code createMainWorkspace} maintains. Re-read on every call, because
   * a workspace row is the half of this resolution that moves.
   */
  private Optional<Workspace> mainWorkspace(String repositoryId) {
    Optional<RepositoryLookup.RepositoryView> view;
    try {
      view = repositories.find(repositoryId);
    } catch (RuntimeException unreachable) {
      LOG.debugf(
          "could not read repository %s while resolving its editor workspace: %s",
          repositoryId, unreachable.getMessage());
      return Optional.empty();
    }
    return view.map(RepositoryLookup.RepositoryView::mainBranch)
        .filter(branch -> branch != null && !branch.isBlank())
        .flatMap(branch -> workspaces.findActiveByRepositoryAndBranch(repositoryId, branch));
  }
}
