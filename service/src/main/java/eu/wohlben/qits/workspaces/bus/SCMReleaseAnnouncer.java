package eu.wohlben.qits.workspaces.bus;

import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.workspaces.control.ReleaseAnnouncer;
import eu.wohlben.qits.workspaces.events.SCMRelease;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Turns an accepted release push into the platform's {@code SCMRelease} event and hands it to the
 * bus. The whole of this service's event-bus wiring — it publishes and listens for nothing.
 *
 * <p>{@code SCMRelease} says <b>source control has this release</b> and nothing more. The event
 * announcing that an artifact exists is qits-ci's {@code SoftwareRelease}, published when a release
 * pipeline goes green — this class must never grow into publishing that one, because the moment it
 * would publish it at is a whole build too early.
 *
 * <p>It lives in {@code service/} because the {@code domain} module knows nothing of the bus; the
 * seam it implements is {@link ReleaseAnnouncer} in {@code domain/control}, and zero implementations
 * is a supported configuration there.
 *
 * <p><b>{@code @DefaultBean}, for the reason {@code HttpRepositoryLookup} carries it.</b> The suite
 * has its own {@code FakeReleaseAnnouncer} and must not publish to a real qits-events; without this
 * annotation the two are an ambiguous dependency and the build fails at {@code
 * ArcProcessor#validate} — for every test at once, and not at runtime. Keep it.
 *
 * <p><b>It blocks, briefly, and that is the trade.</b> {@link QitsEventBus#publish} attempts the PUT
 * inline, never throws, and gives up after the publish timeout — after which the outbox owns
 * delivery. The caller is the request thread of a synchronous release, which has already paid for a
 * push; a qits-events that is down costs each release those few seconds and nothing after.
 *
 * <p><b>A root event, deliberately.</b> A release is initiated by a person pressing a button, so
 * there is no parent event to name and {@code publish(event)} publishes a chain root. The builds and
 * deploys that follow stamp <em>this</em> event as their parent through the shipped causation
 * machinery, which is what makes a release train a chain in the log rather than a set of rows
 * distinguishable from coincidence only by their timestamps.
 */
@ApplicationScoped
@DefaultBean
public class SCMReleaseAnnouncer implements ReleaseAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onReleasePublished(
      String projectId,
      String repoId,
      String repoName,
      String branch,
      String version,
      String commitSha,
      Instant publishedAt) {
    // commitSha is not in the payload: a consumer that wants the commit has BuildSuccessful, which
    // carries one for the build this release triggers.
    bus.publish(new SCMRelease(projectId, repoId, repoName, branch, version, publishedAt));
  }
}
