package eu.wohlben.qits.domain.process.control;

/**
 * The outcome of {@link TechnicalProcessRegistry#beginForRepository} — the atomic, kind-aware
 * single-flight for repository-scoped processes. Exactly one of:
 *
 * <ul>
 *   <li>{@link Fresh} — no compatible process was live, a new one was registered; the caller runs
 *       the work and returns its id.
 *   <li>{@link Reused} — a live process of the <em>same</em> kind already holds the repository; the
 *       caller starts no second walk and returns this id (the browser attaches to the running one).
 *   <li>{@link Conflict} — a live process of a <em>different</em> kind holds the repository (e.g. a
 *       sync requested while a pull runs). The two can't share a walk — a pull would skip the
 *       sync's push — nor safely run concurrently against the same bare origin, so the caller
 *       rejects.
 * </ul>
 */
public sealed interface RepoProcessLease {
  record Fresh(TechnicalProcess process) implements RepoProcessLease {}

  record Reused(String processId) implements RepoProcessLease {}

  record Conflict(String runningKind) implements RepoProcessLease {}
}
