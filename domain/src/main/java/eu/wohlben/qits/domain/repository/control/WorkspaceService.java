package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.InternalServerErrorException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.repository.dto.WorkspaceDto;
import eu.wohlben.qits.domain.repository.entity.Repository;
import eu.wohlben.qits.domain.repository.entity.Workspace;
import eu.wohlben.qits.domain.repository.entity.WorkspaceEvent;
import eu.wohlben.qits.domain.repository.entity.WorkspaceEventType;
import eu.wohlben.qits.domain.repository.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.domain.repository.entity.WorkspaceStatus;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceEventRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class WorkspaceService {

  @Inject RepositoryRepository repositoryRepository;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject WorkspaceEventRepository workspaceEventRepository;

  @Inject MetadataService metadataService;

  @Inject GitExecutor git;

  @Inject ContainerRuntime containers;

  @Inject WorkspaceContainerEventPublisher containerEvents;

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  @Inject QitsHostResolver qitsHostResolver;

  @Inject GitIdentity gitIdentity;

  @ConfigProperty(name = "qits.workspace.qits-port", defaultValue = "8080")
  String qitsPort;

  /** The URL a workspace container clones/pushes its branch over (the in-process JGit host). */
  private String cloneUrl(String repoId) {
    return "http://" + qitsHostResolver.qitsHost() + ":" + qitsPort + "/git/" + repoId;
  }

  /**
   * Runs a git command inside a workspace's container under {@code /workspace}, throwing on a
   * non-zero exit. The container-side counterpart of {@code git.exec(workspacePath, …)} now that
   * the checkout lives in the container rather than on the host.
   */
  private String containerGit(String repoId, String workspaceId, String... gitArgs) {
    String container = containers.containerName(workspaceId, repoId);
    String[] argv = new String[gitArgs.length + 1];
    argv[0] = "git";
    System.arraycopy(gitArgs, 0, argv, 1, gitArgs.length);
    ContainerRuntime.ExecResult result =
        containers.exec(container, "/workspace", java.util.Map.of(), argv);
    if (result.exitCode() != 0) {
      throw new InternalServerErrorException(
          "Container git failed ["
              + result.exitCode()
              + "]: "
              + String.join(" ", argv)
              + "\n"
              + result.output());
    }
    return result.output();
  }

  /** Creates {@code branch} from {@code parentBranch} host-side in the bare origin. */
  private void createBranchRefOnOrigin(Path originPath, String branch, String parentBranch) {
    try {
      git.exec(originPath.toFile(), "git", "branch", "--end-of-options", branch, parentBranch);
    } catch (Exception e) {
      throw new InternalServerErrorException("Failed to create branch: " + e.getMessage());
    }
  }

  /**
   * Materializes a workspace's container from its durable branch ref: runs the container and clones
   * {@code branch} into its {@code /workspace} (the commit identity arrives as container-level
   * {@code GIT_*} env via {@link WorkspaceContainerFactory}, so nothing is configured in the
   * clone). Removes the container again if the clone fails, so a retry can succeed. The branch ref
   * must already exist in the origin — this is the on-demand half of workspace creation, invoked
   * lazily by {@link #ensureContainer} for never-provisioned and pruned workspaces alike.
   */
  private void provisionContainer(
      String repoId, String workspaceId, String branch, String parentBranch) {
    String container = containers.run(repoId, workspaceId, branch, parentBranch);

    ContainerRuntime.ExecResult clone =
        containers.exec(
            container,
            null,
            java.util.Map.of(),
            "git",
            "clone",
            "--branch",
            branch,
            cloneUrl(repoId),
            "/workspace");
    if (clone.exitCode() != 0) {
      containers.rm(container);
      throw new InternalServerErrorException("Clone into container failed: " + clone.output());
    }
  }

  /** Appends a history event to a workspace's timeline. */
  private void recordEvent(
      Workspace workspace, WorkspaceEventType type, String branch, String target, String commit) {
    workspaceEventRepository.persist(
        WorkspaceEvent.builder()
            .workspace(workspace)
            .type(type)
            .branch(branch)
            .parent(workspace.parent)
            .target(target)
            .commit(commit)
            .at(Instant.now())
            .build());
  }

  public List<WorkspaceDto> listWorkspaces(String repoId) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    Path originPath = Path.of(dataDir, repoId, "origin");
    // Live container set (one docker ps), so RUNNING stays accurate even when docker state changed
    // out-of-band; the persisted column carries the STOPPED/PROVISIONING/FAILED signal otherwise.
    Set<String> runningIds =
        containers.listWorkspaceContainers(repoId).stream()
            .map(ContainerRuntime.ContainerInfo::workspaceId)
            .collect(Collectors.toSet());
    // The branch tree shows only live workspaces; resolved ones live in the history view.
    return workspaceRepository.findActiveByRepositoryId(repoId).stream()
        .map(
            wt -> {
              String branch = wt.branch;
              AheadBehind ab = aheadBehind(originPath, wt.parent, branch);
              // Only diverged branches (both ahead and behind) can't fast-forward and so risk a
              // conflict; everything else integrates cleanly, so skip the extra merge-tree probe.
              boolean conflicts =
                  ab.ahead() != null
                      && ab.behind() != null
                      && ab.ahead() > 0
                      && ab.behind() > 0
                      && wouldConflict(originPath, wt.parent, branch);
              WorkspaceRuntimeStatus runtime =
                  runningIds.contains(wt.workspaceId)
                      ? WorkspaceRuntimeStatus.RUNNING
                      : wt.runtimeStatus == WorkspaceRuntimeStatus.RUNNING
                          ? WorkspaceRuntimeStatus.STOPPED
                          : wt.runtimeStatus;
              return new WorkspaceDto(
                  wt.workspaceId,
                  wt.parent,
                  branch,
                  ab.ahead(),
                  ab.behind(),
                  conflicts,
                  wt.status,
                  runtime,
                  wt.runtimeError,
                  wt.preamble,
                  wt.result,
                  wt.resolvedAt);
            })
        .toList();
  }

  /** A single active workspace's current DTO (runtime status computed live), or 404. */
  public WorkspaceDto getWorkspace(String repoId, String workspaceId) {
    return listWorkspaces(repoId).stream()
        .filter(w -> workspaceId.equals(w.workspaceId()))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + workspaceId));
  }

  /**
   * Whether {@code branch} — workspace-backed or plain — can be removed with no data loss: it is
   * not its own parent (the main branch can't be cleaned up), has no unmerged commits ({@code ahead
   * == 0} against its parent), a clean working tree when workspace-backed, and no other workspace
   * forks from it. A plain branch's parent is the repository's main branch; a workspace's is its
   * fork point. This is the single criterion the UI, the cleanup endpoint and post-integrate
   * cleanup all use.
   */
  public boolean canCleanupBranch(
      String repoId, Path originPath, String branch, String mainBranch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      return false;
    }
    Workspace wt = findWorkspaceByBranch(repoId, branch);
    String parent =
        (wt != null && wt.parent != null && !wt.parent.isBlank()) ? wt.parent : mainBranch;
    // No usable parent, or the branch *is* its parent (e.g. main): nothing to merge into, never
    // safe.
    if (parent == null || parent.isBlank() || parent.equals(branch)) {
      return false;
    }
    // ahead == null means git couldn't compare; ahead > 0 means commits not yet in the parent.
    Integer ahead = aheadBehind(originPath, parent, branch).ahead();
    if (ahead == null || ahead != 0) {
      return false;
    }
    if (wt != null) {
      // The working tree lives in the container; a dirty tree or unpushed commits (which the
      // origin-side ahead/behind above cannot see) both mean cleanup could destroy work.
      if (!isWorkspaceClean(repoId, wt)
          || !isFullyPushed(repoId, originPath, wt.workspaceId, wt.branch)) {
        return false;
      }
    }
    return !hasChildren(repoId, branch);
  }

  /**
   * Whether the container's HEAD equals the branch's ref in the origin — i.e. every commit made
   * inside the container has been pushed. The origin-side ahead/behind can't see container-local
   * commits, so without this a "safe" cleanup could delete unpushed work. A missing container means
   * nothing is left to lose, so treat it as pushed.
   */
  boolean isFullyPushed(String repoId, Path originPath, String workspaceId, String branch) {
    String container = containers.containerName(workspaceId, repoId);
    if (!containers.exists(container)) {
      return true;
    }
    if (branch == null || branch.isBlank()) {
      return true;
    }
    ContainerRuntime.ExecResult head =
        containers.exec(container, "/workspace", java.util.Map.of(), "git", "rev-parse", "HEAD");
    if (head.exitCode() != 0) {
      return false; // unknown container state — never delete blindly
    }
    try {
      String originSha =
          git.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/" + branch).trim();
      return head.output().trim().equals(originSha);
    } catch (Exception e) {
      return false;
    }
  }

  /** The parent a branch is compared against and how far it is ahead/behind it. */
  public record BranchSummary(String parent, Integer ahead, Integer behind) {}

  /**
   * Resolves a branch's parent — its workspace's fork point when workspace-backed, otherwise the
   * repository's {@code mainBranch} — and how far it is ahead of and behind that parent. Returns a
   * {@code null} parent (and zero counts) for the main branch itself or when no parent resolves.
   * Used to drive the branch tree's ahead/behind connector and commits popover for every branch,
   * including those without a workspace.
   */
  public BranchSummary summarize(String repoId, Path originPath, String branch, String mainBranch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      return new BranchSummary(null, 0, 0);
    }
    Workspace wt = findWorkspaceByBranch(repoId, branch);
    String parent =
        (wt != null && wt.parent != null && !wt.parent.isBlank()) ? wt.parent : mainBranch;
    if (parent == null || parent.isBlank() || parent.equals(branch)) {
      return new BranchSummary(null, 0, 0);
    }
    AheadBehind ab = aheadBehind(originPath, parent, branch);
    return new BranchSummary(parent, ab.ahead(), ab.behind());
  }

  /**
   * True when the workspace container's working tree has no staged or unstaged changes. The
   * container <em>is</em> the working tree, so no container means there is nothing uncommitted to
   * destroy — clean, symmetric with {@link #isFullyPushed}'s absent-means-pushed. A failed status
   * probe on a live container stays dirty: that state is genuinely unknown, never delete blindly.
   */
  private boolean isWorkspaceClean(String repoId, Workspace wt) {
    String container = containers.containerName(wt.workspaceId, repoId);
    if (!containers.exists(container)) {
      return true;
    }
    ContainerRuntime.ExecResult status =
        containers.exec(
            container, "/workspace", java.util.Map.of(), "git", "status", "--porcelain");
    return status.exitCode() == 0 && status.output().isBlank();
  }

  /** True when another workspace forks from {@code branch} (i.e. lists it as its parent). */
  private boolean hasChildren(String repoId, String branch) {
    for (Workspace other : workspaceRepository.findActiveByRepositoryId(repoId)) {
      if (branch.equals(other.parent)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The on-disk path of a host workspace checked out on {@code branch}, if any. With workspace
   * containers there are no host checkouts (each branch lives in its container), so this is always
   * empty — the sync code then updates the bare origin ref directly rather than a checked-out
   * workspace. Kept as the seam so pull stays branch-aware if host workspaces ever return.
   */
  public Optional<Path> workspacePathForBranch(String repoId, String branch) {
    return Optional.empty();
  }

  /** The workspace that owns {@code branch}, or null when none matches. */
  private Workspace findWorkspaceByBranch(String repoId, String branch) {
    if (branch == null) {
      return null;
    }
    for (Workspace wt : workspaceRepository.findActiveByRepositoryId(repoId)) {
      if (branch.equals(wt.branch)) {
        return wt;
      }
    }
    return null;
  }

  /**
   * Counts how far {@code branch} is ahead of and behind its {@code parent} branch. Runs in the
   * bare origin, which holds every workspace branch as a ref. Returns {@code (0, 0)} when the two
   * names are the same or either is missing, and {@code (null, null)} if git can't resolve a ref.
   */
  private AheadBehind aheadBehind(Path originPath, String parent, String branch) {
    if (parent == null
        || branch == null
        || parent.isBlank()
        || branch.isBlank()
        || parent.equals(branch)
        || !Files.exists(originPath)) {
      return new AheadBehind(0, 0);
    }
    try {
      // `--left-right --count A...B` prints "<behind>\t<ahead>": commits in A not B, then B not A.
      String out =
          git.exec(
                  originPath.toFile(),
                  "git",
                  "rev-list",
                  "--left-right",
                  "--count",
                  parent + "..." + branch)
              .trim();
      String[] parts = out.split("\\s+");
      if (parts.length != 2) {
        return new AheadBehind(null, null);
      }
      return new AheadBehind(Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
    } catch (Exception e) {
      return new AheadBehind(null, null);
    }
  }

  private record AheadBehind(Integer ahead, Integer behind) {}

  /**
   * Whether merging {@code parent} into {@code branch} would produce conflicts, decided by a real
   * three-way merge in the object store via {@code git merge-tree --write-tree} (no working tree
   * touched). It exits 0 when the merge is clean and 1 when it conflicts; any other outcome (error,
   * unresolvable ref) is treated as "no conflict" so we never raise a false warning. Runs in the
   * bare origin, which holds every branch ref.
   */
  private boolean wouldConflict(Path originPath, String parent, String branch) {
    if (parent == null
        || branch == null
        || parent.isBlank()
        || branch.isBlank()
        || parent.equals(branch)
        || parent.startsWith("-")
        || branch.startsWith("-")
        || !Files.exists(originPath)) {
      return false;
    }
    try {
      GitExecutor.ExecResult result =
          git.execAllowNonZero(
              originPath.toFile(), "git", "merge-tree", "--write-tree", branch, parent);
      return result.exitCode() == 1;
    } catch (Exception e) {
      return false;
    }
  }

  public Workspace createWorkspace(
      String repoId, String workspaceId, String parent, String branch) {
    return createWorkspace(repoId, workspaceId, parent, branch, null);
  }

  @Transactional
  public Workspace createWorkspace(
      String repoId, String workspaceId, String parent, String branch, String preamble) {
    var repo =
        repositoryRepository
            .findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    // `workspaceId` becomes a path segment under the repo's workspaces dir, so it must be a strict
    // slug: no slashes/dots/dashes-leading that could traverse out of the dir or smuggle a git
    // flag.
    if (!workspaceId.matches("[A-Za-z0-9_-]{1,64}") || workspaceId.startsWith("-")) {
      throw new BadRequestException("Invalid workspace id: " + workspaceId);
    }

    Path originPath = Path.of(dataDir, repoId, "origin");
    if (!Files.exists(originPath)) {
      throw new NotFoundException("Repository origin not found on disk");
    }

    // Resolved rows linger (soft delete), so only an ACTIVE workspace blocks the id — a resolved
    // one
    // can be reused.
    if (workspaceRepository.existsActiveByRepositoryAndWorkspaceId(repoId, workspaceId)) {
      throw new BadRequestException("Workspace already exists: " + workspaceId);
    }

    // `parent` is the branch to fork from; `branch` is the new branch the workspace owns.
    // Each workspace gets its own branch so two workspaces never commit to the same branch.
    String parentBranch = (parent == null || parent.isBlank()) ? "master" : parent;
    String newBranch = (branch == null || branch.isBlank()) ? workspaceId : branch;
    // Both are user-supplied and passed to git: reject dash-leading names so they can't be smuggled
    // in as flags (argv flag injection).
    if (parentBranch.startsWith("-") || newBranch.startsWith("-")) {
      throw new BadRequestException("Invalid branch name");
    }

    // Only the durable state is created here: the branch ref host-side in the bare origin (so
    // ahead/behind and the merge-tree conflict probe, both origin-side, work from the first
    // second) plus the row below. No container, no clone — provisioning is lazy: first use goes
    // through ensureContainer, which materializes the container from this branch ref. That keeps
    // creation free of docker and the running git server (the cli seeds depend on this).
    createBranchRefOnOrigin(originPath, newBranch, parentBranch);

    Workspace workspace = new Workspace();
    workspace.workspaceId = workspaceId;
    workspace.repository = repo;
    workspace.parent = parentBranch;
    workspace.branch = newBranch;
    workspace.status = WorkspaceStatus.ACTIVE;
    workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
    workspace.preamble = preamble;
    workspaceRepository.persist(workspace);
    recordEvent(workspace, WorkspaceEventType.CREATED, newBranch, parentBranch, null);

    WorkspaceMetadata metadata = new WorkspaceMetadata();
    metadata.workspaceId = workspaceId;
    metadata.parent = parentBranch;
    metadataService.writeWorkspaceMetadata(repoId, metadata);

    return workspace;
  }

  /**
   * Creates the default workspace for a repository's main branch: a workspace on the
   * <em>existing</em> main branch (not a fork of a new branch), so working in it commits directly
   * to main. The workspace id is the branch name as a slug; it has no parent, since the main branch
   * is the root of the branch tree. Like {@link #createWorkspace} this writes only the durable row
   * — the container (with its checkout) is provisioned lazily on first use via {@link
   * #ensureContainer}. Idempotent: returns the existing workspace if one with the derived id is
   * already present. Called by {@link RepositoryService} so every freshly added repository starts
   * with its main branch workable.
   */
  @Transactional
  public Workspace createMainWorkspace(String repoId, String branch) {
    var repo =
        repositoryRepository
            .findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      throw new BadRequestException("Invalid main branch: " + branch);
    }
    String workspaceId = toWorkspaceSlug(branch);

    Path originPath = Path.of(dataDir, repoId, "origin");
    if (!Files.exists(originPath)) {
      throw new NotFoundException("Repository origin not found on disk");
    }
    if (workspaceRepository.existsActiveByRepositoryAndWorkspaceId(repoId, workspaceId)) {
      return workspaceRepository
          .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
          .orElseThrow();
    }

    // The main branch already exists in the origin, so there is no durable state to create beyond
    // the row below — no branch ref, no container. ensureContainer provisions on first use.
    Workspace workspace = new Workspace();
    workspace.workspaceId = workspaceId;
    workspace.repository = repo;
    workspace.parent = null; // the main branch is the tree root — it has no fork point
    workspace.branch = branch;
    workspace.status = WorkspaceStatus.ACTIVE;
    workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
    workspaceRepository.persist(workspace);
    recordEvent(workspace, WorkspaceEventType.CREATED, branch, null, null);

    WorkspaceMetadata metadata = new WorkspaceMetadata();
    metadata.workspaceId = workspaceId;
    metadata.parent = null;
    metadataService.writeWorkspaceMetadata(repoId, metadata);

    return workspace;
  }

  /**
   * Sanitizes a branch name into a workspace-id slug ([A-Za-z0-9_-], ≤64 chars, not dash-leading).
   */
  private static String toWorkspaceSlug(String branch) {
    String slug = branch.replaceAll("[^A-Za-z0-9_-]", "-");
    if (slug.length() > 64) {
      slug = slug.substring(0, 64);
    }
    if (slug.isBlank() || slug.startsWith("-")) {
      slug = "main";
    }
    return slug;
  }

  private record BranchParent(String branch, String parent) {}

  /**
   * Guarantees a running container for an ACTIVE workspace whose branch still exists, provisioning
   * one on demand — the container is a recreatable cache of the durable branch, so losing it is a
   * non-event. Idempotent:
   *
   * <ul>
   *   <li>container already running → stamp {@code RUNNING}, no-op. A live container is
   *       <em>never</em> re-cloned over, so unpushed {@code /workspace} commits are safe.
   *   <li>container present but stopped (e.g. a host/docker restart left it {@code Exited}) →
   *       {@code docker start} it in place. This keeps the {@code /workspace} clone and any
   *       unpushed commits — the lossless recovery a re-clone can't give — so it wins over
   *       re-provisioning.
   *   <li>container absent but the branch ref survives in origin → materialize a fresh container
   *       from that branch via {@link #provisionContainer} — the single provisioning path, for
   *       never-provisioned and pruned workspaces alike.
   *   <li>branch ref gone from origin → the work no longer exists anywhere: the workspace is
   *       ABANDONED here (now the <em>only</em> path to abandonment) and a 404 is thrown.
   * </ul>
   *
   * <p><strong>Loss window:</strong> recreation restores <em>origin</em> state only. Commits made
   * in a container but never pushed die with it; the live-container guard protects the graceful
   * case, but an unexpected container death is still lossy (see {@link #stopContainer} for the
   * lossless stop). Not {@code @Transactional}: each status transition commits in its own
   * transaction so a FAILED/ABANDONED outcome is persisted even though the method then throws, and
   * so it is safe to call from non-request threads (like {@code CommandService.prepare}).
   */
  public void ensureContainer(String repoId, String workspaceId) {
    String container = containers.containerName(workspaceId, repoId);

    // Load branch/parent and short-circuit a live container, in its own transaction.
    BranchParent snapshot =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace wt =
                      workspaceRepository
                          .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
                          .orElseThrow(
                              () -> new NotFoundException("Workspace not found: " + workspaceId));
                  if (containers.isRunning(container)) {
                    wt.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
                    wt.runtimeError = null;
                    return null; // already running — nothing to provision
                  }
                  return new BranchParent(wt.branch, wt.parent);
                });
    if (snapshot == null) {
      return;
    }

    // Present but not running — a container that died out-of-band (classically a host/docker
    // restart leaving it Exited). `isRunning` is false but the container (and its /workspace clone)
    // still exist, so start it back up in place rather than re-cloning: this keeps unpushed
    // commits,
    // the lossless recovery the graceful-stop path can't offer once the container died
    // unexpectedly.
    // The branch-gone abandonment below deliberately doesn't apply here — the work lives in the
    // container, not just origin.
    if (containers.exists(container)) {
      QuarkusTransaction.requiringNew()
          .run(() -> markRuntime(repoId, workspaceId, WorkspaceRuntimeStatus.PROVISIONING, null));
      try {
        containers.start(container);
        QuarkusTransaction.requiringNew()
            .run(() -> markRuntime(repoId, workspaceId, WorkspaceRuntimeStatus.RUNNING, null));
        // Cold -> RUNNING: bring the repository's auto-start daemons up with the container (async).
        containerEvents.fireStarted(repoId, workspaceId);
        return;
      } catch (RuntimeException e) {
        QuarkusTransaction.requiringNew()
            .run(
                () ->
                    markRuntime(
                        repoId,
                        workspaceId,
                        WorkspaceRuntimeStatus.FAILED,
                        truncate(e.getMessage())));
        throw e;
      }
    }

    Path originPath = Path.of(dataDir, repoId, "origin");
    if (snapshot.branch() == null
        || snapshot.branch().isBlank()
        || !branchExists(originPath, snapshot.branch())) {
      // The durable branch is gone: this is genuine death, so abandon (persisted before we throw).
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                Workspace wt =
                    workspaceRepository
                        .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
                        .orElseThrow(
                            () -> new NotFoundException("Workspace not found: " + workspaceId));
                wt.status = WorkspaceStatus.ABANDONED;
                wt.resolvedAt = Instant.now();
                wt.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                recordEvent(wt, WorkspaceEventType.ABANDONED, wt.branch, null, null);
              });
      throw new NotFoundException(
          "Workspace '" + workspaceId + "' has no branch to recreate from; abandoned");
    }

    QuarkusTransaction.requiringNew()
        .run(() -> markRuntime(repoId, workspaceId, WorkspaceRuntimeStatus.PROVISIONING, null));
    try {
      provisionContainer(repoId, workspaceId, snapshot.branch(), snapshot.parent());
      QuarkusTransaction.requiringNew()
          .run(() -> markRuntime(repoId, workspaceId, WorkspaceRuntimeStatus.RUNNING, null));
      // Cold -> RUNNING: bring the repository's auto-start daemons up with the container (async).
      containerEvents.fireStarted(repoId, workspaceId);
    } catch (RuntimeException e) {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  markRuntime(
                      repoId,
                      workspaceId,
                      WorkspaceRuntimeStatus.FAILED,
                      truncate(e.getMessage())));
      throw e;
    }
  }

  private void markRuntime(
      String repoId, String workspaceId, WorkspaceRuntimeStatus status, String error) {
    workspaceRepository
        .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
        .ifPresent(
            wt -> {
              wt.runtimeStatus = status;
              wt.runtimeError = error;
            });
  }

  private static String truncate(String s) {
    if (s == null) {
      return null;
    }
    return s.length() <= 2000 ? s : s.substring(0, 2000);
  }

  /** Whether {@code branch} still exists as a ref in the given repo's bare origin. */
  public boolean branchExists(String repoId, String branch) {
    return branchExists(Path.of(dataDir, repoId, "origin"), branch);
  }

  /** Whether {@code branch} still exists as a ref in the bare origin at {@code originPath}. */
  private boolean branchExists(Path originPath, String branch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-") || !Files.exists(originPath)) {
      return false;
    }
    try {
      GitExecutor.ExecResult r =
          git.execAllowNonZero(
              originPath.toFile(),
              "git",
              "rev-parse",
              "--verify",
              "--quiet",
              "refs/heads/" + branch);
      return r.exitCode() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Best-effort push of a workspace's branch to origin from inside its container (no-op if absent).
   */
  void pushBranch(String repoId, String workspaceId, String branch) {
    String container = containers.containerName(workspaceId, repoId);
    if (branch == null || branch.isBlank() || !containers.exists(container)) {
      return;
    }
    try {
      containers.exec(container, "/workspace", java.util.Map.of(), "git", "push", "origin", branch);
    } catch (RuntimeException ignored) {
      // best effort — a failed push must not block the stop it guards
    }
  }

  /**
   * Gracefully stops a workspace's container: pushes its branch to origin first (so committed work
   * is durable), removes the container, and marks the workspace {@code STOPPED} while leaving it
   * ACTIVE. The container is re-provisioned on next access via {@link #ensureContainer}. This is
   * the lossless counterpart to an unexpected container death — the one path that guarantees
   * unpushed commits are preserved before the container goes away.
   */
  @Transactional
  public void stopContainer(String repoId, String workspaceId) {
    Workspace workspace =
        workspaceRepository
            .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
            .orElseThrow(() -> new NotFoundException("Workspace not found: " + workspaceId));
    pushBranch(repoId, workspaceId, workspace.branch);
    // Settle the workspace's daemons before the container goes away, so a live daemon's
    // disappearance reads as a deliberate STOPPED (graceful: signal + grace) instead of a crash the
    // restart policy would resurrect. Synchronous — completes while the container still exists.
    containerEvents.fireStopping(repoId, workspaceId, true);
    containers.rm(containers.containerName(workspaceId, repoId));
    workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
  }

  @Transactional
  public MergeResult mergeWorkspace(String repoId, String workspaceId, String target) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    Workspace workspace =
        workspaceRepository
            .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
            .orElseThrow(() -> new NotFoundException("Workspace not found: " + workspaceId));

    String resolvedTarget = (target == null || target.isBlank()) ? "master" : target;

    // Resolve a target given as a workspace id to the branch that workspace owns.
    Workspace targetWorkspace =
        workspaceRepository
            .findActiveByRepositoryAndWorkspaceId(repoId, resolvedTarget)
            .orElse(null);
    if (targetWorkspace != null && targetWorkspace.branch != null) {
      resolvedTarget = targetWorkspace.branch;
    }

    String currentBranch = workspace.branch;
    // Integration merges origin refs (in a temp host workspace); a live container may hold
    // unpushed commits, so push the source branch first or the merge would miss them. No container
    // means nothing ever ran in the workspace, so its origin ref is provably complete — skip. A
    // live container's failed push still aborts the merge (a swallowed failure would silently
    // integrate a stale ref).
    if (containers.exists(containers.containerName(workspaceId, repoId))) {
      containerGit(repoId, workspaceId, "push", "origin", currentBranch);
    }
    MergeResult result = mergeIntoTarget(repoId, currentBranch, resolvedTarget);
    if (!result.hasConflicts()) {
      recordEvent(
          workspace, WorkspaceEventType.MERGED, currentBranch, resolvedTarget, result.commitHash());
    }
    return result;
  }

  /**
   * Integrates an arbitrary branch into a target branch, defaulting to the repository's configured
   * main branch when {@code target} is blank. Unlike {@link #mergeWorkspace}, the source needs no
   * workspace of its own — its branch ref is merged into the target's workspace (a temporary one is
   * created and removed when the target isn't checked out anywhere).
   */
  public MergeResult mergeBranch(String repoId, String source, String target) {
    return mergeBranch(repoId, source, target, null);
  }

  @Transactional
  public MergeResult mergeBranch(String repoId, String source, String target, String result) {
    // `source`/`target` are user-supplied: reject blank or dash-leading names so a value like
    // "-D" can't be smuggled to git as a flag (argv flag injection).
    if (source == null || source.isBlank() || source.startsWith("-")) {
      throw new BadRequestException("Invalid source branch: " + source);
    }

    Repository repo =
        repositoryRepository
            .findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    String resolvedTarget = (target == null || target.isBlank()) ? repo.mainBranch : target;
    if (resolvedTarget == null || resolvedTarget.isBlank() || resolvedTarget.startsWith("-")) {
      throw new BadRequestException("Invalid target branch: " + target);
    }
    if (source.equals(resolvedTarget)) {
      throw new BadRequestException("Cannot integrate '" + source + "' into itself");
    }

    MergeResult merged = mergeIntoTarget(repoId, source, resolvedTarget);

    // After a clean integration the source branch's commits live in the target, so when the source
    // is now safe to remove (fully merged, clean if workspace-backed, no dependents) we clean it up
    // —
    // whether it is a workspace or a plain branch.
    boolean cleanedUp = false;
    if (!merged.hasConflicts()) {
      Path originPath = Path.of(dataDir, repoId, "origin");
      if (canCleanupBranch(repoId, originPath, source, repo.mainBranch)) {
        doCleanupBranch(repoId, source, result);
        cleanedUp = true;
      }
    }

    return new MergeResult(merged.commitHash(), merged.hasConflicts(), merged.output(), cleanedUp);
  }

  /**
   * Removes a branch (and its workspace, if any) only when it is safe to do so — fully merged,
   * clean working tree when workspace-backed, no dependent workspaces (see {@link
   * #canCleanupBranch}). Because the UI performs this without a confirmation, the safety is
   * enforced here: an ineligible branch yields a 400 and is left untouched.
   */
  public void cleanupBranch(String repoId, String branch) {
    cleanupBranch(repoId, branch, null);
  }

  @Transactional
  public void cleanupBranch(String repoId, String branch, String result) {
    Repository repo =
        repositoryRepository
            .findByIdOptional(repoId)
            .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    Path originPath = Path.of(dataDir, repoId, "origin");
    if (!canCleanupBranch(repoId, originPath, branch, repo.mainBranch)) {
      throw new BadRequestException(
          "Branch '"
              + branch
              + "' cannot be cleaned up: it has uncommitted changes, unmerged commits, or dependent"
              + " workspaces");
    }

    doCleanupBranch(repoId, branch, result);
  }

  /**
   * Deletes a branch: resolves its workspace as INTEGRATED when one is checked out (reusing the
   * discard mechanics), otherwise just deletes the bare branch ref. Callers gate on {@link
   * #canCleanupBranch}.
   */
  private void doCleanupBranch(String repoId, String branch, String result) {
    Workspace wt = findWorkspaceByBranch(repoId, branch);
    if (wt != null) {
      doDiscard(repoId, wt, WorkspaceStatus.INTEGRATED, result);
      return;
    }
    Path originPath = Path.of(dataDir, repoId, "origin");
    try {
      git.exec(originPath.toFile(), "git", "branch", "-D", branch);
    } catch (Exception e) {
      throw new InternalServerErrorException(
          "Failed to delete branch '" + branch + "': " + e.getMessage());
    }
  }

  /**
   * Merges {@code sourceBranch} into {@code resolvedTarget}: runs {@code git merge} inside the
   * target branch's workspace, creating (and afterwards removing) a temporary workspace when the
   * target isn't already checked out. Shared by workspace and branch integration.
   */
  private MergeResult mergeIntoTarget(String repoId, String sourceBranch, String resolvedTarget) {
    Path originPath = Path.of(dataDir, repoId, "origin");

    // Find existing workspace for target branch or create a temp one
    Path mergeCwd = findWorkspacePathForBranch(repoId, resolvedTarget);
    boolean isTemp = false;
    if (mergeCwd == null) {
      mergeCwd =
          Path.of(dataDir, repoId, "workspaces", ".tmp-merge-" + System.currentTimeMillis())
              .toAbsolutePath();
      try {
        // The workspaces dir no longer holds host checkouts (they are containers now); ensure it
        // exists so the throwaway merge workspace can be created under it.
        Files.createDirectories(mergeCwd.getParent());
        git.exec(
            originPath.toFile(), "git", "worktree", "add", mergeCwd.toString(), resolvedTarget);
      } catch (Exception e) {
        throw new InternalServerErrorException(
            "Failed to create merge workspace: " + e.getMessage());
      }
      isTemp = true;
    }

    try {
      // The one host-spawned synthetic commit: identity via -c (not host env) so it stays explicit
      // at this call site and can't leak into other host git invocations.
      List<String> merge = new ArrayList<>(List.of("git"));
      merge.addAll(gitIdentity.inlineArgs());
      merge.addAll(
          List.of(
              "merge", sourceBranch, "-m", "Merge " + sourceBranch + " into " + resolvedTarget));
      String output = git.exec(mergeCwd.toFile(), merge.toArray(String[]::new));
      String commitHash = git.exec(mergeCwd.toFile(), "git", "rev-parse", "HEAD").trim();
      boolean hasConflicts = output.toLowerCase().contains("conflict");
      if (isTemp) {
        git.exec(originPath.toFile(), "git", "worktree", "remove", mergeCwd.toString());
      }
      return new MergeResult(commitHash, hasConflicts, output, false);
    } catch (InternalServerErrorException e) {
      throw e;
    } catch (Exception e) {
      if (isTemp) {
        try {
          git.exec(originPath.toFile(), "git", "worktree", "remove", "-f", mergeCwd.toString());
        } catch (Exception ignored) {
        }
      }
      throw new InternalServerErrorException("Git merge failed: " + e.getMessage());
    }
  }

  /**
   * Fast-forwards a workspace's branch to the latest commit of its parent branch. Runs {@code git
   * merge --ff-only} inside the workspace so the ref, index and working tree all advance together;
   * the {@code --ff-only} flag refuses (and reports an error) when the branch has diverged or the
   * working tree is dirty, which surfaces as a 400.
   */
  public String fastForwardWorkspace(String repoId, String workspaceId) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    Workspace workspace =
        workspaceRepository
            .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
            .orElseThrow(() -> new NotFoundException("Workspace not found: " + workspaceId));

    String parent = workspace.parent;
    if (parent == null || parent.isBlank()) {
      throw new BadRequestException(
          "Workspace '" + workspaceId + "' has no parent to fast-forward to");
    }
    if (parent.startsWith("-")) {
      throw new BadRequestException("Invalid parent branch");
    }

    // Inside the container: fetch, first fast-forward the container's own branch to origin's ref
    // (it may have advanced out-of-band, e.g. a host-side integration into it), then fast-forward
    // onto the parent and push. --ff-only refuses (400) on divergence or a dirty tree — so a branch
    // that diverged from its parent is correctly rejected even though the container was stale.
    ensureContainer(repoId, workspaceId); // re-provision a lost container from the branch first
    try {
      containerGit(repoId, workspaceId, "fetch", "origin");
      containerGit(repoId, workspaceId, "merge", "--ff-only", "origin/" + workspace.branch);
      String output = containerGit(repoId, workspaceId, "merge", "--ff-only", "origin/" + parent);
      containerGit(repoId, workspaceId, "push", "origin", workspace.branch);
      return output;
    } catch (Exception e) {
      throw new BadRequestException(
          "Cannot fast-forward workspace '"
              + workspaceId
              + "' to '"
              + parent
              + "': "
              + e.getMessage());
    }
  }

  /**
   * Merges a workspace's parent branch into it so a diverged branch (one with its own commits) can
   * catch up to the parent. Unlike {@link #fastForwardWorkspace}, this creates a merge commit, so
   * it works when {@code git merge --ff-only} can't. Runs inside the workspace. If the merge hits
   * conflicts it is aborted — leaving the workspace exactly as it was — and a 400 is returned. The
   * UI only offers this when the trial merge was clean, but the abort keeps the workspace usable
   * should state have changed underneath.
   */
  @Transactional
  public String updateWorkspaceFromParent(String repoId, String workspaceId) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    Workspace workspace =
        workspaceRepository
            .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
            .orElseThrow(() -> new NotFoundException("Workspace not found: " + workspaceId));

    String parent = workspace.parent;
    if (parent == null || parent.isBlank()) {
      throw new BadRequestException("Workspace '" + workspaceId + "' has no parent to update from");
    }
    if (parent.startsWith("-")) {
      throw new BadRequestException("Invalid parent branch");
    }

    ensureContainer(repoId, workspaceId); // re-provision a lost container from the branch first
    String container = containers.containerName(workspaceId, repoId);

    // Inside the container: fetch, sync the container's own branch to origin's ref (it may have
    // advanced out-of-band), then merge the parent in (a merge commit — works where ff-only can't).
    // Identity arrives as container-level GIT_* env (WorkspaceContainerFactory). On conflict, abort
    // so the workspace stays usable and return a 400. On success, push so the origin reflects the
    // merge.
    try {
      containerGit(repoId, workspaceId, "fetch", "origin");
      containerGit(repoId, workspaceId, "merge", "--ff-only", "origin/" + workspace.branch);
    } catch (Exception e) {
      throw new InternalServerErrorException(
          "Failed to fetch '" + parent + "' into '" + workspaceId + "': " + e.getMessage());
    }
    ContainerRuntime.ExecResult result =
        containers.exec(
            container,
            "/workspace",
            java.util.Map.of(),
            "git",
            "merge",
            "--no-edit",
            "origin/" + parent);

    if (result.exitCode() != 0) {
      containers.exec(container, "/workspace", java.util.Map.of(), "git", "merge", "--abort");
      throw new BadRequestException(
          "Cannot merge '" + parent + "' into '" + workspaceId + "' without conflicts");
    }
    containerGit(repoId, workspaceId, "push", "origin", workspace.branch);
    recordEvent(workspace, WorkspaceEventType.UPDATED_FROM_PARENT, null, parent, null);
    return result.output();
  }

  public void discardWorkspace(String repoId, String workspaceId) {
    discardWorkspace(repoId, workspaceId, null);
  }

  @Transactional
  public void discardWorkspace(String repoId, String workspaceId, String result) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));

    Workspace workspace =
        workspaceRepository
            .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
            .orElseThrow(() -> new NotFoundException("Workspace not found: " + workspaceId));

    doDiscard(repoId, workspace, WorkspaceStatus.ABANDONED, result);
  }

  /**
   * Removes a workspace from disk and deletes its branch, then <em>soft-deletes</em> the row: it is
   * marked with its {@code resolution} status ({@code INTEGRATED} for cleanup, {@code ABANDONED}
   * for discard) and kept as a persistent record (with its history events and the commands that ran
   * in it) rather than deleted. The on-disk metadata file is removed so discovery won't re-process
   * it.
   */
  private void doDiscard(
      String repoId, Workspace workspace, WorkspaceStatus resolution, String result) {
    Path originPath = Path.of(dataDir, repoId, "origin");

    try {
      String branch = workspace.branch;

      // Remove the workspace's container. Discard is intentionally lossy: unlike the graceful
      // stopContainer (which pushes first so recreation is lossless), here we delete the branch
      // right after, so pushing unpushed /workspace commits would be pointless — the operator asked
      // to throw this work away. Settle any live daemons first (immediate — no graceful signal, the
      // work is being discarded) so their disappearance doesn't read as a crash to be resurrected.
      containerEvents.fireStopping(repoId, workspace.workspaceId, false);
      containers.rm(containers.containerName(workspace.workspaceId, repoId));

      if (branch != null && !branch.isBlank()) {
        try {
          git.exec(originPath.toFile(), "git", "branch", "-D", "--", branch);
        } catch (Exception ignored) {
          // branch may already be gone
        }
      }

      workspace.status = resolution;
      workspace.resolvedAt = Instant.now();
      if (result != null && !result.isBlank()) {
        workspace.result = result;
      }
      recordEvent(
          workspace,
          resolution == WorkspaceStatus.INTEGRATED
              ? WorkspaceEventType.INTEGRATED
              : WorkspaceEventType.ABANDONED,
          branch,
          null,
          null);
      metadataService.deleteWorkspaceMetadata(repoId, workspace.workspaceId);
    } catch (InternalServerErrorException e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git discard failed: " + e.getMessage());
    }
  }

  private Path findWorkspacePathForBranch(String repoId, String branch) {
    // With workspace containers no branch has a host checkout the merge can run in, so integration
    // always spins up a throwaway host workspace in the bare origin ({@link #mergeIntoTarget}).
    // (Returning null unconditionally — rather than scanning the workspaces dir — also avoids
    // matching an unrelated on-disk checkout that shares the path.)
    return null;
  }

  public record MergeResult(
      String commitHash, boolean hasConflicts, String output, boolean cleanedUp) {}
}
