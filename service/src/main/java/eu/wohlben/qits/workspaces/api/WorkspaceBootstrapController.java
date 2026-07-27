package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.BootstrapRunService;
import eu.wohlben.qits.workspaces.control.WorkspaceBootstrapRunner;
import eu.wohlben.qits.workspaces.dto.BootstrapRunDto;
import eu.wohlben.qits.workspaces.dto.BootstrapStepDto;
import eu.wohlben.qits.workspaces.control.QitsConfig;
import eu.wohlben.qits.workspaces.control.WorkspaceConfigReader;
import eu.wohlben.qits.workspaces.control.WorkspaceResolver;
import jakarta.enterprise.inject.Instance;
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
 * The workspace surface of the bootstrap chain: the config-declared steps (from the workspace's
 * in-container {@code .qits-config.yml} — the only chain source since Part 5) in execution order,
 * each with its last recorded run in this workspace (null when it never ran), plus the on-demand
 * triggers. Runs kick off asynchronously — a chain can take as long as a cold {@code mvn install} —
 * and progress arrives over the workspace SSE channel's {@code bootstrap} hints; a run already in
 * flight is a 400.
 */
@Path("/workspaces/{id}/bootstrap-commands")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkspaceBootstrapController {

  @Inject Instance<WorkspaceConfigReader> configReader;

  @Inject BootstrapRunService bootstrapRunService;

  @Inject WorkspaceBootstrapRunner bootstrapRunner;

  @Inject WorkspaceResolver workspaceResolver;

  public static record ListWorkspaceBootstrapRequest() {
    public record Response(boolean chainRunning, List<Entry> entries) {
      public record Entry(BootstrapStepDto step, BootstrapRunDto lastRun) {}
    }
  }

  @GET
  public ListWorkspaceBootstrapRequest.Response list(
      @PathParam("id") Long id) {
    Map<String, BootstrapRunDto> lastRuns =
        bootstrapRunService.listForWorkspace(id).stream()
            .collect(Collectors.toMap(BootstrapRunDto::bootstrapCommandId, Function.identity()));
    List<QitsConfig.BootstrapDecl> chain =
        configReader.isUnsatisfied()
            ? List.of()
            : configReader
                .get()
                .readConfig(id)
                .map(view -> view.config().bootstrap())
                .orElse(List.of());
    var entries =
        chain.stream()
            .map(
                decl ->
                    new ListWorkspaceBootstrapRequest.Response.Entry(
                        new BootstrapStepDto(decl.id(), decl.name(), decl.description()),
                        // The last-run row is keyed by step name (the runner records the daemon's
                        // reported name; ids default to names until configs declare real ids).
                        lastRuns.get(decl.name())))
            .toList();
    return new ListWorkspaceBootstrapRequest.Response(
        bootstrapRunner.isChainRunning(id), entries);
  }

  public static record RunBootstrapChainRequest() {
    public record Response(boolean started) {}
  }

  @POST
  @Path("/run")
  public RunBootstrapChainRequest.Response runChain(
      @PathParam("id") Long id) {
    bootstrapRunner.runChainAsync(id);
    return new RunBootstrapChainRequest.Response(true);
  }

  public static record RunBootstrapCommandRequest() {
    public record Response(boolean started) {}
  }

  /**
   * {@code {stepId}} is the config-declared bootstrap {@code id:} (defaulting to the step name).
   */
  @POST
  @Path("/{stepId}/run")
  public RunBootstrapCommandRequest.Response runSingle(
      @PathParam("id") Long id, @PathParam("stepId") String stepId) {
    bootstrapRunner.runSingleAsync(id, stepId);
    return new RunBootstrapCommandRequest.Response(true);
  }
}
