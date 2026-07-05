package eu.wohlben.qits.daemonproxy;

import eu.wohlben.qits.domain.daemon.control.DaemonProxyPath;
import eu.wohlben.qits.domain.daemon.control.DaemonSupervisor;
import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.httpproxy.HttpProxy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * The daemon web-view reverse proxy: {@code /daemon/{worktreeId}/{daemonId}/*} on the qits origin
 * forwards to the daemon's dev server on its published localhost port — verbatim passthrough, no
 * prefix stripping, no rewriting. The dev server itself serves under the prefix (it was launched
 * with {@code QITS_PUBLIC_BASE}, see {@link DaemonProxyPath}), so assets and the HMR websocket stay
 * inside it; vertx-http-proxy forwards WebSocket upgrades by default. Because the frame shares the
 * qits origin, the UI's DOM picker reads {@code iframe.contentDocument} directly — no injection.
 *
 * <p>Security posture: the origin is resolved exclusively from supervisor state — the port comes
 * from the container's published-port mapping recorded at daemon launch, never from any request
 * component, and targets are pinned to {@code 127.0.0.1}; unknown keys 404 without connecting
 * anywhere (the SSRF constraints from the feature doc). Two accepted consequences, both bounded by
 * the existing trust model ("qits runs these apps as processes with the user's privileges"): the
 * framed app's JS runs same-origin with qits, and every web-viewable daemon is reachable by anyone
 * who can reach qits itself. Note {@code /daemon/*} is a raw router route, so websockets-next's
 * {@code SameOriginUpgradeCheck} does not guard it — if qits ever grows auth, this route needs the
 * same guard.
 */
@ApplicationScoped
public class DaemonProxyRoute {

  @Inject Vertx vertx;

  @Inject DaemonSupervisor supervisor;

  private HttpClient proxyClient;

  void init(@Observes Router router) {
    proxyClient = vertx.createHttpClient();
    router.route(DaemonProxyPath.PREFIX + "*").handler(this::handle);
  }

  private void handle(RoutingContext rc) {
    String path = rc.request().path();
    String[] segments = path.substring(DaemonProxyPath.PREFIX.length()).split("/", 3);
    if (segments.length < 2 || segments[0].isEmpty() || segments[1].isEmpty()) {
      respond(rc, 404, "No daemon here.");
      return;
    }
    String worktreeId = segments[0];
    String daemonId = segments[1];

    if (segments.length == 2) {
      // Redirect the bare /daemon/{w}/{d} to the trailing-slash form so relative URLs inside the
      // framed document resolve under the base path.
      String query = rc.request().query();
      rc.response()
          .setStatusCode(302)
          .putHeader("Location", path + "/" + (query == null ? "" : "?" + query))
          .end();
      return;
    }

    // The request stays untouched while the supervisor lookup runs off the event loop (its monitor
    // can be held for the duration of a daemon launch); the proxy resumes it when forwarding.
    rc.request().pause();
    rc.vertx()
        .executeBlocking(() -> supervisor.proxyTarget(worktreeId, daemonId))
        .onFailure(e -> respond(rc, 502, "Daemon lookup failed."))
        .onSuccess(target -> route(rc, target));
  }

  private void route(RoutingContext rc, Optional<DaemonSupervisor.ProxyTarget> target) {
    if (target.isEmpty()) {
      respond(rc, 404, "No web-viewable daemon here.");
      return;
    }
    DaemonStatus status = target.get().status();
    switch (status) {
      case STARTING, RESTARTING -> respondSplash(rc, status);
      case STOPPED, CRASHED ->
          respond(
              rc,
              502,
              "The daemon is not running (" + status + ") — start it from the worktree page.");
      case READY, DEGRADED -> {
        Integer hostPort = target.get().hostPort();
        if (hostPort == null) {
          respond(
              rc,
              502,
              "The worktree container does not publish the daemon's port — recreate the"
                  + " container to pick it up.");
          return;
        }
        // Per-request proxy over the shared client: the origin is fixed here, from supervisor
        // state only — never derived from the request.
        HttpProxy.reverseProxy(proxyClient).origin(hostPort, "127.0.0.1").handle(rc.request());
      }
    }
  }

  /** A qits-branded splash that refreshes itself until the dev server is up. */
  private void respondSplash(RoutingContext rc, DaemonStatus status) {
    String html =
        "<!doctype html><html><head><title>qits</title>"
            + "<meta http-equiv=\"refresh\" content=\"2\">"
            + "<style>body{font-family:system-ui,sans-serif;display:flex;align-items:center;"
            + "justify-content:center;height:100vh;margin:0;color:#666}</style></head>"
            + "<body><p>daemon is "
            + status.name().toLowerCase()
            + "… this page refreshes automatically</p></body></html>";
    rc.response().setStatusCode(200).putHeader("Content-Type", "text/html").end(html);
  }

  private void respond(RoutingContext rc, int status, String message) {
    String html =
        "<!doctype html><html><head><title>qits</title>"
            + "<style>body{font-family:system-ui,sans-serif;display:flex;align-items:center;"
            + "justify-content:center;height:100vh;margin:0;color:#666}</style></head>"
            + "<body><p>"
            + message
            + "</p></body></html>";
    rc.response().setStatusCode(status).putHeader("Content-Type", "text/html").end(html);
  }
}
