package eu.wohlben.qits.workspaces.wiring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** The project-scoped repository listing used when a wrapper workspace branches its whole tree. */
@Path("/projects/api/projects")
@RegisterRestClient(configKey = "qits-projects")
public interface ProjectsProjectRepositories {
  @GET
  @Path("/{projectId}/repositories")
  @Produces(MediaType.APPLICATION_JSON)
  ListResponse list(
      @PathParam("projectId") String projectId,
      @HeaderParam("Authorization") String authorization);

  /**
   * Resolves a project-scoped repository <b>name</b> to its row id — qits-projects' alias table,
   * the single authority on the public {@code (projectId, repoName)} identity. 404 for an unknown
   * project and an unknown name alike, in the same words, which is why {@link HttpRepositoryLookup}
   * reports both as "no such name".
   *
   * <p>The route requires {@code qits:system}, so the call carries this service's own machine
   * bearer like every other call on this client. It is the same read qits-githost makes to serve
   * {@code /git/<projectId>/<repoName>}; nothing about it is workspaces-specific.
   */
  @GET
  @Path("/{projectId}/repositories/by-name/{repoName}")
  @Produces(MediaType.APPLICATION_JSON)
  ByNameResponse byName(
      @PathParam("projectId") String projectId,
      @PathParam("repoName") String repoName,
      @HeaderParam("Authorization") String authorization);

  /**
   * qits-projects' {@code ProjectController.ResolveRepositoryNameRequest.Response} — the opaque
   * storage key the name addresses, and nothing else. The full view is then the ordinary by-id
   * read, so this context has one description of a repository rather than two.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ByNameResponse(String repositoryId) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ListResponse(List<Entry> entries) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Entry(ProjectsRepositories.Repository repository) {}
}
