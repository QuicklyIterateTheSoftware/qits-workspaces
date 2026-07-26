package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspaceProcessTracker;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.mapper.WorkspaceMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/repositories/{repoId}/workspaces")
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

  @GET
  public ListWorkspacesRequest.Response list(@PathParam("repoId") String repoId) {
    var entries =
        workspaceService.listWorkspaces(repoId).stream()
            .map(ListWorkspacesRequest.Response.Entry::new)
            .toList();
    return new ListWorkspacesRequest.Response(entries);
  }

  /**
   * {@code adoptExisting} lets the workspace take over a branch that already exists (the
   * branch-list "Create workspace" button on a workspaceless branch) instead of forking a new one;
   * false for the normal "branch off" flow, which creates a fresh branch and errors on a name
   * collision.
   */
  public static record CreateWorkspaceRequest(
      @NotBlank String id, String parent, String branch, String preamble, boolean adoptExisting) {
    /** Backward-compatible "branch off" form: create a new branch, never adopt an existing one. */
    public CreateWorkspaceRequest(String id, String parent, String branch, String preamble) {
      this(id, parent, branch, preamble, false);
    }

    public record Response(WorkspaceDto workspace) {}
  }

  @POST
  public CreateWorkspaceRequest.Response create(
      @PathParam("repoId") String repoId, @Valid CreateWorkspaceRequest request) {
    var wt =
        workspaceService.createWorkspace(
            repoId,
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
     * /api/technical-processes/{technicalProcessId}/events}.
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
  @Path("/{workspaceId}/ensure-container")
  public EnsureContainerRequest.Response ensureContainer(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    String technicalProcessId = workspaceService.beginEnsureContainer(repoId, workspaceId);
    return new EnsureContainerRequest.Response(
        workspaceService.getWorkspace(repoId, workspaceId), technicalProcessId);
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
  @Path("/{workspaceId}/active-process")
  public ActiveProcessRequest.Response activeProcess(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    return new ActiveProcessRequest.Response(
        technicalProcesses.isResolvable()
            ? technicalProcesses.get().activeFor(repoId, workspaceId).orElse(null)
            : null);
  }

  /**
   * Gracefully stop the workspace's container: its branch is pushed to origin first (so committed
   * work survives), then the container is removed and the workspace is left ACTIVE/STOPPED for lazy
   * recreate. Returns the refreshed workspace.
   */
  @POST
  @Path("/{workspaceId}/stop-container")
  public WorkspaceDto stopContainer(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    workspaceService.stopContainer(repoId, workspaceId);
    return workspaceService.getWorkspace(repoId, workspaceId);
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
  @Path("/{workspaceId}/delete-container")
  public WorkspaceDto deleteContainer(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    workspaceService.deleteContainer(repoId, workspaceId);
    return workspaceService.getWorkspace(repoId, workspaceId);
  }

  public static record RecreateContainerRequest() {
    /**
     * The workspace's state at submit time plus the technical process streaming the recreate —
     * watch it live at {@code /api/technical-processes/{technicalProcessId}/events}.
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
  @Path("/{workspaceId}/recreate-container")
  public RecreateContainerRequest.Response recreateContainer(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    String technicalProcessId = workspaceService.beginRecreateContainer(repoId, workspaceId);
    return new RecreateContainerRequest.Response(
        workspaceService.getWorkspace(repoId, workspaceId), technicalProcessId);
  }

  public static record MergeWorkspaceRequest(String target) {
    public record Response(String commitHash, boolean hasConflicts, String output) {}
  }

  @POST
  @Path("/{workspaceId}/merge")
  public MergeWorkspaceRequest.Response merge(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      @Valid MergeWorkspaceRequest request) {
    var result = workspaceService.mergeWorkspace(repoId, workspaceId, request.target());
    return new MergeWorkspaceRequest.Response(
        result.commitHash(), result.hasConflicts(), result.output());
  }

  public static record FastForwardWorkspaceRequest() {
    public record Response(String output) {}
  }

  @POST
  @Path("/{workspaceId}/fast-forward")
  public FastForwardWorkspaceRequest.Response fastForward(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    var output = workspaceService.fastForwardWorkspace(repoId, workspaceId);
    return new FastForwardWorkspaceRequest.Response(output);
  }


  public static record UpdateFromParentRequest() {
    public record Response(String output) {}
  }

  @POST
  @Path("/{workspaceId}/update-from-parent")
  public UpdateFromParentRequest.Response updateFromParent(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    var output = workspaceService.updateWorkspaceFromParent(repoId, workspaceId);
    return new UpdateFromParentRequest.Response(output);
  }


  public static record DiscardWorkspaceRequest(String result) {
    public record Response(boolean success) {}
  }

  @POST
  @Path("/{workspaceId}/discard")
  public DiscardWorkspaceRequest.Response discard(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      @Valid DiscardWorkspaceRequest request) {
    workspaceService.discardWorkspace(
        repoId, workspaceId, request == null ? null : request.result());
    return new DiscardWorkspaceRequest.Response(true);
  }

}
