package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspaceProcessTracker;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.mapper.WorkspaceMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Workspaces, addressed by their own id.
 *
 * <p><strong>A workspace is not a sub-resource of a repository here.</strong> This context does not
 * own repositories — it holds a repository id as a string, with no foreign key and no join, in a
 * different database. Addressing these routes as {@code /repositories/{repoId}/workspaces/...}
 * asserted a containment the model deliberately does not have. So the entity leads: a single
 * workspace is {@code {id}} alone — {@link
 * eu.wohlben.qits.workspaces.entity.Workspace#id}, the generated key every FK'd child table already
 * uses — and the repository is <em>scope</em> on the collection, which is what it actually is.
 *
 * <p>Not the string {@code workspaceId}: that is a branch-derived label, unique only per repository
 * and reusable once a workspace resolves, which is why it needed a {@code repoId} beside it to
 * identify anything. A unique id is already unique.
 */
@Path("/workspaces")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkspaceController {

  @Inject WorkspaceService workspaceService;

  @Inject WorkspaceMapper workspaceMapper;

  /**
   * Optional, like everywhere else this context touches the technical-process framework: with no
   * tracker installed {@code /active-process} answers null, which is already its "none is live"
   * response.
   */
  @Inject Instance<WorkspaceProcessTracker> technicalProcesses;

  public static record ListWorkspacesRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(WorkspaceDto workspace) {}
    }
  }

  /** The repository is a filter on the collection, not a parent segment. */
  @GET
  public ListWorkspacesRequest.Response list(@QueryParam("repositoryId") String repositoryId) {
    var entries =
        workspaceService.listWorkspaces(repositoryId).stream()
            .map(ListWorkspacesRequest.Response.Entry::new)
            .toList();
    return new ListWorkspacesRequest.Response(entries);
  }

  /**
   * {@code repositoryId} rides in the body rather than the path or a query parameter: a create
   * carries its scope in the payload, and the repository is not a filter on a POST.
   *
   * <p>{@code id} is the requested <em>label</em> (the path/container-name segment), not an
   * identifier — the created workspace's identifier comes back in the response.
   *
   * <p>{@code adoptExisting} lets the workspace take over a branch that already exists (the
   * branch-list "Create workspace" button on a workspaceless branch) instead of forking a new one;
   * false for the normal "branch off" flow, which creates a fresh branch and 409s on a name
   * collision. Either way the branch must have no active workspace already — it is the resource
   * being claimed.
   */
  public static record CreateWorkspaceRequest(
      @NotBlank String repositoryId,
      @NotBlank String id,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting) {
    /** Backward-compatible "branch off" form: create a new branch, never adopt an existing one. */
    public CreateWorkspaceRequest(
        String repositoryId, String id, String parent, String branch, String preamble) {
      this(repositoryId, id, parent, branch, preamble, false);
    }

    public record Response(WorkspaceDto workspace) {}
  }

  @POST
  public CreateWorkspaceRequest.Response create(@Valid CreateWorkspaceRequest request) {
    var wt =
        workspaceService.createWorkspace(
            request.repositoryId(),
            request.id(),
            request.parent(),
            request.branch(),
            request.preamble(),
            request.adoptExisting());
    return new CreateWorkspaceRequest.Response(workspaceMapper.toDto(wt));
  }

  public static record EnsureContainerRequest() {
    /**
     * The workspace's state at submit time plus the technical process streaming the start — watch
     * it live (replay + live + terminal {@code done}) at {@code
     * /workspaces/api/technical-processes/{technicalProcessId}/events}.
     */
    public record Response(WorkspaceDto workspace, String technicalProcessId) {}
  }

  /**
   * Start (or recreate) the workspace's container on demand — the container is a recreatable cache
   * of the durable branch. Idempotent (a running container is left as-is). The provision runs
   * asynchronously: this returns a technical-process id immediately and the work — docker run,
   * clone, submodule wiring, service auto-start — streams over the process's SSE endpoint, where
   * failures surface too (alongside the workspace's {@code runtimeError}). 404 only when the
   * workspace itself is unknown.
   */
  @POST
  @Path("/{id}/ensure-container")
  public EnsureContainerRequest.Response ensureContainer(
      @PathParam("id") Long id) {
    String technicalProcessId = workspaceService.beginEnsureContainer(id);
    return new EnsureContainerRequest.Response(
        workspaceService.getWorkspace(id), technicalProcessId);
  }

  public static record ActiveProcessRequest() {
    /** The workspace's currently-running technical process, or null when none is live. */
    public record Response(String technicalProcessId) {}
  }

  /**
   * Discovery for the workspace detail route's transient process tab: the id of the technical
   * process currently running against this workspace (null once it completes). Announced over the
   * workspace's payload-free SSE channel as a {@code process} hint, so clients re-fetch this
   * instead of polling.
   */
  @GET
  @Path("/{id}/active-process")
  public ActiveProcessRequest.Response activeProcess(
      @PathParam("id") Long id) {
    return new ActiveProcessRequest.Response(
        technicalProcesses.isResolvable()
            ? technicalProcesses.get().activeFor(id).orElse(null)
            : null);
  }

  /**
   * Gracefully stop the workspace's container: its branch is pushed to origin first (so committed
   * work survives), then the container is removed and the workspace is left ACTIVE/STOPPED for lazy
   * recreate. Returns the refreshed workspace.
   */
  @POST
  @Path("/{id}/stop-container")
  public WorkspaceDto stopContainer(
      @PathParam("id") Long id) {
    workspaceService.stopContainer(id);
    return workspaceService.getWorkspace(id);
  }

  /**
   * Delete the workspace's container outright ({@code docker rm}) while keeping its branch and the
   * workspace row — the destructive counterpart to the graceful {@link #stopContainer}, which
   * pauses in place. Reclaims the (stopped) container and any uncommitted changes in it; the next
   * Start re-clones a fresh container from the durable branch. Does NOT delete the branch — that is
   * {@code discard} (Abandon). Returns the refreshed workspace; 404 only when the workspace is
   * unknown.
   */
  @POST
  @Path("/{id}/delete-container")
  public WorkspaceDto deleteContainer(
      @PathParam("id") Long id) {
    workspaceService.deleteContainer(id);
    return workspaceService.getWorkspace(id);
  }

  public static record RecreateContainerRequest() {
    /**
     * The workspace's state at submit time plus the technical process streaming the recreate —
     * watch it live at {@code /workspaces/api/technical-processes/{technicalProcessId}/events}.
     */
    public record Response(WorkspaceDto workspace, String technicalProcessId) {}
  }

  /**
   * Recreate the workspace's container on the current image — the way to move it onto a newer
   * workspace-daemon build once its version badge shows it is outdated
   * (docs/epics/qits-workspace-registry/). The old container is torn down and a fresh one
   * provisioned from the durable branch; the work streams over the returned technical process like
   * {@code ensure-container}. Refuses with 400 unless the working tree is provably clean — a dirty
   * or unknown (no live daemon) state is rejected, since recreating would risk losing work. 404
   * only when the workspace itself is unknown.
   */
  @POST
  @Path("/{id}/recreate-container")
  public RecreateContainerRequest.Response recreateContainer(
      @PathParam("id") Long id) {
    String technicalProcessId = workspaceService.beginRecreateContainer(id);
    return new RecreateContainerRequest.Response(
        workspaceService.getWorkspace(id), technicalProcessId);
  }

  public static record MergeWorkspaceRequest(String target) {
    public record Response(String commitHash, boolean hasConflicts, String output) {}
  }

  @POST
  @Path("/{id}/merge")
  public MergeWorkspaceRequest.Response merge(
      @PathParam("id") Long id,
      @Valid MergeWorkspaceRequest request) {
    var result = workspaceService.mergeWorkspace(id, request.target());
    return new MergeWorkspaceRequest.Response(
        result.commitHash(), result.hasConflicts(), result.output());
  }

  /**
   * @param summary the release commit's subject after the version scope. Capped at 100 because a
   *     conventional subject budget is 72 and {@code release(2026.731.193059): } already costs ~24
   *     of it — the cap lets the summary be the whole of what is left and no more.
   */
  public static record IntegrateRequest(@NotBlank @Size(max = 100) String summary) {
    /** @see eu.wohlben.qits.workspaces.control.WorkspaceService.IntegrateResult */
    public record Response(String version, String commitSha, String branch) {}
  }

  /**
   * Integrate this workspace: merge its branch into the repository's default branch, stamped with a
   * fresh {@code YYYY.MMDD.HHMMSS} version, as <b>one</b> commit — {@code release(<version>):
   * <summary>} — which is then pushed through the ordinary git host, where the ordinary
   * post-receive fires and the ordinary pipeline builds it.
   *
   * <p><b>Its own verb, not a widening of {@code merge}.</b> Different response, different failure
   * modes and different semantics: merge moves a ref, integrate performs a release. Workspace-keyed,
   * so it lives here rather than on {@code /branches} — the rule {@link BranchController}'s own
   * javadoc supplies. And the target is not a parameter: it is always the default branch, which is
   * the feature.
   *
   * <p>The failures are 409s and they are told apart structurally. Each body carries the usual
   * {@code message} plus an additive {@code reason} — {@code CONFLICT}, {@code MERGE_CONFLICT},
   * {@code NOT_FAST_FORWARD}, {@code ALREADY_INTEGRATED}, {@code PUSH_REJECTED} — and, for the two
   * conflict modes, a {@code conflicts} array of the conflicted paths. A conflict or a lost race
   * released <b>nothing</b> and left the default branch byte-identical; "already integrated" is what
   * a lost 200 looks like on retry and means the work is in.
   */
  @POST
  @Path("/{id}/integrate")
  public IntegrateRequest.Response integrate(
      @PathParam("id") Long id, @Valid IntegrateRequest request) {
    var result = workspaceService.integrateWorkspace(id, request.summary());
    return new IntegrateRequest.Response(
        result.version(), result.commitSha(), result.branch());
  }

  // POST /{workspaceId}/fast-forward and /{workspaceId}/update-from-parent used to live here. Both
  // were `docker exec git fetch/merge --ff-only/merge --no-edit/push` against the checkout inside
  // the container — the host reaching past the daemon into the workspace it owns. They moved to the
  // workspace-daemon's own HTTP API, where the checkout is a local java.nio path and the git runs
  // in-process, alongside /files, /detection and /component-map. The host keeps only the state it
  // owns: the Workspace row, the parent, and the UPDATED_FROM_PARENT event.

  public static record DiscardWorkspaceRequest(String result) {
    public record Response(boolean success) {}
  }

  @POST
  @Path("/{id}/discard")
  public DiscardWorkspaceRequest.Response discard(
      @PathParam("id") Long id,
      @Valid DiscardWorkspaceRequest request) {
    workspaceService.discardWorkspace(id, request == null ? null : request.result());
    return new DiscardWorkspaceRequest.Response(true);
  }

}
