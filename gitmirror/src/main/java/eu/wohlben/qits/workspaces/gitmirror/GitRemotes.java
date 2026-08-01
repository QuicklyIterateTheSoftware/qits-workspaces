package eu.wohlben.qits.workspaces.gitmirror;

/**
 * Where a repository answers as a git remote. The one thing this module refuses to know: an address
 * is deployment knowledge, and the suite points the same flow at a local bare so a real
 * fast-forward compare-and-swap can be asserted with no HTTP git host in the reactor.
 *
 * <p><b>Two methods for one string, and the split is load-bearing.</b> A deployment returns the same
 * url from both. A test double returns the same url from both too — but {@link #pushUrl} is asked
 * <em>once, immediately before a push</em>, which is the only instant a lost race is about, so a
 * double can stage a second writer there. Reads and fetches must not consume that hook, which is why
 * they ask {@link #fetchUrl} instead.
 */
public interface GitRemotes {

  /** The remote to read from — {@code ls-remote} and the mirror's fetch. */
  String fetchUrl(String repoId);

  /** The remote to push to, asked once per push. */
  String pushUrl(String repoId);
}
