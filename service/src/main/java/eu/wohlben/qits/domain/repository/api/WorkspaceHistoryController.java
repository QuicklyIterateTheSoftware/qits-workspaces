package eu.wohlben.qits.domain.repository.api;

import eu.wohlben.qits.domain.repository.control.WorkspaceHistoryService;
import eu.wohlben.qits.domain.repository.dto.WorkspaceHistoryDetailDto;
import eu.wohlben.qits.domain.repository.dto.WorkspaceHistoryDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * The workspace history for a repository: every workspace (active + resolved) as a browsable record
 * of the work that flowed through the repo. Keyed by the surrogate id, since workspace ids are
 * reusable once resolved.
 */
@Path("/repositories/{repoId}/history")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkspaceHistoryController {

  @Inject WorkspaceHistoryService workspaceHistoryService;

  public static record ListHistoryRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(WorkspaceHistoryDto workspace) {}
    }
  }

  @GET
  public ListHistoryRequest.Response list(@PathParam("repoId") String repoId) {
    var entries =
        workspaceHistoryService.list(repoId).stream()
            .map(ListHistoryRequest.Response.Entry::new)
            .toList();
    return new ListHistoryRequest.Response(entries);
  }

  public static record GetHistoryRequest() {
    public record Response(WorkspaceHistoryDetailDto workspace) {}
  }

  @GET
  @Path("/{id}")
  public GetHistoryRequest.Response get(
      @PathParam("repoId") String repoId, @PathParam("id") Long id) {
    return new GetHistoryRequest.Response(workspaceHistoryService.get(repoId, id));
  }

  public static record UpdateHistoryRequest(String preamble, String result) {
    public record Response(WorkspaceHistoryDetailDto workspace) {}
  }

  @PATCH
  @Path("/{id}")
  public UpdateHistoryRequest.Response update(
      @PathParam("repoId") String repoId, @PathParam("id") Long id, UpdateHistoryRequest request) {
    return new UpdateHistoryRequest.Response(
        workspaceHistoryService.updateNarrative(repoId, id, request.preamble(), request.result()));
  }
}
