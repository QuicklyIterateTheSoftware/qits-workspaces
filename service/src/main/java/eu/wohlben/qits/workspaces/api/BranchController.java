package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Branch-level release, integration and cleanup: the operations the branch list offers on a row that
 * is not (or no longer) a workspace of its own.
 *
 * <p>These sit under {@code /branches} rather than under {@code /workspaces/{id}} because they are
 * keyed by <em>branch name</em>: the source of an integration needs no workspace of its own (a plain
 * branch is merged from its origin ref), and a cleanup target may have outlived the workspace that
 * created it. That is what separates them from {@link WorkspaceController}'s {@code merge}/{@code
 * discard}, which address a workspace by id — and it is why {@code /branches/release} is here while
 * {@code /workspaces/{id}/release} is there: one flow, two keys, and the key decides the home.
 *
 * <p>The repository is <em>scope</em>, in the query string, for the same reason it is on {@link
 * WorkspaceController}'s collection: this context does not own repositories — it holds a repository
 * id as a string, with no foreign key and no join, in a different database — so a {@code
 * /repositories/{repoId}/…} segment would assert a containment the model deliberately does not
 * have. Unlike a workspace, a branch has no id of its own, so the pair (repository, branch name) is
 * what names one: the repository narrows, the body says which branch.
 *
 * <p>The monorepo served both from its {@code RepositoryController}, as thin forwards into {@link
 * WorkspaceService}. On extraction the repository half went to {@code qits-projects}, which cut them
 * rather than keep forwarding into another context (see the seam note there) — so they live here,
 * with the service they were always calling.
 */
@Path("/branches")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class BranchController {

  @Inject WorkspaceService workspaceService;

  /**
   * The registry that turns the public identity {@code (projectId, repositoryName)} into the row id
   * every verb below is keyed by. Injected here rather than pushed into {@link WorkspaceService}
   * because resolution is <em>addressing</em>, not release: the service keeps one entry point taking
   * an id, and the door decides how the caller was allowed to name it.
   */
  @Inject RepositoryLookup repositories;

  public static record MergeBranchRequest(@NotBlank String source, String target, String result) {
    /**
     * @param cleanedUp whether the integrated source workspace+branch was removed afterwards (it
     *     was fully merged with no dependents)
     */
    public record Response(
        String commitHash, boolean hasConflicts, String output, boolean cleanedUp) {}
  }

  /**
   * Merge {@code source} into {@code target}, defaulting to the repo's main branch.
   *
   * <p>409 {@code RELEASE_REQUIRED} when the target resolves to that default branch: it is written
   * by {@code /workspaces/{id}/release} alone, and {@code /workspaces/{id}/integrate} is the door
   * for a plain merge into a parent.
   */
  @POST
  @Path("/merge")
  @APIResponse(responseCode = "200", description = "Merged.")
  @APIResponse(
      responseCode = "400",
      description = "A blank, dash-leading or self-referential branch name.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such repository.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description =
          "The target is the repository's default branch. `reason` is RELEASE_REQUIRED and the"
              + " message names the two endpoints that do write it.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public MergeBranchRequest.Response mergeBranch(
      @QueryParam("repositoryId") String repoId, @Valid MergeBranchRequest request) {
    var result =
        workspaceService.mergeBranch(repoId, request.source(), request.target(), request.result());
    return new MergeBranchRequest.Response(
        result.commitHash(), result.hasConflicts(), result.output(), result.cleanedUp());
  }

  /**
   * @param branch the branch to release, which needs no workspace of its own
   * @param summary the commit's subject after the {@code release(<version>)} scope, capped exactly
   *     as the workspace-keyed door caps it
   */
  public static record ReleaseBranchRequest(
      @NotBlank String branch, @NotBlank @Size(max = 100) String summary) {
    // The response is WorkspaceController.ReleaseRequest.Response, reused rather than copied: the
    // two doors answer with one record, so they cannot drift into answering differently.
  }

  /**
   * Release a branch by name: merge it into the repository's default branch, stamped with a fresh
   * {@code YYYY.MMDD.HHMMSS} version, as <b>one</b> commit — {@code release(<version>): <summary>} —
   * pushed with {@code -o qits.release}, with a {@code SCMRelease} published.
   *
   * <p><b>The branch-keyed sibling of {@code /workspaces/{id}/release}</b>, and a thin resolver over
   * it rather than a second implementation: the flow is keyed by (repository, source branch)
   * internally, so a branch name is all it ever needed. Same 409 family, same summary cap, same
   * response record.
   *
   * <p>It exists for the caller that has a branch and no workspace: a maintenance branch is
   * force-pushed by a build container, and a workspace is a container lifecycle with a branch claim
   * and a resolution state machine — all wrong-shaped for a ref a pipeline overwrites at will.
   *
   * <p>A branch an ACTIVE workspace <em>does</em> claim is that workspace's release: the row
   * resolves to {@code INTEGRATED} exactly as the workspace-keyed door leaves it, because this door
   * must not strand a workspace on a branch that just merged. Either way the source branch is
   * deleted afterwards.
   *
   * <p><b>Two ways to name the repository, and exactly one per call.</b> {@code repositoryId} is
   * the internal row id — opaque, minted per platform instance, and the only spelling this door had.
   * {@code projectId} + {@code repositoryName} is the <em>public</em> identity: the pair a clone
   * url, a pipeline and a person all spell, resolved through {@link RepositoryLookup#findByName}.
   * Callers move to the name form because an id is not addressable outside the registry that minted
   * it; the id form stays for whatever already holds one. Mixing them, or sending neither, is a 400
   * naming the rule rather than a silent precedence order — a caller that sent both meant one of
   * them, and guessing which would be a release landing somewhere it was not asked to.
   */
  @POST
  @Path("/release")
  @APIResponse(responseCode = "200", description = "Released; the version and the merge commit.")
  @APIResponse(
      responseCode = "400",
      description =
          "A blank, dash-leading or oversized field — or the default branch itself, which a release"
              + " lands on and cannot be released into — or the repository addressed both ways at"
              + " once, or neither.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description =
          "No such repository, no repository by that name in that project, or the origin has no such"
              + " branch.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description =
          "Nothing was released and the default branch is unchanged. `reason` says which refusal.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public WorkspaceController.ReleaseRequest.Response releaseBranch(
      @QueryParam("repositoryId") String repoId,
      @QueryParam("projectId") String projectId,
      @QueryParam("repositoryName") String repositoryName,
      @Valid ReleaseBranchRequest request) {
    return WorkspaceController.ReleaseRequest.Response.of(
        workspaceService.releaseBranch(
            addressedRepository(repoId, projectId, repositoryName),
            request.branch(),
            request.summary()));
  }

  /** What both addressing forms have to say, spelled once so the two 400s cannot disagree. */
  private static final String ADDRESSING_RULE =
      "Address the repository exactly one way: repositoryId=<id>, or"
          + " projectId=<project>&repositoryName=<name>.";

  /**
   * The row id the caller addressed, whichever way they spelled it.
   *
   * <p>Three answers and no fourth: the id as given, the id the alias table holds for {@code
   * (projectId, repositoryName)}, or a refusal. A name that resolves to nothing is a <b>404</b>
   * naming the pair — the caller asked about a repository that is not there. A registry that could
   * not be asked throws out of {@link RepositoryLookup#findByName} and surfaces as a 5xx, which is
   * the distinction that port exists to keep: an outage reported as a 404 would tell a pipeline its
   * repository had been deleted.
   */
  private String addressedRepository(String repoId, String projectId, String repositoryName) {
    boolean addressedById = present(repoId);
    boolean addressedByName = present(projectId) || present(repositoryName);
    if (addressedById && addressedByName) {
      throw new BadRequestException(ADDRESSING_RULE + " Both were given.");
    }
    if (addressedById) {
      return repoId.trim();
    }
    if (!(present(projectId) && present(repositoryName))) {
      throw new BadRequestException(
          ADDRESSING_RULE + " The name form needs both halves; neither addressing form is complete.");
    }
    String project = projectId.trim();
    String name = repositoryName.trim();
    return repositories
        .findByName(project, name)
        .map(RepositoryLookup.RepositoryView::id)
        .orElseThrow(
            () ->
                new NotFoundException(
                    "No repository named '" + name + "' in project " + project));
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
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
  @APIResponse(responseCode = "200", description = "Removed.")
  @APIResponse(
      responseCode = "400",
      description =
          "The branch is not eligible: uncommitted changes, unmerged commits, or dependents.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such repository.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public CleanupBranchRequest.Response cleanupBranch(
      @QueryParam("repositoryId") String repoId, @Valid CleanupBranchRequest request) {
    workspaceService.cleanupBranch(repoId, request.branch(), request.result());
    return new CleanupBranchRequest.Response(true);
  }
}
