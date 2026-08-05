package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.gitmirror.GitRemotes;

/**
 * Where a repository answers as a <em>git remote</em> — the url this service mirrors from and pushes
 * to.
 *
 * <p>This context used to hold that repository on disk, on the shared volume of bare origins
 * qits-artifacts serves, and advanced refs by writing them there. That is why <b>no branch creation,
 * merge or cleanup this service performed ever produced a CI run</b>: a filesystem ref update fires no {@code
 * post-receive}, so nothing downstream learned. Every one of them is a push now, over HTTP to the
 * ordinary git host, so receive-pack is the sole writer of every ref, the protection hook sees every
 * release, and the existing post-receive → qits-ci → build chain happens for the ordinary reason.
 *
 * <p>A port rather than a config lookup for the reason every other seam here is one: the address of
 * another service is deployment knowledge, and the suite needs the same flows pointed at a local
 * bare so a real fast-forward compare-and-swap can be asserted without an HTTP git host in the
 * reactor. {@link ConfiguredGitHostAddress} is the shipped implementation and is {@code
 * @DefaultBean}, so a test-scoped bean of this type simply wins.
 *
 * <p>It <b>extends {@link GitRemotes}</b>, which the gitmirror module declares, so nothing has to
 * adapt one to the other: the module named the shape it needs, this port names it in this context's
 * vocabulary and config decides what it returns. The two methods are one string in every deployment
 * — see {@code GitRemotes} for the one reason a test double distinguishes them.
 */
public interface GitHostAddress extends GitRemotes {

  /**
   * The remote for {@code repoId}. Any string {@code git} accepts as a remote: the platform's is
   * {@code <qits.artifacts.url>/artifacts/git/<repoId>}.
   */
  @Override
  String fetchUrl(String repoId);

  /** The same remote, asked once immediately before a push. */
  @Override
  String pushUrl(String repoId);
}
