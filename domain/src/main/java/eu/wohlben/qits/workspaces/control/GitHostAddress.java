package eu.wohlben.qits.workspaces.control;

/**
 * Where a repository's bare origin answers as a <em>git remote</em> — the url the integrate flow
 * pushes its release commit to.
 *
 * <p>This context already holds that repository on disk, in the tree {@code
 * qits.repositories.data-dir} names, and could advance {@code main} by writing the ref directly.
 * That is exactly what today's merge does, and it is why <b>no CI run exists for any merge this
 * service has ever performed</b>: a filesystem ref update fires no {@code post-receive}, so nothing
 * downstream learns. Integrate therefore pushes over HTTP to the ordinary git host — the same door
 * a workspace container pushes through — so receive-pack is the sole writer of the default branch,
 * the protection hook sees every release, and the existing post-receive → qits-ci → build chain
 * happens because the release <em>is</em> a push like any other.
 *
 * <p>A port rather than a config lookup for the reason every other seam here is one: the address of
 * another service is deployment knowledge, and the suite needs the same flow pointed at a local
 * bare so a real fast-forward compare-and-swap can be asserted without an HTTP git host in the
 * reactor. {@link ConfiguredGitHostAddress} is the shipped implementation and is {@code
 * @DefaultBean}, so a test-scoped bean of this type simply wins.
 */
public interface GitHostAddress {

  /**
   * The remote to push {@code repoId} to. Any string {@code git push} accepts as a remote: the
   * platform's is {@code <qits.artifacts.url>/artifacts/git/<repoId>}.
   */
  String pushUrl(String repoId);
}
