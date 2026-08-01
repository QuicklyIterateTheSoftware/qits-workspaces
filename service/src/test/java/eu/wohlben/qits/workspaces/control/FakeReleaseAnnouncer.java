package eu.wohlben.qits.workspaces.control;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The test-side {@link ReleaseAnnouncer}: it records what it was told and does nothing else.
 *
 * <p>It wins over the production {@code bus/SoftwareReleaseAnnouncer}, which is a {@code
 * @DefaultBean} for exactly that reason — the suite must not publish to a real qits-events, and two
 * unqualified beans of one type are an ambiguous dependency that fails the whole build at {@code
 * ArcProcessor#validate}. What this double holds is the shape of the statement and the fact that
 * exactly one is made per successful RELEASE: none when nothing was released, and none at all for a
 * plain integrate.
 */
@ApplicationScoped
public class FakeReleaseAnnouncer implements ReleaseAnnouncer {

  /** One announcement, as the publisher receives it. */
  public record Announced(
      String projectId,
      String repoId,
      String branch,
      String version,
      String commitSha,
      Instant publishedAt) {}

  private final List<Announced> announced = new CopyOnWriteArrayList<>();

  @Override
  public void onReleasePublished(
      String projectId,
      String repoId,
      String branch,
      String version,
      String commitSha,
      Instant publishedAt) {
    announced.add(new Announced(projectId, repoId, branch, version, commitSha, publishedAt));
  }

  public List<Announced> announced() {
    return List.copyOf(announced);
  }

  /** Drop everything — call from {@code @BeforeEach}; the bean outlives a test method. */
  public void reset() {
    announced.clear();
  }
}
