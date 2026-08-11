package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.workspaces.dto.BootstrapRunDto;
import eu.wohlben.qits.workspaces.entity.BootstrapOutcome;
import eu.wohlben.qits.workspaces.entity.BootstrapRun;
import eu.wohlben.qits.workspaces.mapper.BootstrapRunMapper;
import eu.wohlben.qits.workspaces.persistence.BootstrapRunRepository;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;

/**
 * The single writer of {@link BootstrapRun} rows — one row per {@code (workspace, command)},
 * overwritten on every run. A separate bean from {@link WorkspaceBootstrapRunner} because the
 * runner executes on non-request threads (the CDI async observer thread and its manual-run
 * executor) where a self-invoked {@code @Transactional} would not be intercepted; {@link
 * ActivateRequestContext} supplies the request context those threads lack (the {@code
 * CommandLifecycleService} precedent).
 *
 * <p>{@link #listForWorkspace} is read by {@code WorkspaceBootstrapRunController} ({@code GET
 * /workspaces/{id}/bootstrap-runs}) — the reader this table went a release without. See {@link
 * BootstrapRun}'s javadoc for why that is a read of host state rather than the deleted forwarding
 * controller coming back.
 */
@ApplicationScoped
public class BootstrapRunService {

  @Inject BootstrapRunRepository bootstrapRunRepository;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject WorkspaceResolver workspaceResolver;

  @Inject BootstrapRunMapper bootstrapRunMapper;

  @Inject WorkspaceChangePublisher changePublisher;

  /**
   * Upsert the last-run row for {@code (workspace, bootstrapCommandId)} and hint the UI.
   *
   * <p><b>The write is held through a postgres cutover</b> ({@link DbRetry#inNewTx}) rather than
   * dropped. This is bookkeeping <em>after</em> irreversible work: the step already ran inside the
   * container, so a lost row leaves the world and the rows disagreeing, and the caller
   * ({@code WorkspaceBootstrapRunner}'s recorder) swallows the failure by design — nothing
   * downstream would ever notice. It is safe to re-run because the row is keyed on {@code
   * (workspace, bootstrapCommandId)} and every column is overwritten from the arguments, so a
   * second attempt writes the same row to the same values.
   *
   * <p>It runs on the registry's single {@code daemon-sink-dispatch} thread, never a socket thread
   * or a monitor. A held attempt delays the outcomes queued behind it and loses none of them, which
   * is the trade: during a cutover every writer on that thread is failing anyway.
   */
  @ActivateRequestContext
  public void recordOutcome(
      String repoId,
      String workspaceId,
      Long rowId,
      String bootstrapCommandId,
      String commandName,
      BootstrapOutcome outcome,
      String commandId,
      Integer exitCode) {
    DbRetry.runInNewTx(
        "record bootstrap outcome for workspace " + workspaceId,
        () ->
            writeOutcome(
                repoId,
                workspaceId,
                bootstrapCommandId,
                commandName,
                outcome,
                commandId,
                exitCode));
    // Outside the retried unit — the body is database-only by rule.
    changePublisher.fire(repoId, rowId, WorkspaceChangeHint.Topic.BOOTSTRAP);
  }

  /** One attempt of {@link #recordOutcome}'s database work: resolve, upsert, flush. */
  private void writeOutcome(
      String repoId,
      String workspaceId,
      String bootstrapCommandId,
      String commandName,
      BootstrapOutcome outcome,
      String commandId,
      Integer exitCode) {
    Workspace workspace = activeWorkspace(repoId, workspaceId);
    BootstrapRun existing =
        bootstrapRunRepository
            .findByWorkspaceRowAndBootstrapCommand(workspace.id, bootstrapCommandId)
            .orElse(null);
    // Fully populate before persisting (the codebase's build-then-persist convention — a new row
    // must never be registered half-initialized).
    BootstrapRun run = existing != null ? existing : new BootstrapRun();
    run.workspace = workspace;
    run.bootstrapCommandId = bootstrapCommandId;
    run.commandName = commandName;
    run.outcome = outcome;
    run.commandId = commandId;
    run.exitCode = exitCode;
    run.ranAt = Instant.now();
    if (existing == null) {
      bootstrapRunRepository.persist(run);
    }
    // The last thing the unit does: it puts the insert (or the dirty-checked update) on the
    // statement side of the commit line, which is the only side inNewTx can retry.
    bootstrapRunRepository.flush();
  }

  /** The active workspace's last-run rows, for the workspace bootstrap surface. */
  @Transactional
  @ActivateRequestContext
  public List<BootstrapRunDto> listForWorkspace(Long id) {
    Workspace workspace = workspaceResolver.resolveActive(id);
    return bootstrapRunRepository.findByWorkspaceRow(workspace.id).stream()
        .map(bootstrapRunMapper::toDto)
        .toList();
  }

  private Workspace activeWorkspace(String repoId, String workspaceId) {
    return workspaceRepository
        .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + workspaceId));
  }
}
