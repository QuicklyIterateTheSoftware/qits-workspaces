package eu.wohlben.qits.workspaces.control;

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

  /** Upsert the last-run row for {@code (workspace, bootstrapCommandId)} and hint the UI. */
  @Transactional
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
    changePublisher.fire(repoId, rowId, WorkspaceChangeHint.Topic.BOOTSTRAP);
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
