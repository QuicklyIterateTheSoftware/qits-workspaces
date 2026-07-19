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
 * workspace they run against — so a Start's HTTP response can hand the id to the browser, and the
 * workspace detail route can discover the current process for its workspace ({@link #activeFor}).
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
   * Register a new repository-scoped process (null workspaceId) — for work that pulls/operates on a
   * repository as a whole rather than a single workspace (e.g. a streamed {@code repository pull}).
   * Unlike {@link #begin}, it keeps no {@link #activeByWorkspace} mapping and fires no {@code
   * PROCESS} hint (there is no per-workspace channel): v1 hands the id straight to the browser via
   * the HTTP response and relies on the post-done retention window for reattach. It is otherwise a
   * full process — registered by id and reaped when idle.
   */
  public TechnicalProcess beginForRepository(String repoId) {
    String id = UUID.randomUUID().toString();
    TechnicalProcess process = new TechnicalProcess(id, repoId, null, this::onDone);
    byId.put(id, process);
    scheduleIdleReaper(process);
    return process;
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

  private void onDone(TechnicalProcess process) {
    // Workspace-scoped processes clear their active mapping and announce it; repository-scoped ones
    // (null workspaceId, from beginForRepository) have neither, so only the retention schedule
    // runs.
    if (process.workspaceId() != null) {
      activeByWorkspace.remove(workspaceKey(process.repoId(), process.workspaceId()), process.id());
      changePublisher.fire(
          process.repoId(), process.workspaceId(), WorkspaceChangeHint.Topic.PROCESS);
    }
    scheduler.schedule(() -> byId.remove(process.id()), doneTtlMillis, TimeUnit.MILLISECONDS);
  }

  private static String workspaceKey(String repoId, String workspaceId) {
    return repoId + "/" + workspaceId;
  }
}
