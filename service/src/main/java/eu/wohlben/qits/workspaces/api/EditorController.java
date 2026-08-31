package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.EditorLifecycle;
import eu.wohlben.qits.workspaces.control.EditorService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The project editor's door, and there is only one of it.
 *
 * <p><b>Find-or-create, and no status read beside it.</b> {@code POST /editor/ensure} is the whole
 * readiness protocol: a fresh editor answers 201, an existing one 200, and both carry the same body.
 * A caller polls this and nothing else, which is what lets a reader who reloads mid-start rejoin the
 * editor already coming up instead of asking for a second one. The pairing is {@code
 * TerminalController.open}'s — {@code fresh} becomes the status and never a field.
 *
 * <p><b>The scope rides as a query parameter</b>, the way {@code GET /workspaces?repositoryId=} does
 * and for the same reason: a workspace is not a sub-resource of a repository in this context, which
 * holds the id as an opaque String in another database with no join. The body is empty, because the
 * scope is everything this door needs.
 *
 * <p><b>The repository is the WRAPPER's</b>, always. A project has one editor and it rides the
 * aggregate workspace, which branches the wrapper and every submodule under it — so a repository
 * segment in a caller's own address says which page they came in through, not which editor this is.
 * A repository that is not a wrapper is a 400 naming the rule rather than a plain workspace start
 * nobody could ever poll to ready.
 *
 * <p>Who may ask is this class's {@code @RolesAllowed("qits:admin")}, the standing rule {@code
 * WorkspaceController} carries: asking for a workspace container already requires the platform admin
 * role, and an editor is a workspace container.
 */
@Path("/editor")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class EditorController {

  @Inject EditorService editors;

  /**
   * The answer, as a BARE object rather than in this context's usual {@code {thing: …}} envelope.
   *
   * <p>That is deliberate and it is the one place here that departs: the client polls this every two
   * seconds and reads four scalars off it, so an envelope would be a wrapper around a wrapper. The
   * routes that answer a {@code WorkspaceDto} keep theirs — a named payload is what makes a
   * collection and an item one shape.
   *
   * @param workspaceId the workspace's row id as a String — the identity {@code
   *     /workspaces/{id}/stop-container} and {@code /recreate-container} address, which is why a
   *     branch label would not do
   * @param containerStatus the workspace's runtime status, as this service last recorded it
   * @param editorState what the daemon last reported: {@code STARTING}, {@code RUNNING}, {@code
   *     ENDED}, or null when nothing has been reported
   * @param editorReady the readiness, and the only field to act on — the container is running and the
   *     editor inside it says it is serving
   */
  public record EditorSessionResponse(
      String workspaceId, String containerStatus, String editorState, boolean editorReady) {

    static EditorSessionResponse of(EditorService.EditorSession session) {
      EditorLifecycle state = session.editorState();
      return new EditorSessionResponse(
          session.workspaceId(),
          session.containerStatus(),
          state == null ? null : state.name(),
          session.editorReady());
    }
  }

  /**
   * Make sure this project has an editor, and say whether it answers yet.
   *
   * <p>Idempotent: the workspace row is created on the branch it claims or handed back, and the
   * container is asked for only when asking could change something — see {@link EditorService} for
   * the two reasons it does not ask, and why neither of them is a lock.
   */
  @POST
  @Path("/ensure")
  @APIResponse(responseCode = "201", description = "The editor was started by this call.")
  @APIResponse(responseCode = "200", description = "The editor was already there.")
  @APIResponse(
      responseCode = "400",
      description = "The repository is not a project's wrapper, so it has no editor.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such repository.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public Response ensure(@QueryParam("repositoryId") String repositoryId) {
    EditorService.EditorSession session = editors.ensure(repositoryId);
    return Response.status(session.fresh() ? 201 : 200)
        .entity(EditorSessionResponse.of(session))
        .build();
  }
}
