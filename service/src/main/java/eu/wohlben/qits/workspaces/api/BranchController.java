package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.wiring.ProjectsReleaseRequests;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
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
// THE PAIR AT CLASS LEVEL, AND THE HUMAN-ONLY GUARD IN THE METHOD BODIES — because method-level
// @RolesAllowed on this class is not trustworthy in the deployed binary, measured 2026-09-03:
// releaseBranch carried {"qits:admin","qits:system"} in the shipped tree (image c7c830ad) and the
// runtime enforced the class-level admin-only anyway, while executeRelease's byte-identical
// method annotation WAS enforced. Same file, same spelling, one method honored and one not; the
// root cause is unfound and this class no longer bets on the mechanism. What is proven to hold:
// class-level enforcement (merge answered 403 to a system token all along) and code in the body.
// So the class admits both roles, the machine-and-person doors (release, execute-release) take
// exactly that, and the person-only doors (merge, cleanup) REFUSE the system role in their first
// line, where no annotation processor can lose it.
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
public class BranchController {

  /**
   * The person-only refusal, programmatic on purpose — see the class annotation's comment. A
   * machine's token carries {@code qits:system} and never {@code qits:admin}; a person (or a
   * commissioned context acting as one) carries {@code qits:admin}.
   */
  private void requireAdmin(String door) {
    if (!identity.getRoles().contains("qits:admin")) {
      throw new jakarta.ws.rs.ForbiddenException(
          door + " is a person's door: it wants qits:admin, and this token carries only machine"
              + " roles");
    }
  }

  @Inject WorkspaceService workspaceService;

  /** The request-creating half's far side; the door forwards the ask with the caller's name on it. */
  @Inject @RestClient ProjectsReleaseRequests releaseRequests;

  @Inject eu.wohlben.qits.workspaces.wiring.IdpProjectsBearer projectsBearer;

  @Inject SecurityIdentity identity;

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
    requireAdmin("/branches/merge");
    var result =
        workspaceService.mergeBranch(repoId, request.source(), request.target(), request.result());
    return new MergeBranchRequest.Response(
        result.commitHash(), result.hasConflicts(), result.output(), result.cleanedUp());
  }

  /**
   * @param branch the branch to release, which needs no workspace of its own
   * @param summary the commit's subject after the {@code release(<version>)} scope, capped exactly
   *     as the workspace-keyed door caps it
   * @param expectedSha the source head the caller means to land, or null (the field absent) to
   *     land whatever the branch holds — today's behavior byte for byte. The release-quality-gates
   *     execution pins the sha its gates evaluated; a head that moved answers 409 {@code
   *     HEAD_MOVED} and nothing lands.
   */
  public static record ReleaseBranchRequest(
      @NotBlank String branch,
      @NotBlank @Size(max = 100) String summary,
      @jakarta.validation.constraints.Pattern(regexp = "[0-9a-f]{7,64}") String expectedSha) {
    // The response is WorkspaceController.ReleaseRequest.Response, reused rather than copied: the
    // two doors answer with one record, so they cannot drift into answering differently.
  }

  /**
   * Ask for a branch to be released — <b>a release request, not a merge</b>. The door split's
   * public half: what used to land here at once is created (or converged, merge-request-shaped) as
   * a release request in qits-projects, settled by the quality gates off the commit ledger, and
   * executed against {@code /branches/execute-release} once they pass. The caller polls the
   * request; a fire-and-forget pipeline step simply exits, and the release lands when the build
   * goes green — which is the ordering the trains never had.
   *
   * <p>The sha the request arms with is the branch's head on the git host at this instant — or the
   * caller's {@code expectedSha} where given, which is how an ask can be pinned to exactly what the
   * caller reviewed. The guards a caller could act on stay at this door: an unknown repository or
   * branch is a 404, the default branch a 400, exactly as before.
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
  /** What the request-creating doors answer: the request, to poll until it settles. */
  @Schema(name = "ReleaseRequested")
  public static record ReleaseRequested(
      String requestId, String state, String branch, String commitSha, String detail) {

    static ReleaseRequested of(ProjectsReleaseRequests.CreateResponse answer) {
      ProjectsReleaseRequests.RequestView request = answer.request();
      return new ReleaseRequested(
          request.id(), request.state(), request.branch(), request.commitSha(), request.detail());
    }
  }

  @POST
  @Path("/release")
  @APIResponse(
      responseCode = "200",
      description =
          "The release request, created or converged — poll it until RELEASED, REJECTED or FAILED."
              + " Nothing has merged yet; the gates decide when it does.")
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
  // qits:system beside the class's qits:admin, the same pair the execute arm below carries and for
  // the same reason: a MACHINE asks here too. qits-platform-maintenance requests the release of the
  // bump branches it pushes, and its client deliberately holds no qits:admin — that is a person's
  // role, and the bootstrap's grant comment defends it. Admitting the system role at the door keeps
  // that doctrine intact instead of promoting a service to personhood for one POST.
  public ReleaseRequested releaseBranch(
      @QueryParam("repositoryId") String repoId,
      @QueryParam("projectId") String projectId,
      @QueryParam("repositoryName") String repositoryName,
      @Valid ReleaseBranchRequest request) {
    String repository = addressedRepository(repoId, projectId, repositoryName);
    return requestRelease(repository, request.branch(), request.summary(), request.expectedSha());
  }

  /**
   * The shared request-creating half of both public doors: resolve what is being asked (guards
   * included — they are this door's, not the gate's), then hand the ask to qits-projects with the
   * caller's own name on it.
   */
  ReleaseRequested requestRelease(
      String repository, String branch, String summary, String expectedSha) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      throw new BadRequestException("Invalid branch: " + branch);
    }
    String mainBranch =
        repositories.find(repository).map(RepositoryLookup.RepositoryView::mainBranch).orElse(null);
    if (branch.equals(mainBranch)) {
      throw new BadRequestException(
          "'"
              + branch
              + "' is the repository's default branch, which is what a release lands on — there is"
              + " nothing to release it into.");
    }
    String sha =
        expectedSha != null ? expectedSha : workspaceService.branchHeadSha(repository, branch);
    String caller =
        identity == null || identity.isAnonymous() ? "qits-workspaces" : identity.getPrincipal().getName();
    // Bearer where one exists, the forwarded pair where none can be minted; the person travels in
    // the body either way — the wiring interface's javadoc carries the reasoning.
    String authorization = projectsBearer.authorization().orElse(null);
    return ReleaseRequested.of(
        releaseRequests.create(
            repository,
            authorization,
            authorization == null ? caller : null,
            authorization == null ? "qits:system" : null,
            new ProjectsReleaseRequests.CreateBody(branch, sha, summary, caller)));
  }

  /**
   * The <b>execution arm</b> of the release-quality-gates flow: land the branch now, exactly as
   * {@code /branches/release} always has — merge, calver stamp, tag, atomic push, promotion — and
   * honouring the same {@code expectedSha} pin. It exists so the public door can become
   * request-creating without the requests having nowhere to execute: qits-projects calls this once
   * a request's gates have passed, pinned to the sha they evaluated.
   *
   * <p><b>Two roles, spelled in full</b> because a method-level list replaces the class's: {@code
   * qits:system} is the machine arm's own caller (qits-projects executing a gated request), and
   * {@code qits:admin} keeps an operator's direct hand on the lever — the escape hatch when the
   * gate itself is what is broken. Same request record, same response record, same 409 family as
   * the door it is the execution half of.
   */
  @POST
  @Path("/execute-release")

  @APIResponse(responseCode = "200", description = "Released; the version and the merge commit.")
  @APIResponse(
      responseCode = "409",
      description =
          "Nothing was released and the default branch is unchanged. `reason` says which refusal —"
              + " HEAD_MOVED when the branch outran the pinned sha.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public WorkspaceController.ReleaseRequest.Response executeRelease(
      @QueryParam("repositoryId") String repoId,
      @QueryParam("projectId") String projectId,
      @QueryParam("repositoryName") String repositoryName,
      @Valid ReleaseBranchRequest request) {
    return WorkspaceController.ReleaseRequest.Response.of(
        workspaceService.releaseBranch(
            addressedRepository(repoId, projectId, repositoryName),
            request.branch(),
            request.summary(),
            request.expectedSha()));
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
    requireAdmin("/branches/cleanup");
    workspaceService.cleanupBranch(repoId, request.branch(), request.result());
    return new CleanupBranchRequest.Response(true);
  }
}
