package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspaceService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Branch-level integration and cleanup: the two operations the branch list offers on a row that is
 * not (or no longer) a workspace of its own.
 *
 * <p>These sit under {@code /repositories/{repoId}/branches} rather than under {@code
 * /workspaces/{workspaceId}} because they are keyed by <em>branch name</em>: the source of an
 * integration needs no workspace of its own (a plain branch is merged from its origin ref), and a
 * cleanup target may have outlived the workspace that created it. That is what separates them from
 * {@link WorkspaceController}'s {@code merge}/{@code discard}, which address a workspace by id.
 *
 * <p>The monorepo served both from its {@code RepositoryController}, as thin forwards into {@link
 * WorkspaceService}. On extraction the repository half went to {@code qits-projects}, which cut them
 * rather than keep forwarding into another context (see the seam note there) — so they live here,
 * with the service they were always calling. The paths are unchanged from the monolith, because the
 * branch list calls them as-is.
 */
@Path("/repositories/{repoId}/branches")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BranchController {

  @Inject WorkspaceService workspaceService;

  public static record MergeBranchRequest(@NotBlank String source, String target, String result) {
    /**
     * @param cleanedUp whether the integrated source workspace+branch was removed afterwards (it
     *     was fully merged with no dependents)
     */
    public record Response(
        String commitHash, boolean hasConflicts, String output, boolean cleanedUp) {}
  }

  /** Integrate {@code source} into {@code target}, defaulting to the repo's main branch. */
  @POST
  @Path("/merge")
  public MergeBranchRequest.Response mergeBranch(
      @PathParam("repoId") String repoId, @Valid MergeBranchRequest request) {
    var result =
        workspaceService.mergeBranch(repoId, request.source(), request.target(), request.result());
    return new MergeBranchRequest.Response(
        result.commitHash(), result.hasConflicts(), result.output(), result.cleanedUp());
  }

  public static record CleanupBranchRequest(@NotBlank String branch, String result) {
    public record Response(boolean success) {}
  }

  /**
   * Remove a branch (and its workspace, if any) when that loses no work. The UI asks for no
   * confirmation, so eligibility is re-checked in the service and an ineligible branch yields a 400.
   */
  @POST
  @Path("/cleanup")
  public CleanupBranchRequest.Response cleanupBranch(
      @PathParam("repoId") String repoId, @Valid CleanupBranchRequest request) {
    workspaceService.cleanupBranch(repoId, request.branch(), request.result());
    return new CleanupBranchRequest.Response(true);
  }
}
