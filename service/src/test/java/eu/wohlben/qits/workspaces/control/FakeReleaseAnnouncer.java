package eu.wohlben.qits.workspaces.control;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The test-side {@link ReleaseAnnouncer}: it records what it was told and does nothing else.
 *
 * <p>Nothing implements this port in production yet — the {@code SoftwareRelease} event is a named
 * follow-up — so this double exists to make the seam <em>testable now</em> rather than a claim that
 * only the future feature could check. What it holds is the shape of the statement and the fact that
 * exactly one is made per successful integrate, and none at all when nothing was released.
 */
@ApplicationScoped
public class FakeReleaseAnnouncer implements ReleaseAnnouncer {

  /** One announcement, as the future publisher would receive it. */
  public record Announced(
      String repoId, String branch, String version, String commitSha, Instant publishedAt) {}

  private final List<Announced> announced = new CopyOnWriteArrayList<>();

  @Override
  public void onReleasePublished(
      String repoId, String branch, String version, String commitSha, Instant publishedAt) {
    announced.add(new Announced(repoId, branch, version, commitSha, publishedAt));
  }

  public List<Announced> announced() {
    return List.copyOf(announced);
  }

  /** Drop everything — call from {@code @BeforeEach}; the bean outlives a test method. */
  public void reset() {
    announced.clear();
  }
}
