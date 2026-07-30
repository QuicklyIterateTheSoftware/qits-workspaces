package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.ContainerProxyPath;
import eu.wohlben.qits.workspaces.control.DaemonProxyTargets;
import eu.wohlben.qits.workspaces.control.ProxyOrigin;
import eu.wohlben.qits.workspaces.daemonhost.WorkspaceTunnels;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The workspace-daemon reverse proxy: {@code /workspaces/container/{workspaceId}/*} forwards
 * verbatim to that workspace's in-container {@code qits-workspace-daemon}, reached by the
 * container's DNS name on the shared {@code qits-net} network. The sibling of {@link
 * ServiceProxyRoute}, and modelled on it almost line for line — same path arithmetic, same
 * off-event-loop lookup, same "resolve the target from our own state, never from the request" rule.
 *
 * <p><b>This is the only way to reach a daemon.</b> Its HTTP API is where the file browser, the
 * commands surface, the coding-agent surface, the service and bootstrap surfaces and the two
 * interactive websockets all live, and until this route existed none of them had an address at all
 * (migration-plan.md §9 item 16). The daemon is deliberately not a gateway route: the gateway's
 * table is static configuration mapping one prefix to one {@code host:port}, and a daemon is one
 * process per container with no stable address to configure. {@link ContainerProxyPath} carries the
 * full argument.
 *
 * <p><b>Verbatim means verbatim: this route rewrites no path.</b> The daemon receives
 * {@code /workspaces/container/{workspaceId}/files}, not {@code /files}, and is <em>configured</em>
 * to know that leading part is its own address —
 * {@code WorkspaceContainerFactory} injects {@link ContainerProxyPath#base} as
 * {@code QITS_WORKSPACE_DAEMON_API_BASE_PATH} at container creation. That is the same arrangement
 * {@link ServiceProxyRoute} has with a dev server's {@code QITS_PUBLIC_BASE}, and it is a
 * deliberate rule rather than an inherited shape: a hop that rewrites a path leaves the two ends
 * disagreeing about the destination's own address, and the disagreement shows up in redirects,
 * generated links and logs, a long way from the rewrite. Stripping here would also have to be done
 * twice — {@code vertx-http-proxy} skips its interceptor chain on a WebSocket upgrade and takes the
 * URI straight off the inbound request — so the rewrite that looks like one line is two, one of
 * them reaching into internals.
 *
 * <p>WebSocket upgrades ride along — {@code vertx-http-proxy} forwards them by default — which is
 * what carries {@code WS /terminal/commands/{id}} and {@code WS /chat/commands/{id}} without either
 * end knowing it is proxied. Note the browser's socket now traverses gateway → qits-workspaces →
 * daemon; the daemon authenticates the handshake with the same bearer it checks on every request,
 * and neither hop uses websockets-next, so no framework origin check is involved on either. Getting
 * that bearer onto an upgrade takes its own mechanism — see {@link #presentBearerOnUpgrade}, which
 * is not an optimisation but the difference between a working terminal and a 401.
 *
 * <p><b>Two interceptors, and the reason each is there.</b> The bearer is added here rather than
 * being anything the caller supplies — it is peer authentication between qits and the container, so
 * a caller-supplied one would be meaningless and a forwarded one would be a credential leak. The
 * host rewrite pins the authority the daemon sees to a constant, so it does not change under the
 * daemon when the origin does (which is exactly what the stage 2 dial-back tunnel will do).
 *
 * <p>Security posture, stated plainly because it is easy to overstate: this route scopes a request
 * to an existing ACTIVE workspace and forwards it. It does not authorize the caller, because there
 * is no owner to authorize against — qits is single-user. See {@link DaemonProxyTargets}.
 */
@ApplicationScoped
public class ContainerProxyRoute {

  @Inject Vertx vertx;

  @Inject DaemonProxyTargets targets;

  @Inject WorkspaceTunnels tunnels;

  /** The bearer the daemon requires; the same value {@code WorkspaceContainerFactory} injects. */
  @ConfigProperty(name = "qits.workspace.daemon-api-token", defaultValue = "qits-workspace-daemon")
  String daemonApiToken;

  /**
   * The daemon's own port — not where the proxy connects, but the authority it presents. Pinning it
   * to a constant is what keeps the daemon's view of who called it identical whether the request
   * arrived at the container's address or through the reverse tunnel's ephemeral loopback port.
   */
  @ConfigProperty(name = "qits.workspace.daemon-api-port", defaultValue = "13338")
  int daemonApiPort;

  /** See {@link ServiceProxyRoute}'s field of the same name for why this key may be read here. */
  @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/")
  String rootPath;

  private HttpClient proxyClient;
  private String rootPrefix;

  void init(@Observes Router router) {
    proxyClient = vertx.createHttpClient();
    rootPrefix = RootPath.prefix(rootPath);
    router.route(ContainerProxyPath.PREFIX + "*").handler(this::handle);
  }

  private void handle(RoutingContext rc) {
    String path = rc.request().path();
    // `route(PREFIX + "*")` also matches the bare prefix with no trailing slash, one character
    // short of `start` — where the substring below would overflow. No segments, no workspace.
    int start = rootPrefix.length() + ContainerProxyPath.PREFIX.length();
    if (path.length() < start) {
      respond(rc, 404, "No workspace here.");
      return;
    }
    // Limit 2: the id, then the rest. The rest is only ever LOOKED AT here — it is not removed, and
    // the request that goes on the wire still carries the whole path. The daemon knows this prefix
    // is its address because it was told so at container creation; see the class javadoc.
    String[] segments = path.substring(start).split("/", 2);
    if (segments.length < 1 || segments[0].isEmpty()) {
      respond(rc, 404, "No workspace here.");
      return;
    }
    // A non-numeric first segment is simply not a workspace — 404 without touching the database,
    // and the same 404 an unknown or soft-deleted one gets.
    Long workspaceId;
    try {
      workspaceId = Long.valueOf(segments[0]);
    } catch (NumberFormatException notAnId) {
      respond(rc, 404, "No workspace here.");
      return;
    }

    // The request stays untouched while the lookup runs off the event loop — it reads a row and may
    // bind a listener, so it needs a worker thread and a transaction; the proxy resumes it when
    // forwarding.
    rc.request().pause();
    rc.vertx()
        .executeBlocking(() -> resolve(workspaceId))
        .onFailure(e -> respond(rc, 502, "Workspace lookup failed."))
        .onSuccess(resolved -> route(rc, resolved));
  }

  /**
   * How to reach this workspace's daemon: through the reverse tunnel when its daemon can serve one,
   * and at the container's own address otherwise.
   *
   * <p>The two are strictly complementary and keyed by the daemon's announced capability version: a
   * daemon that serves streams has stopped listening on {@code qits-net}, and one that still listens
   * knows nothing about {@code OpenStream}. So there is no ambiguous middle to design around, and a
   * daemon that has not said hello yet counts as "not capable" — which is the safe direction, since
   * an image old enough to predate the tunnel is also old enough to still be listening.
   *
   * <p>The tunnel branch does not consult the workspace row or docker at all. A live control socket
   * is stronger evidence that the container is up than {@code docker inspect} is, and it costs one
   * round-trip less per request. It does mean the ACTIVE-row scoping only runs on the direct branch;
   * that is fine, because {@code WorkspaceTunnels} is keyed on the same row id and a soft-deleted
   * workspace's daemon is not connected.
   */
  private Resolved resolve(Long workspaceId) {
    return tunnels
        .originFor(workspaceId)
        .map(Resolved::tunnelled)
        .orElseGet(() -> Resolved.direct(targets.resolve(workspaceId)));
  }

  /** Either a tunnel entrance, or a direct target that still has to be interpreted. */
  private record Resolved(
      WorkspaceTunnels.TunnelOrigin tunnel, DaemonProxyTargets.DaemonTarget direct) {
    static Resolved tunnelled(WorkspaceTunnels.TunnelOrigin origin) {
      return new Resolved(origin, null);
    }

    static Resolved direct(DaemonProxyTargets.DaemonTarget target) {
      return new Resolved(null, target);
    }
  }

  /**
   * Answer differently for each way a daemon can be absent. A naive proxy reports "no such
   * workspace", "container not running", "daemon never connected" and "connect failed" as one
   * indistinguishable 502, and then every daemon problem looks like the same problem —
   * {@link ServiceProxyRoute} already distinguishes its states for that reason.
   */
  private void route(RoutingContext rc, Resolved resolved) {
    presentBearerOnUpgrade(rc.request());
    if (resolved.tunnel() != null) {
      // Only the origin moved. Same two interceptors as the direct branch, and the authority they
      // pin is the daemon's own port either way — so the daemon cannot tell which route the request
      // took, and must not be able to. The client is the tunnel's, never the shared one; see
      // WorkspaceTunnels for what sharing it would cost.
      HttpProxy.reverseProxy(resolved.tunnel().client())
          .origin(resolved.tunnel().port(), "127.0.0.1")
          .addInterceptor(bearer(daemonApiToken))
          .addInterceptor(hostRewrite(daemonApiPort))
          .handle(rc.request());
      return;
    }
    DaemonProxyTargets.DaemonTarget target = resolved.direct();
    switch (target.reachability()) {
      case NO_WORKSPACE -> respond(rc, 404, "No workspace here.");
      case NO_CONTAINER ->
          respond(
              rc,
              502,
              "The workspace container is not running — start it from the workspace page.");
      case UNREACHABLE ->
          respond(
              rc,
              502,
              "The workspace container is not reachable — try restarting the workspace container.");
      case READY -> {
        ProxyOrigin origin = target.origin();
        // Per-request proxy over the shared client: the origin is fixed here, from our own state
        // only (the container's name on the shared network + the configured port) — never from any
        // component of the request.
        HttpProxy.reverseProxy(proxyClient)
            .origin(origin.port(), origin.host())
            .addInterceptor(bearer(daemonApiToken))
            .addInterceptor(hostRewrite(daemonApiPort))
            .handle(rc.request());
      }
    }
  }

  /**
   * Present the bearer on a WebSocket upgrade, where {@link #bearer} cannot.
   *
   * <p><b>{@code vertx-http-proxy} skips its whole interceptor chain on an upgrade.</b> Verified in
   * 4.5.26: {@code ReverseProxy.handle} branches to {@code handleWebSocketUpgrade} and returns
   * before the interceptor iterator is installed, and that path then copies the headers straight
   * off the inbound {@code HttpServerRequest}. So the interceptor below runs for every ordinary
   * request and for none of the two interactive sockets — and the daemon authenticates its
   * handshake with the same bearer it checks on every request, so {@code WS /terminal/commands/{id}}
   * and {@code WS /chat/commands/{id}} were answered 401 and the interactive surface was
   * unreachable. Nothing in the suite could see it: the stub origin in {@code
   * ContainerProxyRouteTest} accepts any handshake, and only a real daemon rejects one.
   *
   * <p>Setting it on the inbound request is what reaches that path, and it works for the same
   * reason the gateway's {@code EdgeHeaders.applyToUpgrade} does: {@code headers()} hands back the
   * live, mutable Netty-backed map the upgrade will copy. Restricted to upgrades on purpose —
   * ordinary requests keep going through the interceptor, so there is exactly one mechanism per
   * path rather than two doing the same job.
   *
   * <p>The {@code Host} pinning in {@link #hostRewrite} has no equivalent here and does not need
   * one: the upgrade path does not copy {@code Host} at all, so the daemon sees the origin's own
   * authority. Nothing in {@code WorkspaceApi} reads it.
   */
  private void presentBearerOnUpgrade(HttpServerRequest request) {
    if (request.headers().contains(HttpHeaders.UPGRADE, "websocket", true)) {
      request.headers().set("Authorization", "Bearer " + daemonApiToken);
    }
  }

  /**
   * Present qits' own credential to the daemon. The daemon's bearer is peer authentication between
   * this service and the container — it says "qits is calling", not "this user is calling", and the
   * daemon has no user identity to check anyway. So it is <em>set</em>, replacing whatever the
   * inbound request carried: forwarding a caller-supplied Authorization would be both meaningless
   * and a way to smuggle a credential into a container.
   */
  private static ProxyInterceptor bearer(String token) {
    return new ProxyInterceptor() {
      @Override
      public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
        context.request().headers().set("Authorization", "Bearer " + token);
        return context.sendRequest();
      }
    };
  }

  /**
   * Pin the authority the daemon sees. Nothing in {@code WorkspaceApi} reads {@code Host} today, so
   * this changes no behaviour — it fixes one, which is the point: without it the daemon's view of
   * who called it is whatever the origin happens to be, and the origin is exactly what moves when
   * the dial-back tunnel replaces the direct container address. A header that quietly changes
   * meaning between two deployments of the same code is worth spending three lines to prevent.
   * ({@code ProxyInterceptor} has no abstract method — it is not a functional interface — so this
   * must be an explicit implementation, not a lambda.)
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

  /**
   * Errors here answer JSON, not the qits-branded HTML {@link ServiceProxyRoute} uses. That route's
   * client is a browser rendering a framed dev server; this one's is code calling a JSON API, and
   * the shape matches what the daemon itself answers a failure with ({@code {"message": …}}) so a
   * client's error handling does not fork on which hop failed.
   */
  private void respond(RoutingContext rc, int status, String message) {
    rc.response()
        .setStatusCode(status)
        .putHeader("Content-Type", "application/json")
        .end(new io.vertx.core.json.JsonObject().put("message", message).encode());
  }
}
