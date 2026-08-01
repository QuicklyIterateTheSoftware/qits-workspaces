package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The test-side {@link GitHostAddress}: the repository's own bare origin, addressed as a local path.
 *
 * <p>It wins over the shipped {@link ConfiguredGitHostAddress} with no change on your part, because
 * that one is {@code @DefaultBean} and yields to any other bean of the type — the same arrangement
 * {@code FakeRepositoryLookup} has with {@code HttpRepositoryLookup}.
 *
 * <p><b>What this keeps real, and what it does not.</b> The push is a real {@code git push} into a
 * real bare: a real ref negotiation, a real fast-forward check, and therefore a real
 * compare-and-swap — which is what lets the concurrency and conflict assertions mean something with
 * no HTTP git host in this reactor. What it replaces is only the <em>transport</em>. The protection
 * hook is qits-artifacts' and is proven there and live; nothing here can stand in for it.
 *
 * <p>Note {@code TestOrigin} sets {@code receive.advertisePushOptions} on the bares it builds. Push
 * options are advertised by JGit in production and default to <em>off</em> for a local {@code
 * receive-pack}, and {@code git push --push-option} fails outright against a server that did not
 * advertise them — so without that line the fixture would refuse the real production argv.
 */
@ApplicationScoped
public class FakeGitHostAddress implements GitHostAddress {

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private final AtomicReference<Runnable> beforeNextPush = new AtomicReference<>();

  /**
   * The read address, and it deliberately does <b>not</b> fire the hook. The mirror asks for this on
   * every clone, fetch and {@code ls-remote}, and a staged second writer that fired on the first of
   * those would move the branch long before the instant a race is about.
   */
  @Override
  public String fetchUrl(String repoId) {
    return Path.of(dataDir, repoId, "origin").toAbsolutePath().toString();
  }

  @Override
  public String pushUrl(String repoId) {
    Runnable hook = beforeNextPush.getAndSet(null);
    if (hook != null) {
      hook.run();
    }
    return fetchUrl(repoId);
  }

  /**
   * Run something once, at the instant before the next push — which is the only instant a
   * non-fast-forward race is about.
   *
   * <p>{@link #pushUrl} is asked for the remote at step 8, after the worktree, the merge, the bump,
   * the commit and the tag exist and before the ref is contended for, so a hook here moves the
   * default branch exactly where a second writer would have — and {@link #fetchUrl} exists so that
   * everything the mirror reads on the way there leaves the hook alone. Staged rather than raced on
   * purpose: a real race is
   * nondeterministic about which side loses, and the repository lease means two integrates through
   * the API usually do not contend at all — which is correct behaviour and useless as a test of what
   * happens when they do. The precedent is qits-ci's {@code FakeCiStepRunner.during(step, …)}, which
   * stages "mid-step" the same way and for the same reason.
   *
   * <p>Consumed once, so a test that arms it and then fails before the push leaves nothing behind
   * for the next one.
   */
  public void beforeNextPush(Runnable hook) {
    beforeNextPush.set(hook);
  }

  /** Drop an unconsumed hook — call from {@code @BeforeEach}; the bean outlives a test method. */
  public void reset() {
    beforeNextPush.set(null);
  }
}
