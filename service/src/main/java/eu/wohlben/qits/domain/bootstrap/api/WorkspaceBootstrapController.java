package eu.wohlben.qits.domain.bootstrap.api;

import eu.wohlben.qits.domain.bootstrap.control.BootstrapCommandService;
import eu.wohlben.qits.domain.bootstrap.control.BootstrapRunService;
import eu.wohlben.qits.domain.bootstrap.control.WorkspaceBootstrapRunner;
import eu.wohlben.qits.domain.bootstrap.dto.BootstrapCommandDto;
import eu.wohlben.qits.domain.bootstrap.dto.BootstrapRunDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The workspace surface of the bootstrap chain: the configured commands in execution order, each
 * with its last recorded run in this workspace (null when it never ran), plus the on-demand
 * triggers. Runs kick off asynchronously — a chain can take as long as a cold {@code mvn install} —
 * and progress arrives over the workspace SSE channel's {@code bootstrap} hints; a run already in
 * flight is a 400.
 */
@Path("/repositories/{repoId}/workspaces/{workspaceId}/bootstrap-commands")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkspaceBootstrapController {

  @Inject BootstrapCommandService bootstrapCommandService;

  @Inject BootstrapRunService bootstrapRunService;

  @Inject WorkspaceBootstrapRunner bootstrapRunner;

  public static record ListWorkspaceBootstrapRequest() {
    public record Response(boolean chainRunning, List<Entry> entries) {
      public record Entry(BootstrapCommandDto command, BootstrapRunDto lastRun) {}
    }
  }

  @GET
  public ListWorkspaceBootstrapRequest.Response list(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    Map<String, BootstrapRunDto> lastRuns =
        bootstrapRunService.listForWorkspace(repoId, workspaceId).stream()
            .collect(Collectors.toMap(BootstrapRunDto::bootstrapCommandId, Function.identity()));
    var entries =
        bootstrapCommandService.resolveAll(repoId).stream()
            .map(
                command ->
                    new ListWorkspaceBootstrapRequest.Response.Entry(
                        command, lastRuns.get(command.id())))
            .toList();
    return new ListWorkspaceBootstrapRequest.Response(
        bootstrapRunner.isChainRunning(repoId, workspaceId), entries);
  }

  public static record RunBootstrapChainRequest() {
    public record Response(boolean started) {}
  }

  @POST
  @Path("/run")
  public RunBootstrapChainRequest.Response runChain(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    bootstrapRunner.runChainAsync(repoId, workspaceId);
    return new RunBootstrapChainRequest.Response(true);
  }

  public static record RunBootstrapCommandRequest() {
    public record Response(boolean started) {}
  }

  @POST
  @Path("/{commandId}/run")
  public RunBootstrapCommandRequest.Response runSingle(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      @PathParam("commandId") String commandId) {
    bootstrapRunner.runSingleAsync(repoId, workspaceId, commandId);
    return new RunBootstrapCommandRequest.Response(true);
  }
}
