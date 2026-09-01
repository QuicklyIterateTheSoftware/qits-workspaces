package eu.wohlben.qits.workspaces.wiring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * qits-projects' release-request door — where the public release doors send an ask now that the
 * gate is mandatory. Same configKey and same base-url reasoning as {@link ProjectsRepositories},
 * whose javadoc carries it in full.
 *
 * <p><b>The identity is forwarded, not owned.</b> The caller's name rides {@code X-Qits-User} with
 * {@code qits:system} beside it, so the request's {@code requester} is the person or pipeline that
 * asked at this door rather than this service — the audit property the whole flow keys on. An
 * intra-net hop may assert the pair (the edge strips the namespace at the boundary); it moves to a
 * machine bearer with the auth rollout like every other forwarded hop.
 */
@Path("/projects/api/repositories")
@RegisterRestClient(configKey = "qits-projects")
public interface ProjectsReleaseRequests {

  @POST
  @Path("/{repoId}/release-requests")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  CreateResponse create(
      @PathParam("repoId") String repoId,
      @HeaderParam("X-Qits-User") String user,
      @HeaderParam("X-Qits-Roles") String roles,
      CreateBody body);

  record CreateBody(String branch, String commitSha, String summary) {}

  /** qits-projects' {@code ReleaseRequestController.CreateReleaseRequest.Response}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CreateResponse(RequestView request) {}

  /** The fields the doors answer with; the rest of the DTO stays qits-projects' to change. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record RequestView(String id, String state, String branch, String commitSha, String detail) {}
}
