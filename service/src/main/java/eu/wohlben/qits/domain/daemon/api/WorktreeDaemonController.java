package eu.wohlben.qits.domain.daemon.api;

import eu.wohlben.qits.domain.daemon.control.DaemonSupervisor;
import eu.wohlben.qits.domain.daemon.dto.DaemonInstanceDto;
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
 * The runtime surface of daemons in one worktree: the effective daemons with their supervised
 * status (all of them, running or not — the everything-visible convention) and start/stop. The
 * event feed moved to the durable {@code /daemon-events} endpoint.
 */
@Path("/repositories/{repoId}/worktrees/{worktreeId}/daemons")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorktreeDaemonController {

  @Inject DaemonSupervisor daemonSupervisor;

  public static record ListWorktreeDaemonsRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(DaemonInstanceDto instance) {}
    }
  }

  @GET
  public ListWorktreeDaemonsRequest.Response list(
      @PathParam("repoId") String repoId, @PathParam("worktreeId") String worktreeId) {
    var entries =
        daemonSupervisor.effectiveDaemons(repoId, worktreeId).stream()
            .map(ListWorktreeDaemonsRequest.Response.Entry::new)
            .toList();
    return new ListWorktreeDaemonsRequest.Response(entries);
  }

  public static record StartDaemonRequest() {
    public record Response(DaemonInstanceDto instance) {}
  }

  @POST
  @Path("/{daemonId}/start")
  public StartDaemonRequest.Response start(
      @PathParam("repoId") String repoId,
      @PathParam("worktreeId") String worktreeId,
      @PathParam("daemonId") String daemonId) {
    return new StartDaemonRequest.Response(daemonSupervisor.start(repoId, worktreeId, daemonId));
  }

  public static record StopDaemonRequest() {
    public record Response(DaemonInstanceDto instance) {}
  }

  @POST
  @Path("/{daemonId}/stop")
  public StopDaemonRequest.Response stop(
      @PathParam("repoId") String repoId,
      @PathParam("worktreeId") String worktreeId,
      @PathParam("daemonId") String daemonId) {
    return new StopDaemonRequest.Response(daemonSupervisor.stop(repoId, worktreeId, daemonId));
  }
}
