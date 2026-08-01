package eu.wohlben.qits.workspaces.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * qits-workspaces' one event, on the wire. Plain JUnit — an event class is data, and the serializer
 * it is asserted against builds its own mapper precisely so no container is needed to know what it
 * emits.
 *
 * <p>These assertions are the contract qits-events and every release-train trigger were written
 * against, so a change here that is not also a change there is a cross-repo break rather than a
 * refactor. The payload's four keys are the frozen shape.
 *
 * <p><b>The wire name is the class name</b> — {@code QitsEvent.signature()} returns the simple class
 * name — so this class renaming from {@code SoftwareRelease} to {@code SCMRelease} <i>is</i> the wire
 * rename, and the assertion below is the whole of it. The payload did not change; only the meaning
 * the name carries did, and qits-ci's new {@code SoftwareRelease} is what now says "an artifact
 * exists".
 */
class SCMReleaseTest {

  private static final Instant PUSHED = Instant.parse("2026-08-01T12:46:03Z");

  private static SCMRelease anEvent() {
    return new SCMRelease("qits", "qits-workspaces", "release-flow", "2026.801.124603", PUSHED);
  }

  @Test
  void theSignatureIsTheClassNameAndTheNameFollowsIt() {
    SCMRelease event = anEvent();

    assertEquals("SCMRelease", event.signature());
    assertEquals("SCMRelease", event.name());
  }

  @Test
  void occurredAtIsWhenThePushWasAcceptedRatherThanWhenItWasAnnounced() {
    assertEquals(PUSHED, anEvent().occurredAt());
  }

  @Test
  void theEventIdIsAV4GeneratedOnceAndStableThereafter() {
    SCMRelease event = anEvent();

    UUID first = event.eventId();
    assertEquals(4, first.version(), "the idempotency key must be random, not derived");
    assertSame(first, event.eventId());
    // Two releases of the same facts are two occurrences and must not collide on one id.
    assertNotEquals(first, anEvent().eventId());
  }

  @Test
  void theEnvelopeIsThePlansShape() {
    EventEnvelope envelope = EventEnvelope.of(anEvent());
    JsonNode json = CanonicalJson.parse(CanonicalJson.envelope(envelope));

    assertEquals(
        List.of("description", "name", "occurredAt", "parentId", "payload"),
        json.properties().stream().map(Map.Entry::getKey).toList());
    assertEquals("SCMRelease", json.get("name").asText());
    assertEquals("2026-08-01T12:46:03Z", json.get("occurredAt").asText());
    assertEquals(
        "{\"branch\":\"release-flow\",\"projectId\":\"qits\","
            + "\"repository\":\"qits-workspaces\",\"version\":\"2026.801.124603\"}",
        json.get("payload").asText(),
        "four fields and no target: a release lands on the default branch by construction");
    assertEquals(true, json.get("parentId").isNull(), "a human-initiated release is a chain root");
  }

  @Test
  void theIdentityAndTheClockTravelInTheEnvelopeAndNeverInThePayload() {
    SCMRelease event = anEvent();

    String payload = CanonicalJson.payload(event);

    assertFalse(payload.contains("eventId"), payload);
    assertFalse(payload.contains(event.eventId().toString()), payload);
    assertFalse(payload.contains("occurredAt"), payload);
    assertFalse(payload.contains("signature"), payload);
  }

  @Test
  void aSubscriberReadsTheFourPayloadFieldsBack() {
    SCMRelease published = anEvent();

    SCMRelease received =
        CanonicalJson.payloadTo(CanonicalJson.payload(published), SCMRelease.class);

    assertEquals(published.projectId(), received.projectId());
    assertEquals(published.repository(), received.repository());
    assertEquals(published.branch(), received.branch());
    assertEquals(published.version(), received.version());
  }
}
