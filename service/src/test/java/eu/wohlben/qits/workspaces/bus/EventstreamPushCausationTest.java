package eu.wohlben.qits.workspaces.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.eventstream.CausationHeader;
import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.workspaces.gitmirror.RepoMirror;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The producer half of the causation chain: what a push to the git host says about why it happened.
 *
 * <p>Every ref this service moves is moved by a push, so this is the one hop between "a release was
 * asked for" and "the git host announced a commit" — and the git host reads the cause off the
 * request rather than guessing it.
 *
 * <p>Two claims, and the second is the one that could rot silently. The first is that the port reads
 * the ambient cause and answers null when there is none: a push must never fail for want of a cause.
 * The second is that {@code qits-workspaces-gitmirror}, which has no Quarkus in it and therefore
 * spells the header name as a literal, still spells the <b>same</b> name the bus library defines.
 * Nothing else connects the two strings, and a mismatch would cost every push its causation edge
 * with a green build either side.
 */
class EventstreamPushCausationTest {

  private final EventstreamPushCausation causation = new EventstreamPushCausation();

  @Test
  void theHeaderTheMirrorStampsIsTheHeaderTheBusDefines() {
    assertEquals(CausationHeader.NAME, RepoMirror.CAUSATION_HEADER);
  }

  @Test
  void anAmbientCauseBecomesTheIdAPushCarries() {
    UUID cause = UUID.randomUUID();

    CausationScope.with(cause, () -> assertEquals(cause.toString(), causation.currentCauseId()));
  }

  @Test
  void noAmbientCauseIsNoIdRatherThanAnInventedOne() {
    assertNull(causation.currentCauseId());
    CausationScope.with(null, () -> assertNull(causation.currentCauseId()));
  }
}
