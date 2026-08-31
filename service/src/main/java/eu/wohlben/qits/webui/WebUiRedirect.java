package eu.wohlben.qits.webui;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

/**
 * {@code /workspaces} → {@code /workspaces/}, and nothing else.
 *
 * <p>Quinoa mounts the web client at {@code /workspaces/*}, which does not match the bare segment — so
 * before this route existed, typing {@code /workspaces} into a browser answered 404 while
 * {@code /workspaces/} served the client. Upstream behaviour, but not a defensible surface: the segment
 * is this service's to serve in every spelling, and the bare one means "take me to the client".
 *
 * <p>GET and HEAD redirect; every other method is a 405 answered HERE, not left to the router. The
 * route used to declare its two methods and lean on Vert.x's router-level 405 — "some route matched
 * the path with another method" — but that computation only fires when NO route matches the
 * request, and {@code EditorProxyRoute} is a method-less catch-all at order 1000 that matches
 * everything and {@code next()}s what is not an editor origin. One such route on the router and a
 * POST here fell through to a 404 that read as "no such surface". The method contract is this
 * route's own statement now, immune to whatever else the router carries. 301, because the answer
 * will never be anything else, and the query string travels. The same route, for the same reason,
 * exists in qits-ci and qits-artifacts.
 */
@Singleton
public class WebUiRedirect {

  void init(@Observes Router router) {
    router
        .route("/workspaces")
        .handler(
            rc -> {
              // Vert.x path routes are trailing-slash tolerant: route("/workspaces") matches /workspaces/ too,
              // and answering the slash form here would sit AHEAD of Quinoa and loop the
              // redirect onto itself. Only the exact bare segment is this route's business.
              if (!"/workspaces".equals(rc.request().path())) {
                rc.next();
                return;
              }
              HttpMethod method = rc.request().method();
              if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
                rc.response().setStatusCode(405).putHeader("Allow", "GET, HEAD").end();
                return;
              }
              String query = rc.request().query();
              rc.response()
                  .setStatusCode(301)
                  .putHeader("Location", query == null ? "/workspaces/" : "/workspaces/?" + query)
                  .end();
            });
  }
}
