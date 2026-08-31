package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
 * workspace that no longer exists.
 *
 * <p><b>A miss is remembered too, and it is a different kind of thing from a remembered hit.</b> The
 * scan behind a miss is one {@code find} per root repository — N registry round trips, on every
 * request, for any label shaped like a slug: a browser sitting on an editor origin whose main
 * workspace does not exist yet reloads twice a second, and a platform with thirty root repositories
 * pays sixty qits-projects calls a second to keep saying 404. A hit may be kept forever because the
 * fact behind it is immutable; a miss may not, because a project registered a minute from now must
 * resolve then.
 *
 * <p>So a miss is kept <b>against the candidate set it was computed over</b>, and that is what makes
 * it exact rather than merely short: the set is one indexed local query, everything the scan reads
 * about a repository in it is immutable, so the answer can only have changed if the set has. A main
 * workspace created a second ago therefore resolves at once instead of waiting out a window — which
 * matters, because that miss is a 404 page and not the splash that reloads itself. {@code
 * qits.editor.label-miss-ttl-ms} sits underneath as a backstop, for a label nobody ever registers on
 * a platform whose root workspaces do not move.
 *
 * <p>A scan in which the registry <em>threw</em> is not remembered at all: "could not ask" is not
 * "not there", the same distinction the loop's own log line draws.
 */
@ApplicationScoped
public class EditorProxyTargets {

  private static final Logger LOG = Logger.getLogger(EditorProxyTargets.class);

  @Inject WorkspaceRepository workspaces;

  @Inject RepositoryLookup repositories;

  /** Project label → the wrapper repository's id. Immutable facts; see the class note. */
  private final Map<String, String> wrappers = new ConcurrentHashMap<>();

  /**
   * Project label → the "no such wrapper" answer, and what it was computed over. See the class note
   * for why the two halves of this cache are not the same kind of thing.
   */
  private final Map<String, Miss> misses = new ConcurrentHashMap<>();

  /**
   * The backstop under a miss, once the candidate set that invalidates it has been checked. Five
   * seconds is longer than the SPA's two-second poll, so a label nobody will ever register costs
   * one scan per window rather than one per tick.
   */
  @ConfigProperty(name = "qits.editor.label-miss-ttl-ms", defaultValue = "5000")
  long missTtlMs;

  /**
   * When the miss map is swept. A label reaches this class only after {@link EditorHost} has
   * validated it against qits-projects' slug grammar, but that still admits more labels than a
   * platform has projects, and an entry costs its string until something drops it. Sweeping on
   * insert past this size keeps the map proportional to what is actually being asked for without a
   * scheduler for a few dozen strings.
   */
  private static final int MISS_SWEEP_THRESHOLD = 256;

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
   * The project's wrapper repository, recognised by the name its slug derives — remembered for good
   * once found, and remembered against one candidate set for {@link #missTtlMs} when not.
   */
  private Optional<String> wrapperRepository(String projectLabel) {
    String remembered = wrappers.get(projectLabel);
    if (remembered != null) {
      return Optional.of(remembered);
    }
    // One indexed local query, and it is what makes the miss below safe to keep: the answer can only
    // change when this set does.
    Set<String> candidates = Set.copyOf(workspaces.activeRootRepositoryIds());
    Miss miss = misses.get(projectLabel);
    if (miss != null && System.nanoTime() - miss.expiresAt() < 0 && miss.candidates().equals(candidates)) {
      return Optional.empty();
    }
    String wrapperName = EditorHost.wrapperRepositoryName(projectLabel);
    boolean fullyScanned = true;
    for (String repositoryId : candidates) {
      Optional<RepositoryLookup.RepositoryView> view;
      try {
        view = repositories.find(repositoryId);
      } catch (RuntimeException unreachable) {
        // "Could not ask" is not "not there", and the difference matters here the way it does
        // everywhere this port is read — but there is no caller to tell, because the answer this
        // method gives is a 404 either way. Say so in the log and keep looking: another candidate
        // may resolve, and a blip must not be remembered as an absence — which is what this flag
        // buys, one line down.
        fullyScanned = false;
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
        misses.remove(projectLabel);
        return Optional.of(repositoryId);
      }
    }
    if (fullyScanned) {
      rememberMiss(projectLabel, candidates);
    }
    return Optional.empty();
  }

  /**
   * A completed scan that found nothing: the candidate set it was computed over, and a deadline.
   *
   * @param candidates the root repositories the scan asked about — the miss stands only while this
   *     is still what a scan would ask about, which is what makes a main workspace created a moment
   *     later resolve at once instead of waiting out a window
   * @param expiresAt a {@link System#nanoTime()} reading, the backstop under the set comparison
   */
  private record Miss(Set<String> candidates, long expiresAt) {}

  /** Write down a completed scan that found nothing, sweeping what has expired if the map has grown. */
  private void rememberMiss(String projectLabel, Set<String> candidates) {
    long now = System.nanoTime();
    if (misses.size() >= MISS_SWEEP_THRESHOLD) {
      misses.values().removeIf(miss -> now - miss.expiresAt() >= 0);
    }
    misses.put(projectLabel, new Miss(candidates, now + TimeUnit.MILLISECONDS.toNanos(missTtlMs)));
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
