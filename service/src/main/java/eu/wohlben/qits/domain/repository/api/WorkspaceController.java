package eu.wohlben.qits.domain.repository.api;

import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import eu.wohlben.qits.domain.repository.control.CommitService;
import eu.wohlben.qits.domain.repository.control.ComponentMapService;
import eu.wohlben.qits.domain.repository.control.DetectionService;
import eu.wohlben.qits.domain.repository.control.ResolveConflictService;
import eu.wohlben.qits.domain.repository.control.WorkspaceFilesService;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import eu.wohlben.qits.domain.repository.dto.CommitLogDto;
import eu.wohlben.qits.domain.repository.dto.ComponentMapDto;
import eu.wohlben.qits.domain.repository.dto.DetectionDto;
import eu.wohlben.qits.domain.repository.dto.LazyDirDto;
import eu.wohlben.qits.domain.repository.dto.WorkspaceDto;
import eu.wohlben.qits.domain.repository.dto.WorkspaceFileContentDto;
import eu.wohlben.qits.domain.repository.mapper.WorkspaceMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Path("/repositories/{repoId}/workspaces")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkspaceController {

  @Inject WorkspaceService workspaceService;

  @Inject CommitService commitService;

  @Inject ResolveConflictService resolveConflictService;

  @Inject WorkspaceFilesService workspaceFilesService;

  @Inject ComponentMapService componentMapService;

  @Inject DetectionService detectionService;

  @Inject WorkspaceMapper workspaceMapper;

  @Inject TechnicalProcessRegistry technicalProcesses;

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
   * clone, submodule wiring, daemon auto-start — streams over the process's SSE endpoint, where
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
        technicalProcesses.activeFor(repoId, workspaceId).orElse(null));
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

  @GET
  @Path("/{workspaceId}/incoming-commits")
  public CommitLogDto incomingCommits(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    return commitService.listIncomingCommits(repoId, workspaceId);
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

  public static record ConflictingFilesRequest() {
    public record Response(List<String> files) {}
  }

  @GET
  @Path("/{workspaceId}/conflicts")
  public ConflictingFilesRequest.Response conflicts(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    return new ConflictingFilesRequest.Response(
        resolveConflictService.listConflictingFiles(repoId, workspaceId));
  }

  public static record ResolveConflictRequest() {
    /** The resolution workspace to watch Claude work in, and the launched command that runs it. */
    public record Response(String workspaceId, String branch, String commandId) {}
  }

  @POST
  @Path("/{workspaceId}/resolve-conflict")
  public ResolveConflictRequest.Response resolveConflict(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    var result = resolveConflictService.resolveConflict(repoId, workspaceId);
    return new ResolveConflictRequest.Response(
        result.workspaceId(), result.branch(), result.commandId());
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

  public static record ListWorkspaceFilesRequest() {
    public record Response(List<String> paths, List<LazyDirDto> lazyDirs, String generation) {}
  }

  @GET
  @Path("/{workspaceId}/files")
  public ListWorkspaceFilesRequest.Response listFiles(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      @QueryParam("path") String path) {
    WorkspaceFilesService.Listing listing =
        workspaceFilesService.listFiles(repoId, workspaceId, path);
    List<LazyDirDto> lazyDirs =
        listing.lazyDirs().stream()
            .map(
                dir ->
                    new LazyDirDto(
                        dir.path(), dir.childCount(), lazyDirHref(repoId, workspaceId, dir.path())))
            .toList();
    return new ListWorkspaceFilesRequest.Response(listing.paths(), lazyDirs, listing.generation());
  }

  /**
   * The self-referential {@code /files?path=…} link the client follows to open a lazy directory.
   */
  private static String lazyDirHref(String repoId, String workspaceId, String dirPath) {
    return "/api/repositories/"
        + repoId
        + "/workspaces/"
        + workspaceId
        + "/files?path="
        + URLEncoder.encode(dirPath, StandardCharsets.UTF_8);
  }

  @GET
  @Path("/{workspaceId}/files/content")
  public WorkspaceFileContentDto fileContent(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      @QueryParam("path") String path) {
    return workspaceFilesService.readFile(repoId, workspaceId, path);
  }

  /**
   * The workspace's component map — every {@code @Component} in the working tree with its selector
   * and source files — so a web-view pick can be attributed to the code that renders it. Scanned
   * lazily and cached against the working tree's state; a tree without components yields an empty
   * map, never an error.
   */
  @GET
  @Path("/{workspaceId}/component-map")
  public ComponentMapDto componentMap(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    return componentMapService.componentMap(repoId, workspaceId);
  }

  /**
   * The workspace's framework/project/test-link detection metadata, computed once over the working
   * tree: the detected projects (pom-refined labels), every framework's resolved membership path
   * set (the client's filter input), and the source→test link graph with runner kinds. Consumed by
   * the file browser instead of re-deriving detection from path strings; {@code /files} stays a
   * pure filesystem transport. Cached against the working tree's state; a tree with no recognised
   * framework yields empty lists, never an error.
   */
  @GET
  @Path("/{workspaceId}/detection")
  public DetectionDto detection(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    return detectionService.detect(repoId, workspaceId);
  }
}
