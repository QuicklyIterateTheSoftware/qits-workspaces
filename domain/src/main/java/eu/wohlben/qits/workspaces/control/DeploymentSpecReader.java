package eu.wohlben.qits.workspaces.control;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Does this repository deploy at all? That is the whole question this service asks a repository's
 * {@code .config/qits/deployments.yml}, and the answer is the file's <b>presence</b>.
 *
 * <p>A library, an SPA published to the npm registry, a documentation repo — none of them is
 * deployed by qits-platform-deployments, and none of them carries the file. Releasing one pushes the
 * trunk and stops there. Everything a deployable repository says <em>inside</em> the file belongs to
 * the deployer: {@code deployment_target}, {@code available_on_env}, {@code health_path}, {@code
 * health_cmd}. This reader opens none of it.
 *
 * <h2>It used to parse one key, and that key was wrong</h2>
 *
 * <p>The key was {@code deploy_branches}, and a release pushed its sha onto <b>every</b> ref the
 * list named. Every repository named the same single ref, so it behaved — but three tiers listed
 * would have shipped a release into dev, preprod and prod in the same second. That is a fan-out, not
 * a ladder, and it was a per-repository answer to a platform-wide question besides.
 *
 * <p>Where a release lands is now one configured branch
 * ({@code qits.workspaces.release.entry-branch}), and advancing it from one tier to the next is a
 * separate operation over the environment rows. The deployer still tolerates the key in a spec,
 * because a spec is read at the built sha and older commits carry it; nothing reads it.
 *
 * <p><b>An unreadable path is not distinguished from an absent one</b>, which is a change of posture
 * the shrink earns: there is nothing left to parse, so there is no read to fail. A directory sitting
 * where the file should be answers "no spec", the same as nothing sitting there.
 */
public final class DeploymentSpecReader {

  /** Where a repository declares that it deploys, relative to a checkout's root. */
  public static final String SPEC_PATH = ".config/qits/deployments.yml";

  private DeploymentSpecReader() {}

  /**
   * Whether this checkout carries a deployment spec.
   *
   * @param checkout the root of a checked-out tree — the release flow's merge worktree, checked out
   *     at the released commit, so the answer travels with the release
   */
  public static boolean exists(Path checkout) {
    return Files.isRegularFile(checkout.resolve(SPEC_PATH));
  }
}
