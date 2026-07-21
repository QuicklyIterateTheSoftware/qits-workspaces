package eu.wohlben.qits.domain.process.control;

/**
 * The outcome of {@link TechnicalProcessRegistry#reserveRepository} — a lightweight
 * repository-scoped single-flight lock for work that runs on its own channel rather than as a
 * streamed {@link TechnicalProcess} (the interactive sign-in terminal: its output rides a
 * WebSocket, not the SSE frame stream). Unlike {@link #beginForRepository a full process}, a
 * reservation is <em>not</em> subject to the idle reaper and is invisible to {@link
 * TechnicalProcessRegistry#activeForRepository} (so it opens no empty process dialog), yet it
 * shares the same {@code activeByRepository} slot — so a reservation and a pull/sync/push are
 * mutually exclusive on the same bare origin.
 *
 * <ul>
 *   <li>{@link Acquired} — the repository was free; the caller holds the lock until it {@link
 *       TechnicalProcessRegistry#releaseRepository releases} it with the returned token.
 *   <li>{@link Conflict} — another operation (a reservation or a live process) already holds the
 *       repository; the caller does not proceed.
 * </ul>
 */
public sealed interface RepoReservation {
  record Acquired(String token) implements RepoReservation {}

  record Conflict(String runningKind) implements RepoReservation {}
}
