package eu.wohlben.qits.domain.process.control;

import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangePublisher;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The in-memory registry of live {@link TechnicalProcess}es, keyed by process id and by the
 * workspace or repository they run against — so a Start/Pull's HTTP response can hand the id to the
 * browser, and the detail route can discover the current process for its workspace ({@link
 * #activeFor}) or repository ({@link #activeForRepository}).
 *
 * <p>Lifecycle: {@link #begin} registers a process <em>before</em> the work starts (the very first
 * output line is captured); the process's terminal {@code done} clears the active mapping and
 * starts a short retention window ({@code qits.process.done-ttl-ms}) during which a late
 * (re)subscriber still gets the full replay plus an immediate {@code done} — after that the entry
 * is evicted and lookups 404. A max-lifetime backstop force-finishes a process that never converges
 * (e.g. a ready pattern that never matches). Activeness changes are announced on the existing
 * payload-free workspace channel as a {@code PROCESS} hint — the hint stays data-free; the payload
 * rides only the process's own SSE stream.
 */
@ApplicationScoped
public class TechnicalProcessRegistry {

  /** How long a completed process stays subscribable (full replay + immediate done) before 404. */
  @ConfigProperty(name = "qits.process.done-ttl-ms", defaultValue = "60000")
  long doneTtlMillis;

  /**
   * Backstop: a process <em>idle</em> (no emitted frame) this long is force-finished so it can't
   * leak forever. Deliberately an idle window, not a total-lifetime cap: a provision's process now
   * spans the whole bootstrap chain, whose length is unbounded (N commands × the per-command
   * await), so a fixed cap would either cut a legitimately long-but-active chain mid-run or be far
   * too long to reap a genuinely stuck process (e.g. a ready pattern that never matches). Measured
   * against {@link TechnicalProcess#millisSinceLastActivity()}: an actively streaming chain keeps
   * resetting it, a stalled one trips it.
   */
  @ConfigProperty(name = "qits.process.max-idle-ms", defaultValue = "900000")
  long maxIdleMillis;

  @Inject WorkspaceChangePublisher changePublisher;

  private final Map<String, TechnicalProcess> byId = new ConcurrentHashMap<>();
  private final Map<String, String> activeByWorkspace = new ConcurrentHashMap<>();
  private final Map<String, RepoActive> activeByRepository = new ConcurrentHashMap<>();

  /**
   * The live repository-scoped process plus its operation kind (e.g. {@code pull}/{@code sync}).
   */
  private record RepoActive(String processId, String kind) {}

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "technical-process-registry");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }

  /** Register a new process for a workspace; the newest one is the workspace's active process. */
  public TechnicalProcess begin(String repoId, String workspaceId) {
    String id = UUID.randomUUID().toString();
    TechnicalProcess process = new TechnicalProcess(id, repoId, workspaceId, this::onDone);
    byId.put(id, process);
    activeByWorkspace.put(workspaceKey(repoId, workspaceId), id);
    scheduleIdleReaper(process);
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.PROCESS);
    return process;
  }

  /**
   * Atomic, kind-aware single-flight for a repository-scoped process (null workspaceId) — for work
   * that operates on a repository as a whole rather than a single workspace (a streamed {@code
   * pull} or {@code sync}, passed as {@code kind}). Check-and-register run under the registry
   * monitor so two racing POSTs can't both register (the second would otherwise clobber the active
   * mapping and leave two walks contending on the bare origin's ref-locks):
   *
   * <ul>
   *   <li>A live process of the <em>same</em> kind → {@link RepoProcessLease.Reused} (the caller
   *       returns its id and starts no second walk; a reload / second tab reattaches to it via
   *       {@link #activeForRepository}).
   *   <li>A live process of a <em>different</em> kind → {@link RepoProcessLease.Conflict} (a pull
   *       and a sync can't share a walk — a pull would skip the push — nor run concurrently; the
   *       caller rejects).
   *   <li>Otherwise a fresh process is registered → {@link RepoProcessLease.Fresh}. It fires the
   *       {@code PROCESS} hint on the repository channel ({@code (repoId, null)} — see {@code
   *       RepositoryEventsController}) so the browser learns a pull became active without polling.
   * </ul>
   */
  public synchronized RepoProcessLease beginForRepository(String repoId, String kind) {
    RepoActive current = activeByRepository.get(repoId);
    if (current != null) {
      TechnicalProcess existing = byId.get(current.processId());
      if (existing != null && !existing.isTerminal()) {
        return kind.equals(current.kind())
            ? new RepoProcessLease.Reused(current.processId())
            : new RepoProcessLease.Conflict(current.kind());
      }
    }
    String id = UUID.randomUUID().toString();
    TechnicalProcess process = new TechnicalProcess(id, repoId, null, this::onDone);
    byId.put(id, process);
    activeByRepository.put(repoId, new RepoActive(id, kind));
    scheduleIdleReaper(process);
    changePublisher.fire(repoId, null, WorkspaceChangeHint.Topic.PROCESS);
    return new RepoProcessLease.Fresh(process);
  }

  /**
   * Force-finish the process once it has been idle for {@link #maxIdleMillis}; while it is still
   * producing frames, re-arm for the remaining idle window instead of cutting it off.
   */
  private void scheduleIdleReaper(TechnicalProcess process) {
    scheduler.schedule(() -> reapIfIdle(process), maxIdleMillis, TimeUnit.MILLISECONDS);
  }

  private void reapIfIdle(TechnicalProcess process) {
    if (process.isTerminal()) {
      return;
    }
    long idle = process.millisSinceLastActivity();
    if (idle < maxIdleMillis) {
      scheduler.schedule(() -> reapIfIdle(process), maxIdleMillis - idle, TimeUnit.MILLISECONDS);
    } else {
      process.forceFinish();
    }
  }

  /** The process for {@code id} — live or within its post-done retention window. */
  public Optional<TechnicalProcess> find(String id) {
    return Optional.ofNullable(id == null ? null : byId.get(id));
  }

  /** The id of the workspace's currently-running process, if any (cleared on done). */
  public Optional<String> activeFor(String repoId, String workspaceId) {
    return Optional.ofNullable(activeByWorkspace.get(workspaceKey(repoId, workspaceId)));
  }

  /** The id of the repository's currently-running process, if any (cleared on done). */
  public Optional<String> activeForRepository(String repoId) {
    return Optional.ofNullable(activeByRepository.get(repoId)).map(RepoActive::processId);
  }

  private void onDone(TechnicalProcess process) {
    // Each scope clears its own active mapping and announces the change on its channel. Workspace-
    // scoped processes fire the per-workspace hint; repository-scoped ones (null workspaceId, from
    // beginForRepository) fire the repository hint (repoId, null).
    if (process.workspaceId() != null) {
      activeByWorkspace.remove(workspaceKey(process.repoId(), process.workspaceId()), process.id());
      changePublisher.fire(
          process.repoId(), process.workspaceId(), WorkspaceChangeHint.Topic.PROCESS);
    } else if (process.repoId() != null) {
      clearRepositoryIfCurrent(process);
      changePublisher.fire(process.repoId(), null, WorkspaceChangeHint.Topic.PROCESS);
    }
    scheduler.schedule(() -> byId.remove(process.id()), doneTtlMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Clear the repository's active mapping only if it still points at <em>this</em> process — under
   * the same monitor {@link #beginForRepository} registers under, so a done racing a fresh begin
   * never removes the newer mapping (the {@link RepoActive} record is compared by id).
   */
  private synchronized void clearRepositoryIfCurrent(TechnicalProcess process) {
    RepoActive current = activeByRepository.get(process.repoId());
    if (current != null && current.processId().equals(process.id())) {
      activeByRepository.remove(process.repoId());
    }
  }

  private static String workspaceKey(String repoId, String workspaceId) {
    return repoId + "/" + workspaceId;
  }
}
