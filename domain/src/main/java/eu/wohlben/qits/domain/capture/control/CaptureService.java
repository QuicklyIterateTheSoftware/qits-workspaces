package eu.wohlben.qits.domain.capture.control;

import eu.wohlben.qits.domain.capture.dto.CaptureContent;
import eu.wohlben.qits.domain.error.InternalServerErrorException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.Workspace;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Turns a capture snapshot into a place to work: a new {@code feature/<date-time>} branch off the
 * repository's main branch plus a workspace on it whose preamble (the goal) carries the rendered
 * capture context. Delegates the actual creation to {@link WorkspaceService#createWorkspace} — the
 * normal loud-failure branch-off path, durable rows only (the container is provisioned lazily on
 * first use).
 *
 * <p>Fails closed: an unresolvable repository creates nothing. Unlike the OTLP receiver's {@code
 * _unscoped} bucket, there is no fail-open here — creating branches is not bucketing.
 */
@ApplicationScoped
public class CaptureService {

  /** UTC so branch names sort chronologically and match {@code createdAt} handling. */
  static final DateTimeFormatter BRANCH_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneOffset.UTC);

  private static final int MAX_NAME_ATTEMPTS = 100;

  @Inject RepositoryRepository repositoryRepository;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject WorkspaceService workspaceService;
  @Inject CaptureGoalRenderer goalRenderer;

  /**
   * Ingests one capture: resolves the repository (404, nothing created, when it doesn't), picks the
   * first free {@code feature/<timestamp>} name, renders the goal, and creates the workspace off
   * the repository's main branch.
   *
   * <p>{@code receivedAt} is a parameter rather than an internal clock read so name de-collision is
   * deterministically testable (the domain has no {@code Clock} idiom — callers pass {@code
   * Instant.now()}). {@code synchronized} closes the probe-then-create race between two same-minute
   * concurrent captures; at button-press rates that is all the coordination this needs (a loser
   * would otherwise fail loudly on the existing ref, never corrupt anything).
   */
  public synchronized Workspace capture(String repoId, CaptureContent content, Instant receivedAt) {
    if (repoId == null || repoId.isBlank()) {
      throw new NotFoundException("Capture payload names no repository");
    }
    Repository repo =
        repositoryRepository
            .findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
    String branch = availableBranchName(repoId, receivedAt);
    String workspaceId = WorkspaceService.toWorkspaceSlug(branch);
    String preamble = goalRenderer.render(content, receivedAt);
    return workspaceService.createWorkspace(
        repoId, workspaceId, repo.mainBranch, branch, preamble, false);
  }

  /**
   * {@code <prefix><yyyy-MM-dd-HHmm>}, suffixed {@code -2}, {@code -3}, … from the 2nd attempt. The
   * prefix is normally {@code feature/}; see {@link #branchPrefix}.
   */
  static String branchNameFor(String prefix, Instant receivedAt, int attempt) {
    String base = prefix + BRANCH_TIMESTAMP.format(receivedAt);
    return attempt <= 1 ? base : base + "-" + attempt;
  }

  /**
   * {@code feature/} unless the repo has a branch literally named {@code feature} — git's ref
   * namespace is filesystem-like, so {@code refs/heads/feature} blocks every {@code
   * refs/heads/feature/*}; such repos fall back to the dash shape {@code feature-<timestamp>}.
   */
  private String branchPrefix(String repoId) {
    return workspaceService.branchExists(repoId, "feature") ? "feature-" : "feature/";
  }

  /**
   * First candidate free in <em>both</em> namespaces: branch refs on the bare origin and ACTIVE
   * workspace ids (either can linger without the other — a branch created outside qits, or a
   * workspace whose slug collides). Two captures in the same minute must both land.
   */
  private String availableBranchName(String repoId, Instant receivedAt) {
    String prefix = branchPrefix(repoId);
    for (int attempt = 1; attempt <= MAX_NAME_ATTEMPTS; attempt++) {
      String candidate = branchNameFor(prefix, receivedAt, attempt);
      boolean taken =
          workspaceService.branchExists(repoId, candidate)
              || workspaceRepository.existsActiveByRepositoryAndWorkspaceId(
                  repoId, WorkspaceService.toWorkspaceSlug(candidate));
      if (!taken) {
        return candidate;
      }
    }
    throw new InternalServerErrorException(
        "No free capture branch name after " + MAX_NAME_ATTEMPTS + " attempts");
  }
}
