package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.gitmirror.GitCli;
import eu.wohlben.qits.workspaces.gitmirror.GitMirrors;
import eu.wohlben.qits.workspaces.gitmirror.RepoMirror;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The one place the framework meets {@code qits-workspaces-gitmirror}.
 *
 * <p>That module is a plain library with no CDI, no MicroProfile config and no Quarkus — which is
 * what lets its own suite run offline against throwaway bares, and what would let it move into a
 * daemon of its own without a rewrite. So exactly one bean builds it from config and hands it out,
 * and nothing else in this context knows how it is constructed.
 */
@ApplicationScoped
public class GitMirrorRegistry {

  /**
   * This service's <b>own</b> data tree, and the asymmetry with {@code qits.repositories.data-dir}
   * is the point: that one is the shared volume qits-artifacts serves and qits-projects clones into,
   * and nothing here writes to it any more. The mirrors are a private cache of repositories this
   * service does not own, so they live where its database and its event outbox already do.
   */
  @ConfigProperty(name = "qits.workspaces.data-dir", defaultValue = "data/workspaces")
  String dataDir;

  /**
   * The bound on every git call that talks to the git host — clone, fetch, {@code ls-remote} and
   * push alike. One key rather than one per verb: they are the same failure (a wedged host pinning a
   * request thread) and this service answers release, integrate and the workspace listing
   * synchronously.
   *
   * <p>Local git in the mirror keeps its unbounded wait, where a bound would only turn slow into
   * broken.
   */
  @ConfigProperty(name = "qits.workspace.git.network-timeout-ms", defaultValue = "120000")
  long networkTimeoutMs;

  /**
   * How long a fetched mirror is trusted before a read refreshes it again.
   *
   * <p>It bounds one thing only: how stale an ahead/behind count or a conflict warning on the branch
   * list may be. Nothing that <i>decides</i> anything reads through it — branch existence is an
   * {@code ls-remote} against the git host, and every flow that is about to write calls a forced
   * refresh first. The window exists because the workspace listing computes ahead/behind for every
   * workspace of a repository and a browser polls it.
   */
  @ConfigProperty(name = "qits.workspace.git.mirror-freshness-ms", defaultValue = "5000")
  long freshnessMs;

  @Inject GitHostAddress gitHost;

  private GitMirrors mirrors;

  @PostConstruct
  void build() {
    mirrors =
        new GitMirrors(
            new GitCli(),
            gitHost,
            Path.of(dataDir).toAbsolutePath(),
            Duration.ofMillis(networkTimeoutMs),
            Duration.ofMillis(freshnessMs));
  }

  /** The mirror for a repository. Cheap and lazy — nothing is cloned until objects are needed. */
  public RepoMirror of(String repoId) {
    return mirrors.of(repoId);
  }

  /** Where the mirrors live, for the one test that asserts they are not on the shared volume. */
  public Path root() {
    return mirrors.root();
  }
}
