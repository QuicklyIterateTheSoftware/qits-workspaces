package eu.wohlben.qits.domain.daemon.api;

import eu.wohlben.qits.domain.daemon.control.DaemonEventService;
import eu.wohlben.qits.domain.daemon.dto.DaemonEventDto;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;

/**
 * The durable daemon-event feed (replacing the old in-memory per-worktree endpoint): paginated,
 * newest first, filterable by worktree, severity, time, and source. Events survive JVM restarts, so
 * last night's crash and what the classifier saw remain inspectable.
 */
@Path("/daemon-events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DaemonEventController {

  @Inject DaemonEventService daemonEventService;

  public static record ListDaemonEventsRequest() {
    public record Response(List<DaemonEventDto> events) {}
  }

  @GET
  public ListDaemonEventsRequest.Response list(
      @QueryParam("repoId") String repoId,
      @QueryParam("worktreeId") String worktreeId,
      @QueryParam("severity") DaemonEventSeverity severity,
      @QueryParam("since") Instant since,
      @QueryParam("source") String source,
      @QueryParam("page") @DefaultValue("0") @Min(0) int page,
      @QueryParam("pageSize") @DefaultValue("50") @Min(1) @Max(500) int pageSize) {
    return new ListDaemonEventsRequest.Response(
        daemonEventService.query(repoId, worktreeId, severity, since, source, page, pageSize));
  }
}
