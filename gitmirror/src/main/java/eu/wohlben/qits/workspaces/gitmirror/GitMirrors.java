package eu.wohlben.qits.workspaces.gitmirror;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry: one {@link RepoMirror} per repository id, all under one root directory.
 *
 * <p><b>This is qits-ci's pattern, one size larger.</b> qits-ci keeps a private bare per repository
 * on its own volume and fills it by fetching over HTTP; that cache reads config files, so a branch
 * tip is all it needs. This one has to merge, so it mirrors <i>every</i> ref and hosts worktrees —
 * but the shape, the ownership and the failure mode are the same, and neither of them ever touches
 * the repositories volume qits-artifacts serves.
 *
 * <p>The cost is a second copy of the platform's git, measured at 21 MB in total. The thing it buys
 * is that no ref in a served repository can move except through receive-pack.
 *
 * <p><b>What the lock covers, and why it is only that.</b> Cloning and fetching one repository are
 * serialized per repository id, because two of them at once would race for the same object store for
 * no gain. Reads and worktrees are not: they are what a listing and a release do, they are safe
 * concurrently, and holding a lock across a whole release would make one workspace listing wait for
 * it. The repository lease in {@code domain} is what keeps two releases of one repository apart, and
 * it is a different concern from keeping two fetches apart.
 */
public final class GitMirrors {

  private final GitCli cli;
  private final GitRemotes remotes;
  private final Path root;
  private final Duration networkTimeout;
  private final Duration freshness;

  private final Map<String, RepoMirror> mirrors = new ConcurrentHashMap<>();

  /**
   * @param root where the mirrors live — this service's OWN data volume, never the shared
   *     repositories tree
   * @param networkTimeout the bound on every wire call: clone, fetch, {@code ls-remote}, push
   * @param freshness how long a fetched mirror is trusted before {@link RepoMirror#refresh()}
   *     fetches again. Zero means every refresh fetches; the flows that cannot tolerate a stale
   *     answer call {@link RepoMirror#refreshNow()} regardless of it.
   */
  public GitMirrors(
      GitCli cli, GitRemotes remotes, Path root, Duration networkTimeout, Duration freshness) {
    this.cli = cli;
    this.remotes = remotes;
    this.root = root.toAbsolutePath();
    this.networkTimeout = networkTimeout;
    this.freshness = freshness;
  }

  /**
   * The mirror for a repository. Cheap and lazy — nothing is cloned until a caller asks for
   * something that needs objects.
   */
  public RepoMirror of(String repoId) {
    if (repoId == null || repoId.isBlank()) {
      throw new GitMirrorException("A mirror needs a repository id");
    }
    return mirrors.computeIfAbsent(
        repoId, id -> new RepoMirror(cli, remotes, id, root, networkTimeout, freshness));
  }

  /** Where the mirrors live. */
  public Path root() {
    return root;
  }
}
