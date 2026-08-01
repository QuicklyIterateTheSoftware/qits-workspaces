package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A repository released: this version of this repository, built from this branch, is on the default
 * branch and pushed.
 *
 * <p>Announced by qits-workspaces the instant the release push is accepted, and by nothing else on
 * the platform — only the process that ran {@code git push} knows, atomically, that the push
 * succeeded and with which version. It is what makes a release train possible: a downstream
 * repository's trigger listens for this and finally has a version to bump to.
 *
 * <p><b>There is no target field, deliberately.</b> A release lands on the default branch by
 * construction — that is the whole of the release flow — so a target would be a constant pretending
 * to be data. {@code branch} is the <b>source</b> branch that was released.
 *
 * <p><b>A plain integrate publishes nothing.</b> Merging a task branch into its parent moves a ref
 * and stamps no version; there is no release to announce, and an event that fired for both would
 * make "a release happened" unlistenable.
 *
 * <p><b>{@code eventId} and {@code occurredAt} are components and stay out of the payload.</b> The
 * library's canonical serializer excludes everything {@link QitsEvent} declares, and these two
 * accessors are those declarations — so identity and time travel in the envelope and the payload is
 * exactly the four fields the platform specified: {@code projectId}, {@code repository}, {@code
 * branch}, {@code version}. Reading a payload back therefore yields a fresh id and a null time,
 * which is correct: a received event's identity and clock are the envelope's.
 *
 * @param projectId the project the repository belongs to, as qits-projects names it
 * @param repository the repository that released, by string id — never a reference into another
 *     context's tables
 * @param branch the SOURCE branch that was released
 * @param version the release stamp, {@code YYYY.MMDD.HHMMSS}
 * @param occurredAt when the push was accepted, which is when the release happened
 */
public record SoftwareRelease(
    UUID eventId,
    String projectId,
    String repository,
    String branch,
    String version,
    Instant occurredAt)
    implements QitsEvent {

  public SoftwareRelease {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public SoftwareRelease(
      String projectId, String repository, String branch, String version, Instant occurredAt) {
    this(null, projectId, repository, branch, version, occurredAt);
  }
}
