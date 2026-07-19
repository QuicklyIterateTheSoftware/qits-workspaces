package eu.wohlben.qits.domain.workspace.api;

import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * The single Server-Sent-Events channel for one workspace's detail route. Emits payload-free
 * <em>invalidation hints</em> — a topic name per frame ({@code daemons}, {@code daemon-events},
 * {@code telemetry}, {@code commands}) — which the frontend maps to a TanStack Query invalidation,
 * so data keeps flowing through the unchanged REST endpoints. Replaces eight free-running polls
 * with fetch-on-signal: an idle workspace produces zero traffic. A ~25s {@code ping} heartbeat
 * keeps idle connections alive through the dev proxies; {@code EventSource} reconnects
 * automatically, and the frontend re-syncs everything on reconnect, so no replay/{@code
 * Last-Event-ID} protocol is needed.
 */
@Path("/repositories/{repoId}/workspaces/{workspaceId}/events")
public class WorkspaceEventsController {

  @Inject WorkspaceEventBroadcaster broadcaster;

  @GET
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  @Operation(hidden = true)
  public Multi<String> events(
      @PathParam("repoId") String repoId, @PathParam("workspaceId") String workspaceId) {
    return broadcaster.withHeartbeat(broadcaster.subscribe(repoId, workspaceId));
  }
}
