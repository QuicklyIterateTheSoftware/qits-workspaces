package eu.wohlben.qits.domain.repository.api;

import eu.wohlben.qits.domain.repository.control.CommitService;
import eu.wohlben.qits.domain.repository.control.ComponentMapService;
import eu.wohlben.qits.domain.repository.control.ResolveConflictService;
import eu.wohlben.qits.domain.repository.control.WorkspaceFilesService;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import eu.wohlben.qits.domain.repository.dto.CommitLogDto;
import eu.wohlben.qits.domain.repository.dto.ComponentMapDto;
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

  @Inject WorkspaceMapper workspaceMapper;

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

  public static record CreateWorkspaceRequest(
      @NotBlank String id, String parent, String branch, String preamble) {
    public record Response(WorkspaceDto workspace) {}
  }

  @POST
  public CreateWorkspaceRequest.Response create(
      @PathParam("repoId") String repoId, @Valid CreateWorkspaceRequest request) {
    var wt =
        workspaceService.createWorkspace(
            repoId, request.id(), request.parent(), request.branch(), request.preamble());
    return new CreateWorkspaceRequest.Response(workspaceMapper.toDto(wt));
  }

  /**
   * Start (or recreate) the workspace's container on demand — the container is a recreatable cache
   * of the durable branch. Idempotent (a running container is left as-is). 404 if the branch itself
   * is gone (the workspace is then abandoned); the error surfaces to the client rather than a
   * silent no-op. Returns the refreshed workspace with its live runtime status.
   */
  @POST
  @Path("/{workspaceId}/ensure-container")
  public WorkspaceDto ensureContainer(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    workspaceService.ensureContainer(repoId, workspaceId);
    return workspaceService.getWorkspace(repoId, workspaceId);
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
    public record Response(List<String> paths, List<LazyDirDto> lazyDirs) {}
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
    return new ListWorkspaceFilesRequest.Response(listing.paths(), lazyDirs);
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
}
