package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.InternalServerErrorException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceEvent;
import eu.wohlben.qits.workspaces.entity.WorkspaceEventType;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceEventRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkspaceService {

  private static final Logger LOG = Logger.getLogger(WorkspaceService.class);

  @Inject RepositoryLookup repositories;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject WorkspaceEventRepository workspaceEventRepository;

  @Inject WorkspaceMetadataStore workspaceMetadata;

  /**
   * Fired when a workspace resolves, so other contexts can drop the rows that hang off it. The
   * delete is soft, so their FK cascade never fires; before the extraction this class reached
   * across and hard-deleted prompt drafts and attachments itself.
   */
  @Inject Event<WorkspaceResolved> workspaceResolvedEvent;

  @Inject GitExecutor git;

  @Inject ContainerRuntime containers;

  @Inject WorkspaceContainerEventPublisher containerEvents;

  /**
   * Optional: the technical-process framework is a cross-context streaming primitive owned by the
   * application. Absent means the same work runs, unnarrated, with a null process id.
   */
  @Inject Instance<WorkspaceProcessTracker> processes;

  /**
   * The in-container workspace-daemon's liveness, observed alongside the docker reconciliation
   * ladder (docs/epics/qits-workspace-daemon/). {@code Instance<>} because apps without the backend
   * impl (cli, tests) have no {@link WorkspaceDaemonLiveness} bean — there it is simply empty.
   * <b>Part 1 is observational only</b>: {@link #ensureContainer} logs this signal but never
   * branches on it.
   */
  @Inject Instance<WorkspaceDaemonLiveness> clientLiveness;

  /**
   * The in-container workspace-daemon's last-reported working-tree cleanliness (clean/dirty),
   * surfaced as {@link WorkspaceDto#clean}. {@code Instance<>} for the same reason as {@link
   * #clientLiveness}: apps without the backend impl (cli, tests) have no {@link WorkspaceGitStatus}
   * bean and simply see it empty. Only consulted for RUNNING workspaces — the daemon reports only
   * while connected.
   */
  @Inject Instance<WorkspaceGitStatus> gitStatus;

  /**
   * The in-container workspace-daemon's last-reported coding-agent activity rollup, surfaced as
   * {@link WorkspaceDto#agentActivity}. {@code Instance<>} for the same reason as {@link
   * #gitStatus}: apps without the backend impl (cli, tests) have no {@link WorkspaceAgentActivity}
   * bean and simply see it empty. Only consulted for RUNNING workspaces — the daemon reports only
   * while connected.
   */
  @Inject Instance<WorkspaceAgentActivity> agentActivity;

  /**
   * The workspace registry's live view of each workspace's daemon — connected-since + the daemon
   * binary's build identity, surfaced as {@link WorkspaceDto#daemonConnectedAt}/{@code
   * daemonVersion}/{@code daemonBuildTime} (docs/epics/qits-workspace-registry/). {@code
   * Instance<>} for the same reason as {@link #gitStatus}: apps without the backend impl (cli,
   * tests) have no {@link WorkspaceDaemonInfo} bean and simply see it empty. Only consulted for
   * RUNNING workspaces.
   */
  @Inject Instance<WorkspaceDaemonInfo> daemonInfo;

  /**
   * Notifies a target workspace's in-container daemon to pull an incoming merge/integration this
   * host just pushed to its branch (docs/epics/qits-workspace-daemon/ bidirectional auto-sync).
   * {@code Instance<>} for the same reason as {@link #gitStatus}: apps without the backend impl
   * (cli, tests) have no {@link WorkspaceGitSync} bean and simply skip the notification — the
   * checkout then syncs on its next host git op, so nothing is lost.
   */
  @Inject Instance<WorkspaceGitSync> gitSync;

  /**
   * Awaits the in-container workspace-daemon's autonomous self-provision (clone + submodules on
   * boot) — the <b>sole</b> provisioning path (docs/epics/qits-workspace-daemon/ Part 2). {@code
   * Instance<>} because the real backend impl lives in {@code service}; apps without it (cli,
   * tests) supply a {@link WorkspaceDaemonProvisioner} test double that clones through the {@code
   * ContainerRuntime}. An empty {@code Instance<>} (or a daemon that never connects) fails the
   * provision — there is no host-driven fallback.
   */
  @Inject Instance<WorkspaceDaemonProvisioner> daemonProvisioner;

  /**
   * How long a fresh provision waits for a daemon to dial home before declaring the provision
   * FAILED. The daemon is the sole provisioner (there is no host-clone fallback), so this is the
   * stale-image discriminator: a modern image's daemon connects within ~a second, so a generous
   * default reliably distinguishes "no daemon here (rebuild the image)" from "slow daemon" — and
   * the former now fails loudly rather than degrading.
   */
  @ConfigProperty(name = "qits.workspace.provision.connect-timeout-ms", defaultValue = "30000")
  long provisionConnectTimeoutMs;

  /** How long, once a daemon is live, to wait for its terminal Provisioned/ProvisionFailed. */
  @ConfigProperty(name = "qits.workspace.provision.timeout-ms", defaultValue = "600000")
  long provisionTimeoutMs;

  /**
   * Runs {@link #beginEnsureContainer}'s provision work off the request thread — the HTTP call
   * returns the technical-process id immediately and the browser watches the work over SSE.
   */
  private final ExecutorService processExecutor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-provision");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    processExecutor.shutdownNow();
  }

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  @Inject GitIdentity gitIdentity;

  /**
   * Creates {@code branch} from {@code parentBranch} host-side in the bare origin.
   *
   * <p>An existing ref is a <em>client</em> error, not a server one: this is the normal-path guard
   * for "each workspace gets its own branch", and asking for a branch that is already there is a
   * 409 the caller can act on — a typo'd "branch off" name, or a branch created outside qits that
   * the caller meant to adopt. It is checked up front rather than inferred from git's exit status,
   * which cannot distinguish "ref exists" from a genuinely broken origin; the latter still 500s.
   */
  private void createBranchRefOnOrigin(Path originPath, String branch, String parentBranch) {
    if (branchExistsOnOrigin(originPath, branch)) {
      throw new ConflictException("Branch already exists: " + branch);
    }
    try {
      git.exec(originPath.toFile(), "git", "branch", "--end-of-options", branch, parentBranch);
    } catch (Exception e) {
      throw new InternalServerErrorException("Failed to create branch: " + e.getMessage());
    }
  }

  /**
   * Whether {@code branch} already exists as a local ref in the bare origin. Uses {@code show-ref
   * --verify --quiet}, whose non-zero exit (ref absent) is the answer, not a failure — so {@code
   * execAllowNonZero} rather than {@code exec}. Backs workspace adoption of a pre-existing branch
   * (see {@link #createWorkspace}).
   */
  private boolean branchExistsOnOrigin(Path originPath, String branch) {
    try {
      return git.execAllowNonZero(
                  originPath.toFile(),
                  "git",
                  "show-ref",
                  "--verify",
                  "--quiet",
                  "--end-of-options",
                  "refs/heads/" + branch)
              .exitCode()
          == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Materializes a workspace's container from its durable branch ref: runs the container and clones
   * {@code branch} into its {@code /workspace} (the commit identity arrives as container-level
   * {@code GIT_*} env via {@link WorkspaceContainerFactory}, so nothing is configured in the
   * clone). Removes the container again if the clone fails, so a retry can succeed. The branch ref
   * must already exist in the origin — this is the on-demand half of workspace creation, invoked
   * lazily by {@link #ensureContainer} for never-provisioned and pruned workspaces alike.
   *
   * <p>The clone is <b>autonomous and daemon-only</b>: the in-container workspace-daemon clones
   * {@code /workspace} and materializes submodules from its own boot-time env, then reports the
   * outcome up the control socket; qits only {@link #awaitDaemonProvision awaits} it (streaming the
   * daemon's output into the {@code clone} segment) and drives no git step. There is <b>no
   * host-driven fallback</b> (docs/epics/qits-workspace-daemon/ Part 2): a container with no live
   * daemon — a stale, pre-daemon image — fails to provision (rm + FAILED) rather than degrading.
   *
   * <p>A non-null {@code process} receives the provision as streamed segments: {@code docker-run}
   * (container create/start) and {@code clone} (the clone plus the daemon's submodule
   * materialization) — lines arrive live from the daemon over the socket, and each segment settles
   * when its step completes. A failure leaves the open segment for the caller to settle {@code
   * failed}.
   *
   * <p>Submodules resolve <b>natively</b> in the daemon's bounded {@code .gitmodules} walk: with
   * the repository addressed by its project-scoped name, a project's repos are siblings and a
   * committed relative submodule url ({@code ../<name>.git}) resolves against the origin to a
   * served sibling with no override (an absolute committed url is redirected explicitly). The
   * daemon sources the walk from the checkout's own {@code .gitmodules} (it has no DB), skipping
   * any submodule it can't resolve — so there is no import-scoping (an accepted trade-off; see
   * {@code Provisioner.materializeSubmodules}).
   */
  private void provisionContainer(
      String repoId,
      String workspaceId,
      Long rowId,
      String branch,
      String parentBranch,
      WorkspaceProcessTracker.Handle process) {
    if (process != null) {
      process.openSegment("docker-run");
    }
    Consumer<String> runLines =
        process == null ? null : line -> process.appendLine("docker-run", line);
    String container = containers.run(repoId, workspaceId, rowId, branch, parentBranch, runLines);
    if (process != null) {
      process.settleSegment("docker-run", true);
      process.openSegment("clone");
    }
    Consumer<String> cloneLines =
        process == null ? null : line -> process.appendLine("clone", line);

    // Provisioning is the daemon's job (docs/epics/qits-workspace-daemon/ Part 2): the in-container
    // workspace-daemon clones /workspace and materializes submodules from its own boot-time env,
    // then
    // reports Provisioned/ProvisionFailed over the control socket. qits only AWAITS that outcome,
    // feeding the clone segment from the daemon's streamed output — it drives no git step and no
    // longer falls back to a host-driven clone. A container with no live daemon (a stale,
    // pre-daemon
    // image) therefore cannot be provisioned: it fails loudly (rm + FAILED), recoverable by
    // rebuilding the image so the daemon is present.
    ProvisionResult outcome = awaitDaemonProvision(repoId, workspaceId, rowId, cloneLines);
    if (!outcome.ok()) {
      containers.rm(container);
      throw new InternalServerErrorException(
          "workspace-daemon self-provision failed: " + outcome.message());
    }
    if (process != null) {
      process.settleSegment("clone", true);
    }
  }

  /**
   * Await the in-container daemon's autonomous self-provision. The daemon is the <b>sole</b>
   * provisioner (docs/epics/qits-workspace-daemon/ Part 2) — there is no host-driven fallback — so
   * a missing provisioner bean or a daemon that never dials home within the connect window is a
   * provision FAILURE, not a degradation. In apps without the backend impl (cli, tests with no real
   * container), a {@link WorkspaceDaemonProvisioner} test double stands in for the daemon and
   * clones the checkout through the {@code ContainerRuntime}.
   */
  private ProvisionResult awaitDaemonProvision(
      String repoId, String workspaceId, Long rowId, Consumer<String> onLine) {
    if (!daemonProvisioner.isResolvable()) {
      return ProvisionResult.failed("no workspace-daemon provisioner is available");
    }
    return daemonProvisioner
        .get()
        .awaitProvision(
            rowId,
            Duration.ofMillis(provisionConnectTimeoutMs),
            Duration.ofMillis(provisionTimeoutMs),
            onLine)
        .orElseGet(
            () ->
                ProvisionResult.failed(
                    "no workspace-daemon dialed home within "
                        + provisionConnectTimeoutMs
                        + "ms — is the container running an image with the daemon?"));
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
    repositories.require(repoId);

    Path originPath = Path.of(dataDir, repoId, "origin");
    // Live container set (one docker ps), so RUNNING stays accurate even when docker state changed
    // out-of-band; the persisted column carries the STOPPED/PROVISIONING/FAILED signal otherwise.
    Set<String> runningIds =
        containers.listWorkspaceContainers(repoId).stream()
            // ps -a lists stopped containers too; only genuinely-running ones count as RUNNING (a
            // deliberately stopped or Exited container is present but must read as STOPPED).
            .filter(ContainerRuntime.ContainerInfo::running)
            .map(ContainerRuntime.ContainerInfo::workspaceId)
            .collect(Collectors.toSet());
    // The newest daemon build connected anywhere is the registry-only "latest agent version"
    // (docs/epics/qits-workspace-registry/): a RUNNING workspace whose build is strictly older is
    // flagged daemonOutdated so the UI can offer a Recreate. Computed once per list, across all
    // repos' live daemons — the notion is registry-wide, not per-repository.
    WorkspaceDaemonInfo.Info latestDaemon =
        daemonInfo.isResolvable() ? latestDaemon(daemonInfo.get().all()) : null;
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
              // Clean/dirty is only knowable while the daemon is connected (RUNNING); otherwise it
              // stays null (unknown ⇒ no badge). The daemon re-reports on reconnect.
              Boolean clean =
                  runtime == WorkspaceRuntimeStatus.RUNNING && gitStatus.isResolvable()
                      ? gitStatus.get().isClean(wt.id).orElse(null)
                      : null;
              // Agent activity shares clean/dirty's RUNNING-only, self-healing contract.
              AgentActivityState activity =
                  runtime == WorkspaceRuntimeStatus.RUNNING && agentActivity.isResolvable()
                      ? agentActivity.get().activityFor(wt.id).orElse(null)
                      : null;
              // Registry facts (connected-since + daemon build identity) share clean/dirty's
              // RUNNING-only, in-memory contract: known only while the daemon's socket is live.
              WorkspaceDaemonInfo.Info info =
                  runtime == WorkspaceRuntimeStatus.RUNNING && daemonInfo.isResolvable()
                      ? daemonInfo.get().lookup(wt.id).orElse(null)
                      : null;
              return new WorkspaceDto(
                  wt.id,
                  wt.workspaceId,
                  wt.parent,
                  branch,
                  ab.ahead(),
                  ab.behind(),
                  conflicts,
                  wt.status,
                  runtime,
                  wt.runtimeError,
                  clean,
                  activity,
                  wt.preamble,
                  wt.result,
                  wt.resolvedAt,
                  info != null ? info.connectedAt() : null,
                  info != null ? info.version() : null,
                  info != null ? info.buildTime() : null,
                  daemonOutdated(info, latestDaemon));
            })
        .toList();
  }

  /**
   * Orders live daemon connections by build recency: build time first (the {@code -SNAPSHOT}
   * tiebreaker the registry epic is built on), then version as a stable last resort. Entries with
   * no reported build time are filtered out by {@link #latestDaemon} before this ever sees them.
   */
  private static final Comparator<WorkspaceDaemonInfo.Info> DAEMON_BUILD_ORDER =
      Comparator.comparing(WorkspaceDaemonInfo.Info::buildTime)
          .thenComparing(i -> i.version() == null ? "" : i.version());

  /**
   * The newest daemon build among live connections, or {@code null} when none reports a build time
   * (older images) — an unknowable build can't be "the latest", so it never wins.
   */
  private static WorkspaceDaemonInfo.Info latestDaemon(Collection<WorkspaceDaemonInfo.Info> all) {
    return all.stream().filter(i -> i.buildTime() != null).max(DAEMON_BUILD_ORDER).orElse(null);
  }

  /**
   * Whether {@code info}'s daemon build is strictly older than {@code latest} — {@code true} only
   * when both build times are known and this one precedes the newest. Returns {@code null} (not
   * {@code false}) whenever the two can't be compared (no live daemon, no reported build time on
   * either side, or no newer build exists), so an uncomparable or up-to-date workspace shows no
   * warning rather than a misleading verdict.
   */
  private static Boolean daemonOutdated(
      WorkspaceDaemonInfo.Info info, WorkspaceDaemonInfo.Info latest) {
    if (info == null || info.buildTime() == null || latest == null || latest.buildTime() == null) {
      return null;
    }
    return info.buildTime().isBefore(latest.buildTime()) ? Boolean.TRUE : null;
  }

  /** A single active workspace's current DTO (runtime status computed live), or 404. */
  public WorkspaceDto getWorkspace(Long id) {
    Workspace workspace = requireActive(id);
    return listWorkspaces(workspace.repositoryId).stream()
        .filter(w -> id.equals(w.id()))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + id));
  }

  /**
   * The ACTIVE workspace with this id, or 404. One lookup: the id is the identity, so no repository
   * is needed to select the row — the repository is read <em>off</em> it, by the callers that build
   * container names and on-disk paths from the label.
   */
  private Workspace requireActive(Long id) {
    if (id == null) {
      throw new NotFoundException("Workspace not found: null");
    }
    return workspaceRepository
        .findActiveById(id)
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + id));
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
          || !isFullyPushed(repoId, originPath, wt.workspaceId, wt.id, wt.branch)) {
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
  boolean isFullyPushed(
      String repoId, Path originPath, String workspaceId, Long rowId, String branch) {
    String container = containers.containerName(workspaceId, repoId);
    if (!containers.exists(container)) {
      return true;
    }
    if (branch == null || branch.isBlank()) {
      return true;
    }
    // The daemon reports its head on every GitStatus frame and auto-pushes committed work, so
    // comparing that against the origin's ref answers this without reaching into the container.
    // Unknown — no live daemon, nothing reported yet, or no registry bean at all (cli, tests) — is
    // NOT "in sync": refuse, exactly as an unreadable container used to.
    Optional<String> reportedHead =
        gitStatus.isUnsatisfied() ? Optional.empty() : gitStatus.get().head(rowId);
    if (reportedHead.isEmpty()) {
      return false;
    }
    try {
      String originSha =
          git.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/" + branch).trim();
      return reportedHead.get().trim().equals(originSha);
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
    // Only an explicit CLEAN from the daemon counts. Unknown — no live daemon, none reported yet,
    // or no registry bean at all — is treated as dirty and refuses the operation. The host has no
    // second opinion to fall back on any more: the `docker exec git status` this used to run went
    // into the daemon with the rest of the in-container git.
    return !gitStatus.isUnsatisfied() && gitStatus.get().isClean(wt.id).orElse(false);
  }

  /**
   * Refuses an operation that would clobber or silently discard uncommitted work: throws a 400 when
   * the workspace's container has a dirty working tree. The daemon-reported Clean/Dirty state
   * already hides/reroutes these actions in the UI; this is the matching server-side guard so a
   * direct API call can't bypass it. Symmetric with {@link #isWorkspaceClean} — an absent container
   * is clean (nothing uncommitted to lose), so a stopped workspace is never blocked here.
   */
  private void requireCleanWorkingTree(String repoId, Workspace wt, String operation) {
    if (!isWorkspaceClean(repoId, wt)) {
      throw new BadRequestException(
          "Cannot "
              + operation
              + " workspace '"
              + wt.workspaceId
              + "': it has uncommitted changes. Commit or discard them first.");
    }
  }

  /**
   * Pre-flight guard shared by branch integration ({@link #mergeBranch}) and workspace integration
   * ({@link #mergeWorkspace}) — and therefore by the MCP {@code integrateBranch} tool, which routes
   * through {@code mergeBranch}. Integration merges the source branch's <em>origin</em> ref (inside
   * the target's workspace), so before that ref is read this makes it faithful to the live source
   * workspace:
   *
   * <ol>
   *   <li>refuses a dirty working tree with a 400 — the origin-side merge would silently leave the
   *       workspace's uncommitted work behind; and
   *   <li>pushes the container's branch so every commit made inside the container reaches the
   *       origin ref the merge reads. The push is <em>not</em> swallowed: a failure aborts the
   *       whole integration, because a silently-skipped push would integrate a stale ref.
   * </ol>
   *
   * A {@code null} source workspace or one with no live container — a plain branch or a stopped
   * workspace — has nothing uncommitted to lose and a provably complete origin ref (nothing ever
   * ran to advance it beyond origin), so it is a no-op. This deliberately does <em>not</em> require
   * the source to be up to date with the target: integrating a diverged but cleanly-mergeable
   * branch (yielding a merge commit, or a reported conflict) is a supported flow.
   *
   * <p>Takes the already-resolved {@link Workspace} rather than re-looking-it-up by branch: {@code
   * mergeWorkspace} identifies its source by {@code workspaceId}, and two active workspaces could
   * in principle share a branch — re-resolving by branch could push/clean-check the wrong
   * container.
   */
  private void requireSyncedSourceForIntegration(String repoId, Workspace sourceWorkspace) {
    if (sourceWorkspace == null
        || !containers.exists(containers.containerName(sourceWorkspace.workspaceId, repoId))) {
      return;
    }
    // No push from here: requireCleanWorkingTree has just established the daemon reports CLEAN,
    // and the daemon auto-pushes committed work as it goes, so origin already has the source
    // branch. The `docker exec git push` this used to run was a host-side second opinion on state
    // the daemon owns.
    requireCleanWorkingTree(repoId, sourceWorkspace, "integrate");
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

  /**
   * After a host-side integration/merge advanced {@code targetBranch}'s origin ref, tell the
   * workspace that owns it (if any) to pull the update into its container — so its checkout catches
   * up right away instead of lagging until the next host git op (docs/epics/qits-workspace-daemon/
   * bidirectional auto-sync). Best-effort and fire-and-forget: no target workspace, no backend impl
   * (cli/tests), or no live daemon all short-circuit to a no-op. The daemon only fast-forwards, so
   * a target tree that turned dirty in the race window is left intact.
   */
  private void notifyIncomingMerge(String repoId, String targetBranch) {
    Workspace target = findWorkspaceByBranch(repoId, targetBranch);
    if (target == null || gitSync.isUnsatisfied()) {
      return;
    }
    gitSync.get().pullFromOrigin(target.id, targetBranch);
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
    return createWorkspace(repoId, workspaceId, parent, branch, null, false);
  }

  public Workspace createWorkspace(
      String repoId, String workspaceId, String parent, String branch, String preamble) {
    return createWorkspace(repoId, workspaceId, parent, branch, preamble, false);
  }

  /**
   * Creates a workspace for a branch. Normally {@code branch} is a <em>new</em> branch this
   * workspace owns, forked off {@code parent} — a fresh ref is created in the origin. When {@code
   * adoptExisting} is set and {@code branch} already exists in the origin, the workspace instead
   * <em>adopts</em> that existing branch in place: no ref is created, the row is simply recorded
   * over it (the branch-list "Create workspace" button on a branch that has no workspace yet). The
   * container is provisioned lazily from the branch ref on first use either way.
   */
  @Transactional
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting) {
    var repo = repositories.require(repoId);

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
    String parentBranch = (parent == null || parent.isBlank()) ? defaultMainBranch(repo) : parent;
    String newBranch = (branch == null || branch.isBlank()) ? workspaceId : branch;
    // Both are user-supplied and passed to git: reject dash-leading names so they can't be smuggled
    // in as flags (argv flag injection).
    if (parentBranch.startsWith("-") || newBranch.startsWith("-")) {
      throw new BadRequestException("Invalid branch name");
    }

    // The branch is the resource being claimed, so the check belongs here — after `branch` and
    // `workspaceId` have been reconciled, not before. The id guard above is about the surrogate
    // label; it says nothing about the branch, because `branch` is an independent request field
    // that merely *defaults* to the id. Under the ordinary usage where the two coincide the two
    // guards agree, which is why the missing one went unnoticed; they stop coinciding the moment a
    // caller sets both, and `adoptExisting` then skips the ref creation that was the only thing
    // standing in the way. UQ_workspace_active_branch (V3) enforces the same rule structurally.
    if (workspaceRepository.existsActiveByRepositoryAndBranch(repoId, newBranch)) {
      throw new ConflictException("Branch already has an active workspace: " + newBranch);
    }

    // Only the durable state is created here: the branch ref host-side in the bare origin (so
    // ahead/behind and the merge-tree conflict probe, both origin-side, work from the first
    // second) plus the row below. No container, no clone — provisioning is lazy: first use goes
    // through ensureContainer, which materializes the container from this branch ref. That keeps
    // creation free of docker and the running git server (the cli seeds depend on this).
    //
    // Adoption: when asked to adopt and the branch already exists (e.g. a branch pushed or created
    // outside qits), skip the ref creation and record the workspace over the existing branch. The
    // normal path still creates the ref — and errors loudly if it already exists — so a typo'd
    // "branch off" name is never silently swallowed.
    if (!(adoptExisting && branchExistsOnOrigin(originPath, newBranch))) {
      createBranchRefOnOrigin(originPath, newBranch, parentBranch);
    }

    Workspace workspace = new Workspace();
    workspace.workspaceId = workspaceId;
    workspace.repositoryId = repoId;
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
    workspaceMetadata.write(repoId, metadata);

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
    var repo = repositories.require(repoId);
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      throw new BadRequestException("Invalid main branch: " + branch);
    }
    String workspaceId = toWorkspaceSlug(branch);

    Path originPath = Path.of(dataDir, repoId, "origin");
    if (!Files.exists(originPath)) {
      throw new NotFoundException("Repository origin not found on disk");
    }
    // Idempotent on the BRANCH, not on the derived id: the branch is what this workspace claims, so
    // "main is already workable" is answered by whoever owns the main branch — whatever it happens
    // to be called. Keying the check on the id instead made a workspace that merely *slugged* to
    // the same name (owning some other branch) look like the main workspace and be handed back in
    // its place.
    Optional<Workspace> existing = workspaceRepository.findActiveByRepositoryAndBranch(repoId, branch);
    if (existing.isPresent()) {
      return existing.get();
    }

    // The main branch already exists in the origin, so there is no durable state to create beyond
    // the row below — no branch ref, no container. ensureContainer provisions on first use.
    Workspace workspace = new Workspace();
    workspace.workspaceId = workspaceId;
    workspace.repositoryId = repoId;
    workspace.parent = null; // the main branch is the tree root — it has no fork point
    workspace.branch = branch;
    workspace.status = WorkspaceStatus.ACTIVE;
    workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
    workspaceRepository.persist(workspace);
    recordEvent(workspace, WorkspaceEventType.CREATED, branch, null, null);

    WorkspaceMetadata metadata = new WorkspaceMetadata();
    metadata.workspaceId = workspaceId;
    metadata.parent = null;
    workspaceMetadata.write(repoId, metadata);

    return workspace;
  }

  /**
   * Sanitizes a branch name into a workspace-id slug ([A-Za-z0-9_-], ≤64 chars, not dash-leading).
   * Public because the capture ingest derives workspace ids from its generated branch names through
   * the same rule.
   */
  public static String toWorkspaceSlug(String branch) {
    String slug = branch.replaceAll("[^A-Za-z0-9_-]", "-");
    if (slug.length() > 64) {
      slug = slug.substring(0, 64);
    }
    if (slug.isBlank() || slug.startsWith("-")) {
      slug = "main";
    }
    return slug;
  }

  /**
   * The branch a blank parent/target defaults to: the repository's configured main branch (set at
   * clone time from the remote's default), with "master" as the last-resort fallback for a row
   * whose {@code mainBranch} was never populated.
   */
  private static String defaultMainBranch(RepositoryLookup.RepositoryView repo) {
    return (repo.mainBranch() == null || repo.mainBranch().isBlank())
        ? "master"
        : repo.mainBranch();
  }

  private record BranchParent(String branch, String parent) {}

  /** A resolved workspace reduced to what the container/path machinery addresses it by. */
  private record WorkspaceRef(String repoId, String workspaceId) {}

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
  public void ensureContainer(Long id) {
    // Its own transaction: this is called from worker threads (the bootstrap runner's manual-run
    // executor) where no session is open, and the rest of ensureContainer already brackets each of
    // its own reads the same way.
    WorkspaceRef target =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace workspace = requireActive(id);
                  return new WorkspaceRef(workspace.repositoryId, workspace.workspaceId);
                });
    ensureContainer(target.repoId(), target.workspaceId(), id, null);
  }

  /**
   * The streaming Start: registers a {@link WorkspaceProcessTracker.Handle} for the workspace <em>before</em> any
   * work runs (so the very first {@code docker run} line is captured), spawns {@link
   * #ensureContainer(String, String, WorkspaceProcessTracker.Handle)} on a worker thread, and returns the process
   * id immediately. The browser watches the work — including the asynchronous service auto-start
   * phase — over the process's SSE stream; failures surface there (and in {@code
   * workspace.runtimeError}), not as an HTTP error. Throws 404 in-request when the workspace
   * doesn't exist, so a bad id still fails fast.
   */
  public String beginEnsureContainer(Long id) {
    Workspace resolved = QuarkusTransaction.requiringNew().call(() -> requireActive(id));
    String repoId = resolved.repositoryId;
    String workspaceId = resolved.workspaceId;
    Long rowId = resolved.id;
    WorkspaceProcessTracker.Handle process = tracker(repoId, workspaceId, rowId);
    processExecutor.submit(
        () -> {
          try {
            ensureContainer(repoId, workspaceId, rowId, process);
          } catch (RuntimeException e) {
            // Surface the failure in the stream: settle the open segment failed (appending the
            // message) and emit done. Idempotent — a no-op if the process already ended.
            if (process != null) {
              process.failProvision(e.getMessage());
            }
            LOG.debugf(
                e, "Streamed ensure-container failed for workspace %s/%s", repoId, workspaceId);
          }
        });
    return process == null ? null : process.id();
  }

  /**
   * Recreate a workspace's container on the current image — the way to roll a workspace onto a
   * newer {@code workspace-daemon} build (docs/epics/qits-workspace-registry/). Unlike {@link
   * #beginEnsureContainer} (which resumes an existing container in place), this deliberately tears
   * the old container down and provisions a fresh one, so a {@code docker run} picks up whatever
   * {@code qits.workspace.image} now resolves to.
   *
   * <p><b>Requires a provably clean working tree.</b> Recreating is lossy for uncommitted work, so
   * the gate is stricter than {@link #requireCleanWorkingTree}: it consults the daemon-reported
   * tri-state ({@link WorkspaceGitStatus#isClean}) and admits only an explicit clean. A dirty tree
   * and an UNKNOWN state (no live daemon, or none has reported yet) are <em>both</em> rejected with
   * a 400 — an unknowable tree is not a safe basis to destroy a container. The gate runs
   * synchronously so a bad request fails fast; the teardown+reprovision then streams like {@link
   * #beginEnsureContainer}: best-effort push (preserve committed work) → settle services gracefully
   * → {@code rm} the old container → {@link #ensureContainer} provisions a fresh one from the
   * durable branch (whose absent-container path re-runs {@link #provisionContainer}).
   */
  public String beginRecreateContainer(Long id) {
    Workspace resolved = QuarkusTransaction.requiringNew().call(() -> requireActive(id));
    String repoId = resolved.repositoryId;
    String workspaceId = resolved.workspaceId;
    Long rowId = resolved.id;
    requireCleanForRecreate(workspaceId, rowId);
    WorkspaceProcessTracker.Handle process = tracker(repoId, workspaceId, rowId);
    processExecutor.submit(
        () -> {
          try {
            // No backup push: requireCleanForRecreate established the daemon reports CLEAN, and
            // the daemon pushes committed work as it lands, so origin is already current. There is
            // nothing for the host to preserve that the daemon has not already sent.
            // Settle live services gracefully so their disappearance reads as deliberate, not a
            // crash
            // the restart policy would resurrect — the same courtesy stopContainer/discard extend.
            containerEvents.fireStopping(repoId, workspaceId, rowId, true);
            containers.rm(containers.containerName(workspaceId, repoId));
            // Container now absent → ensureContainer's provision path re-clones on the current
            // image.
            ensureContainer(repoId, workspaceId, rowId, process);
          } catch (RuntimeException e) {
            if (process != null) {
              process.failProvision(e.getMessage());
            }
            LOG.debugf(
                e, "Streamed recreate-container failed for workspace %s/%s", repoId, workspaceId);
          }
        });
    return process == null ? null : process.id();
  }

  /**
   * Recreate's registry-only clean gate: the daemon-reported tri-state must be an <em>explicit</em>
   * clean. Unlike {@link #requireCleanWorkingTree} (which execs git and folds unknown→dirty and
   * absent→clean), recreate must reject UNKNOWN in its own right — a workspace with no live daemon
   * reporting has an unknowable tree, and destroying its container could silently lose work — so
   * both dirty ({@code Optional.of(false)}) and unknown ({@code Optional.empty()}) throw 400; only
   * {@code Optional.of(true)} passes.
   */
  private void requireCleanForRecreate(String workspaceId, Long rowId) {
    Optional<Boolean> clean =
        gitStatus.isResolvable() ? gitStatus.get().isClean(rowId) : Optional.empty();
    if (!clean.equals(Optional.of(Boolean.TRUE))) {
      String state = clean.map(c -> c ? "clean" : "dirty").orElse("unknown");
      throw new BadRequestException(
          "Cannot recreate workspace '"
              + workspaceId
              + "': its working tree must be clean, but its reported state is "
              + state
              + ". Commit or discard changes, and ensure its daemon is connected, first.");
    }
  }

  /**
   * {@link #ensureContainer(String, String)} with an optional {@link WorkspaceProcessTracker.Handle} receiving
   * the work as streamed segments. With a process attached, every outcome also ends the process:
   * the already-running short-circuit completes it as a no-op, a provision failure fails it, and a
   * successful start hands the process id to the async bootstrap-then-service phase via {@link
   * WorkspaceContainerEventPublisher#fireStarted(String, String, String, boolean)} — the process
   * then reaches {@code done} only once the bootstrap chain and the auto-started services settle.
   */
  private void ensureContainer(
      String repoId, String workspaceId, Long rowId, WorkspaceProcessTracker.Handle process) {
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
      observeClientLiveness(repoId, workspaceId, rowId);
      if (process != null) {
        process.completeNoOp("container-start", "Container is already running — nothing to do.");
      }
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
        if (process != null) {
          process.openSegment("container-start");
        }
        containers.start(container);
        if (process != null) {
          process.appendLine(
              "container-start",
              "Started the existing container in place (its /workspace clone is preserved).");
          process.settleSegment("container-start", true);
          process.finishProvision(true);
        }
        QuarkusTransaction.requiringNew()
            .run(() -> markRuntime(repoId, workspaceId, WorkspaceRuntimeStatus.RUNNING, null));
        // Cold -> RUNNING, but not a fresh provision: the clone (and its bootstrap state) survived,
        // so the bootstrap runner passes straight through to service auto-start (async).
        containerEvents.fireStarted(
            repoId, workspaceId, rowId, process == null ? null : process.id(), false);
        observeClientLiveness(repoId, workspaceId, rowId);
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
                // The second termination path (beside doDiscard). The workspace is only
                // soft-deleted, so no FK cascade fires for the rows other contexts hang off it —
                // they clean up on this event, inside this transaction.
                workspaceResolvedEvent.fire(
                    new WorkspaceResolved(
                        repoId, workspaceId, wt.id, WorkspaceStatus.ABANDONED));
              });
      // The branch is gone, so any persisted /workspace volume is orphaned work — reap it. The
      // container is already absent on this path (we passed the isRunning/exists branches), so no
      // prior rm is needed. Best-effort.
      containers.removeWorkspaceVolume(workspaceId);
      throw new NotFoundException(
          "Workspace '" + workspaceId + "' has no branch to recreate from; abandoned");
    }

    QuarkusTransaction.requiringNew()
        .run(() -> markRuntime(repoId, workspaceId, WorkspaceRuntimeStatus.PROVISIONING, null));
    try {
      provisionContainer(repoId, workspaceId, rowId, snapshot.branch(), snapshot.parent(), process);
      if (process != null) {
        process.finishProvision(true);
      }
      QuarkusTransaction.requiringNew()
          .run(() -> markRuntime(repoId, workspaceId, WorkspaceRuntimeStatus.RUNNING, null));
      // Cold -> RUNNING off a fresh provision (bare clone): run the bootstrap chain, then service
      // auto-start (async; the runner passes straight through when the chain is empty).
      containerEvents.fireStarted(
          repoId, workspaceId, rowId, process == null ? null : process.id(), true);
      observeClientLiveness(repoId, workspaceId, rowId);
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

  /**
   * Record the in-container workspace-daemon's handshake liveness alongside the reconciliation
   * ladder (docs/epics/qits-workspace-daemon/) — <b>informational only</b>. It never gates a status
   * transition: a running container is decided by docker run-state and the branch ref exactly as
   * before, so a missing/broken socket (older images, a crashed binary, cli/tests with no backend
   * impl) degrades to today's behaviour. Later parts consult this once the socket drives behaviour.
   */
  private void observeClientLiveness(String repoId, String workspaceId, Long rowId) {
    if (clientLiveness.isResolvable()) {
      boolean live = clientLiveness.get().isDaemonLive(rowId);
      LOG.debugf(
          "workspace-daemon control socket for %s/%s: %s (informational; reconciliation unaffected)",
          repoId, workspaceId, live ? "present" : "not yet observed");
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
   * Gracefully pauses a workspace's container: pushes its branch to origin first (a durability
   * backstop for committed work), {@code docker stop}s the container <em>in place</em> — keeping it
   * and its {@code /workspace} clone — and marks the workspace {@code STOPPED} while leaving it
   * ACTIVE. On next access {@link #ensureContainer} resumes the same container via {@code
   * containers.start} (its {@code exists()} → {@code start()} branch), so the working tree survives
   * intact: uncommitted/untracked files and unpushed commits alike. This is a true pause, not a
   * teardown — the lossy {@link #rm} is reserved for discard (which deletes the branch afterward).
   */
  @Transactional
  public void stopContainer(Long id) {
    Workspace workspace = requireActive(id);
    String repoId = workspace.repositoryId;
    String workspaceId = workspace.workspaceId;
    // No durability push before the stop: the daemon pushes committed work as it lands, so origin
    // is current by the time we get here. A stop is a pause anyway — the container and its
    // /workspace clone survive it, so uncommitted work is not at risk either.
    // Settle the workspace's services before the container stops, so a live service's disappearance
    // reads as a deliberate STOPPED (graceful: signal + grace) instead of a crash the restart
    // policy
    // would resurrect. Synchronous — completes while the container is still running.
    containerEvents.fireStopping(repoId, workspaceId, workspace.id, true);
    containers.stop(containers.containerName(workspaceId, repoId));
    workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
  }

  /**
   * Deletes a workspace's container outright ({@code docker rm}) while keeping its durable branch
   * and the ACTIVE workspace row. Where {@link #stopContainer} pauses in place (keeping the
   * container and its {@code /workspace} volume for a lossless resume) and a plain recreate now
   * <em>preserves</em> that volume, this is the one deliberate reset: it tears the container down
   * <em>and removes the persistent {@code /workspace} volume</em>, so the next {@link
   * #ensureContainer} re-creates an empty volume and re-clones a fresh checkout from the branch —
   * losing every uncommitted working-tree change and any unpushed commit, as its Shift-guarded
   * "loses uncommitted changes" contract promises. Distinct from {@link #discardWorkspace}
   * (Abandon), which additionally deletes the branch and soft-deletes the row. Settles any live
   * services first (immediate — the container is being torn down) and leaves the workspace {@code
   * STOPPED} with no runtime error. No-op-safe if the container/volume are already gone (both
   * best-effort). The container is removed before the volume (docker refuses an in-use volume).
   */
  @Transactional
  public void deleteContainer(Long id) {
    Workspace workspace = requireActive(id);
    String repoId = workspace.repositoryId;
    String workspaceId = workspace.workspaceId;
    containerEvents.fireStopping(repoId, workspaceId, workspace.id, false);
    containers.rm(containers.containerName(workspaceId, repoId));
    containers.removeWorkspaceVolume(workspaceId);
    workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
    workspace.runtimeError = null;
  }

  @Transactional
  public MergeResult mergeWorkspace(Long id, String target) {
    Workspace workspace = requireActive(id);
    String repoId = workspace.repositoryId;
    var repo = repositories.require(repoId);

    String resolvedTarget = (target == null || target.isBlank()) ? defaultMainBranch(repo) : target;

    // `target` names a BRANCH. It is still accepted as a workspace label, because that is what the
    // branch-tree UI has always sent and the two coincide for an ordinary workspace; the lookup is
    // a convenience, not an identity claim. It is unambiguous now in a way it was not before: at
    // most one ACTIVE workspace owns a branch, so at most one row can answer.
    Workspace targetWorkspace =
        workspaceRepository
            .findActiveByRepositoryAndWorkspaceId(repoId, resolvedTarget)
            .orElse(null);
    if (targetWorkspace != null && targetWorkspace.branch != null) {
      resolvedTarget = targetWorkspace.branch;
    }

    String currentBranch = workspace.branch;
    // Same pre-integration guard as branch integration: refuse a dirty working tree and push the
    // container's unpushed commits so the origin ref this merge reads is complete (a swallowed push
    // would silently integrate a stale ref). Pass the workspace resolved by id (not its branch) so
    // the guard acts on exactly this container. A stopped/absent container is a no-op.
    requireSyncedSourceForIntegration(repoId, workspace);
    MergeResult result = mergeIntoTarget(repoId, currentBranch, resolvedTarget);
    if (!result.hasConflicts()) {
      recordEvent(
          workspace, WorkspaceEventType.MERGED, currentBranch, resolvedTarget, result.commitHash());
      // The merge advanced the target's origin ref; if a live workspace owns it, pull it in now.
      notifyIncomingMerge(repoId, resolvedTarget);
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

    var repo = repositories.require(repoId);

    String resolvedTarget = (target == null || target.isBlank()) ? repo.mainBranch() : target;
    if (resolvedTarget == null || resolvedTarget.isBlank() || resolvedTarget.startsWith("-")) {
      throw new BadRequestException("Invalid target branch: " + target);
    }
    if (source.equals(resolvedTarget)) {
      throw new BadRequestException("Cannot integrate '" + source + "' into itself");
    }
    // Guard and complete the source before the origin-side merge reads its ref: refuse a dirty
    // working tree (a plain branch has none, so it is never blocked) and push any commits that live
    // only inside the source container so they aren't silently dropped from the integration.
    requireSyncedSourceForIntegration(repoId, findWorkspaceByBranch(repoId, source));

    MergeResult merged = mergeIntoTarget(repoId, source, resolvedTarget);

    // After a clean integration the source branch's commits live in the target, so when the source
    // is now safe to remove (fully merged, clean if workspace-backed, no dependents) we clean it up
    // —
    // whether it is a workspace or a plain branch.
    boolean cleanedUp = false;
    if (!merged.hasConflicts()) {
      // The integration advanced the target's origin ref; if a live workspace owns it, pull it in
      // now (before any source cleanup — target and source are distinct branches).
      notifyIncomingMerge(repoId, resolvedTarget);
      Path originPath = Path.of(dataDir, repoId, "origin");
      if (canCleanupBranch(repoId, originPath, source, repo.mainBranch())) {
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
    var repo = repositories.require(repoId);

    Path originPath = Path.of(dataDir, repoId, "origin");
    if (!canCleanupBranch(repoId, originPath, branch, repo.mainBranch())) {
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
      // The one host-spawned synthetic commit. Identity is delivered both as -c (explicit at the
      // call site) AND as GIT_AUTHOR_*/GIT_COMMITTER_* env scoped to this invocation — the env form
      // is what actually guarantees attribution, because an ambient identity env inherited from the
      // host would otherwise outrank the -c config. The env is per-invocation, so it doesn't leak
      // into other host git calls.
      List<String> merge = new ArrayList<>(List.of("git"));
      merge.addAll(gitIdentity.inlineArgs());
      merge.addAll(
          List.of(
              "merge", sourceBranch, "-m", "Merge " + sourceBranch + " into " + resolvedTarget));
      String output =
          git.exec(mergeCwd.toFile(), gitIdentity.envMap(), merge.toArray(String[]::new));
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


  public void discardWorkspace(Long id) {
    discardWorkspace(id, null);
  }

  @Transactional
  public void discardWorkspace(Long id, String result) {
    Workspace workspace = requireActive(id);
    String repoId = workspace.repositoryId;
    repositories.require(repoId);

    requireCleanWorkingTree(repoId, workspace, "abandon");
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

      // Remove the workspace's container AND its persistent /workspace volume. Discard is
      // intentionally lossy: unlike the graceful stopContainer (which docker-stops in place so the
      // container and its /workspace volume survive), here we delete the container, its volume, AND
      // the branch right after, so preserving /workspace would be pointless — the operator asked to
      // throw this work away. Container first, then the volume (docker refuses an in-use volume).
      // Settle any live services first (immediate — no graceful signal, the work is being
      // discarded)
      // so their disappearance doesn't read as a crash to be resurrected.
      containerEvents.fireStopping(repoId, workspace.workspaceId, workspace.id, false);
      containers.rm(containers.containerName(workspace.workspaceId, repoId));
      containers.removeWorkspaceVolume(workspace.workspaceId);

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
      // Pre-launch composition state (prompt drafts, their attachments) is not a durable record
      // like the history events, and its FK cascade never fires because the workspace row is only
      // soft-deleted. Whoever owns those tables drops them on this event, in this transaction.
      workspaceResolvedEvent.fire(
          new WorkspaceResolved(repoId, workspace.workspaceId, workspace.id, resolution));
      workspaceMetadata.delete(repoId, workspace.workspaceId);
    } catch (InternalServerErrorException e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git discard failed: " + e.getMessage());
    }
  }

  /**
   * A tracking handle for a streamed operation, or {@code null} when no {@link
   * WorkspaceProcessTracker} is installed — every call site already treats null as "run it
   * unnarrated", which is the pre-streaming behaviour.
   */
  private WorkspaceProcessTracker.Handle tracker(String repoId, String workspaceId, Long rowId) {
    return processes.isResolvable() ? processes.get().begin(repoId, workspaceId, rowId) : null;
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
