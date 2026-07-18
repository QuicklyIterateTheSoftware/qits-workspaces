package eu.wohlben.qits.daemonproxy;

import eu.wohlben.qits.domain.daemon.control.DaemonProxyPath;
import eu.wohlben.qits.domain.daemon.control.DaemonSupervisor;
import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import eu.wohlben.qits.domain.repository.control.ProxyOrigin;
import eu.wohlben.qits.http.RootPath;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The daemon web-view reverse proxy: {@code /daemon/{workspaceId}/{daemonId}/*} on the qits origin
 * forwards to the daemon's dev server, reached by the workspace container's DNS name on the shared
 * {@code qits-net} network — verbatim passthrough, no prefix stripping (the one rewrite is the
 * {@code Host} header, see {@link #hostRewrite}). The dev server itself serves under the prefix (it
 * was launched with {@code QITS_PUBLIC_BASE}, see {@link DaemonProxyPath}), so assets and the HMR
 * websocket stay inside it; vertx-http-proxy forwards WebSocket upgrades by default. Because the
 * frame shares the qits origin, the UI's DOM picker reads {@code iframe.contentDocument} directly —
 * no injection.
 *
 * <p>Security posture: the origin is resolved exclusively from supervisor state — the container
 * name and port come from the daemon definition recorded at launch, never from any request
 * component; unknown keys 404 without connecting anywhere (the SSRF constraints from the feature
 * doc). Two accepted consequences, both bounded by the existing trust model ("qits runs these apps
 * as processes with the user's privileges"): the framed app's JS runs same-origin with qits, and
 * every web-viewable daemon is reachable by anyone who can reach qits itself. Note {@code
 * /daemon/*} is a raw router route, so websockets-next's {@code SameOriginUpgradeCheck} does not
 * guard it — but the global {@code QitsAuthPolicy} (auth-core) does: in every auth build variant
 * this route requires an authenticated identity like the rest of the UI surface (the oauth session
 * cookie or the proxy's forward-auth headers ride along automatically, the iframe being
 * same-origin).
 */
@ApplicationScoped
public class DaemonProxyRoute {

  @Inject Vertx vertx;

  @Inject DaemonSupervisor supervisor;

  /**
   * Only relevant when qits itself runs under a path prefix (a qits-in-qits daemon bridges {@code
   * -Dquarkus.http.root-path}): the route below is registered on the root-path-mounted router, so
   * it matches relative to the prefix — but {@code rc.request().path()} returns the FULL path, so
   * the segment parse must strip the prefix first. {@code "/"} (the normal deployment) strips
   * nothing.
   */
  @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/")
  String rootPath;

  private HttpClient proxyClient;
  private String rootPrefix;

  void init(@Observes Router router) {
    proxyClient = vertx.createHttpClient();
    rootPrefix = RootPath.prefix(rootPath);
    router.route(DaemonProxyPath.PREFIX + "*").handler(this::handle);
  }

  private void handle(RoutingContext rc) {
    String path = rc.request().path();
    String[] segments =
        path.substring(rootPrefix.length() + DaemonProxyPath.PREFIX.length()).split("/", 3);
    if (segments.length < 2 || segments[0].isEmpty() || segments[1].isEmpty()) {
      respond(rc, 404, "No daemon here.");
      return;
    }
    String workspaceId = segments[0];
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
        .executeBlocking(() -> supervisor.proxyTarget(workspaceId, daemonId))
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
              "The daemon is not running (" + status + ") — start it from the workspace page.");
      case READY, DEGRADED -> {
        ProxyOrigin origin = target.get().origin();
        if (origin == null) {
          respond(
              rc,
              502,
              "The workspace container is not reachable — try restarting the workspace container.");
          return;
        }
        // Per-request proxy over the shared client: the origin is fixed here, from supervisor
        // state only (the container's name + port on the shared network) — never derived from the
        // request.
        HttpProxy.reverseProxy(proxyClient)
            .origin(origin.port(), origin.host())
            .addInterceptor(hostRewrite(origin.port()))
            .handle(rc.request());
      }
    }
  }

  /**
   * Present qits to the framed dev server as {@code localhost} rather than the workspace
   * container's DNS name. Angular's (Vite/webpack) dev server rejects any {@code Host} that isn't
   * localhost/an IP/allow-listed ("This host is not allowed"); {@code localhost} is always allowed.
   * This restores the Host the dev server saw before qits moved onto {@code qits-net}: back then
   * qits reached containers through a published {@code 127.0.0.1:port}, so the check passed;
   * reaching them by DNS name is what started sending the rejected Host. TCP still targets the
   * fixed origin ({@code .origin(...)}); only the Host/:authority header changes, so no per-app
   * {@code allowedHosts} config is needed. ({@code ProxyInterceptor} has no abstract method — it is
   * not a functional interface — so this must be an explicit implementation, not a lambda.)
   */
  private static ProxyInterceptor hostRewrite(int port) {
    return new ProxyInterceptor() {
      @Override
      public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
        context.request().setAuthority(HostAndPort.create("localhost", port));
        return context.sendRequest();
      }
    };
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
