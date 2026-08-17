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

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ListResponse(List<Entry> entries) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Entry(ProjectsRepositories.Repository repository) {}
}
