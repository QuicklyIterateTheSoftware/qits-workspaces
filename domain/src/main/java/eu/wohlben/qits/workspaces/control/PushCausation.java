package eu.wohlben.qits.workspaces.control;

/**
 * What caused the work happening on this thread, for a push about to leave for the git host.
 *
 * <p>The git host publishes an SCM event per ref a push moves, and it publishes them under whatever
 * {@code X-Qits-Causation-Id} the push carried. Every ref this service moves is moved by a push, so
 * this port is what keeps a chain whole across the one hop that would otherwise break it: a release
 * arriving as a request, the pushes it makes, the commit and tag events, the CI run, the deployment.
 *
 * <p>It is a port for the reason every reach out of {@code domain} is one — the answer comes from
 * {@code qits-eventstream}'s {@code CausationScope}, and this module stays free of the bus, exactly
 * as {@link ReleaseAnnouncer} does on the publishing side. <b>Absent is a supported
 * configuration</b>: with no implementation every push simply names no cause, which is what every
 * push did before the git host published anything. The one implementation is {@code
 * service/…/bus/EventstreamPushCausation}.
 *
 * <p>It answers a {@code String} rather than a {@code UUID} because {@code qits-workspaces-gitmirror}
 * — which is what finally writes it onto the request — has no Quarkus in it and takes a plain {@code
 * Supplier<String>}. It re-parses the value before sending it, so a malformed answer costs the push
 * its causation edge and nothing else.
 */
public interface PushCausation {

  /** The id of the event this thread is running because of, or {@code null} when there is none. */
  String currentCauseId();
}
