package eu.wohlben.qits.domain.service.api;

import eu.wohlben.qits.domain.service.control.ServiceSupervisor;
import eu.wohlben.qits.domain.service.dto.ServiceInstanceDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * The runtime surface of services in one workspace: the config-declared services with their
 * supervised status (all of them, running or not — the everything-visible convention) and
 * start/stop. {@code {daemonId}} path params carry the config-declared service {@code id:}
 * (defaulting to its name). The event feed moved to the durable {@code /daemon-events} endpoint.
 */
@Path("/repositories/{repoId}/workspaces/{workspaceId}/services")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkspaceServiceController {

  @Inject ServiceSupervisor serviceSupervisor;

  public static record ListWorkspaceServicesRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(ServiceInstanceDto instance) {}
    }
  }

  @GET
  public ListWorkspaceServicesRequest.Response list(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    var entries =
        serviceSupervisor.effectiveDaemons(repoId, workspaceId).stream()
            .map(ListWorkspaceServicesRequest.Response.Entry::new)
            .toList();
    return new ListWorkspaceServicesRequest.Response(entries);
  }

  public static record StartServiceRequest() {
    public record Response(ServiceInstanceDto instance) {}
  }

  @POST
  @Path("/{daemonId}/start")
  public StartServiceRequest.Response start(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      @PathParam("daemonId") String daemonId) {
    return new StartServiceRequest.Response(serviceSupervisor.start(repoId, workspaceId, daemonId));
  }

  public static record StopServiceRequest() {
    public record Response(ServiceInstanceDto instance) {}
  }

  @POST
  @Path("/{daemonId}/stop")
  public StopServiceRequest.Response stop(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      @PathParam("daemonId") String daemonId) {
    return new StopServiceRequest.Response(serviceSupervisor.stop(repoId, workspaceId, daemonId));
  }
}
