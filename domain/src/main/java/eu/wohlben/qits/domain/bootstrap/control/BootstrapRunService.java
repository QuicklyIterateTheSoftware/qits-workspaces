package eu.wohlben.qits.domain.bootstrap.control;

import eu.wohlben.qits.domain.bootstrap.dto.BootstrapRunDto;
import eu.wohlben.qits.domain.bootstrap.entity.BootstrapOutcome;
import eu.wohlben.qits.domain.bootstrap.entity.BootstrapRun;
import eu.wohlben.qits.domain.bootstrap.mapper.BootstrapCommandMapper;
import eu.wohlben.qits.domain.bootstrap.persistence.BootstrapRunRepository;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.repository.entity.Workspace;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangePublisher;
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
 */
@ApplicationScoped
public class BootstrapRunService {

  @Inject BootstrapRunRepository bootstrapRunRepository;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject BootstrapCommandMapper bootstrapCommandMapper;

  @Inject WorkspaceChangePublisher changePublisher;

  /** Upsert the last-run row for {@code (workspace, bootstrapCommandId)} and hint the UI. */
  @Transactional
  @ActivateRequestContext
  public void recordOutcome(
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
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.BOOTSTRAP);
  }

  /** The active workspace's last-run rows, for the workspace bootstrap surface. */
  @Transactional
  @ActivateRequestContext
  public List<BootstrapRunDto> listForWorkspace(String repoId, String workspaceId) {
    Workspace workspace = activeWorkspace(repoId, workspaceId);
    return bootstrapRunRepository.findByWorkspaceRow(workspace.id).stream()
        .map(bootstrapCommandMapper::toDto)
        .toList();
  }

  private Workspace activeWorkspace(String repoId, String workspaceId) {
    return workspaceRepository
        .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + workspaceId));
  }
}
