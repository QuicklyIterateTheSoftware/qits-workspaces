package eu.wohlben.qits.workspaces.bus;

import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.workspaces.events.SCMRelease;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What the event bus binds to and from JSON, told to native-image. No code, no bean, nothing at
 * runtime: the annotation is the entire content, and this class exists so that the annotation has
 * somewhere to live that can say why.
 *
 * <p><b>Why nothing registers these automatically.</b> Quarkus registers reflection for the classes
 * <em>it</em> knows are serialized — a REST resource's parameters and return types, whatever the CDI
 * {@code ObjectMapper} is handed. {@code CanonicalJson} builds its <b>own</b> {@code ObjectMapper}
 * by hand, deliberately and permanently: the canonical form is a wire contract another service
 * compares byte-for-byte, so it must not be downstream of any application's {@code
 * ObjectMapperCustomizer}. Correct, and this is the price — to the build step scanning for what
 * needs reflecting on, that mapper and everything it touches are invisible. Do not "fix" a
 * recurrence by injecting the CDI mapper.
 *
 * <p><b>What it cost when qits-ci learned it, measured on a deployed binary (2026-07-31).</b> Every
 * publish died inside {@code CanonicalJson}'s writer with Jackson's {@code No serializer found for
 * class … native image, you may need to configure reflection} — no properties discovered, because a
 * record with no reflection metadata has no components to find. The throw happens while the envelope
 * is being built, so the event never reached the outbox either: not a delayed delivery, a lost one.
 * The JVM suite was green throughout and <b>structurally had to be</b> — on a JVM every one of these
 * types reflects fine, so there is no assertion it could have made that would have failed. This
 * repository ships a native image too, which is the whole reason this file exists before the first
 * release rather than after it.
 *
 * <p><b>And the mix-in by name — measured on that same pair of binaries.</b> {@code
 * CanonicalJson$QitsEventMixin} is the private nested class that keeps {@link
 * eu.wohlben.qits.eventstream.QitsEvent}'s declared methods — {@code eventId} and {@code occurredAt}
 * above all — out of a payload, and Jackson finds its {@code @JsonIgnore}s by calling {@code
 * getDeclaredMethods()} on it, which is reflection like any other. Without the entry the payload
 * silently gained {@code eventId}: no crash, no log, and a <b>wire contract violation</b>, since
 * identity is supposed to travel only in the envelope. For {@link SCMRelease} it would leak
 * {@code occurredAt} into the payload too, past the four fields the platform froze. It is named as a
 * string because it is private and stays private — a build concern is not a reason to widen a
 * library's encapsulation.
 *
 * <p><b>Why these two types and no third.</b> They are the whole of what crosses the wire here:
 * {@link SCMRelease} is serialized on the way out, {@link EventEnvelope} is the PUT body. This
 * service subscribes to nothing, so no {@code EventFrame} ever arrives — the day it listens for
 * something, that class and the event's own join this list.
 */
@RegisterForReflection(
    targets = {SCMRelease.class, EventEnvelope.class},
    classNames = "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin")
public final class EventWireReflection {

  private EventWireReflection() {}
}
