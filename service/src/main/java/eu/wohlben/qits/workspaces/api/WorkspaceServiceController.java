package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.ServiceSupervisor;
import eu.wohlben.qits.workspaces.dto.ServiceInstanceDto;
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
 * start/stop. {@code {serviceId}} path params carry the config-declared service {@code id:}
 * (defaulting to its name). The event feed moved to the durable {@code /service-events} endpoint.
 */
@Path("/workspaces/{id}/services")
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
  public ListWorkspaceServicesRequest.Response list(@PathParam("id") Long id) {
    var entries =
        serviceSupervisor.effectiveServices(id).stream()
            .map(ListWorkspaceServicesRequest.Response.Entry::new)
            .toList();
    return new ListWorkspaceServicesRequest.Response(entries);
  }

  public static record StartServiceRequest() {
    public record Response(ServiceInstanceDto instance) {}
  }

  @POST
  @Path("/{serviceId}/start")
  public StartServiceRequest.Response start(
      @PathParam("id") Long id, @PathParam("serviceId") String serviceId) {
    return new StartServiceRequest.Response(serviceSupervisor.start(id, serviceId));
  }

  public static record StopServiceRequest() {
    public record Response(ServiceInstanceDto instance) {}
  }

  @POST
  @Path("/{serviceId}/stop")
  public StopServiceRequest.Response stop(
      @PathParam("id") Long id, @PathParam("serviceId") String serviceId) {
    return new StopServiceRequest.Response(serviceSupervisor.stop(id, serviceId));
  }
}
