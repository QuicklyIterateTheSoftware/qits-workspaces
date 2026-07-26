package eu.wohlben.qits.domain.capture.api;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Opens CORS on exactly {@code /api/capture} — and nothing else. The capturing app's browser posts
 * cross-origin from whatever origin the app runs on, so this one path answers preflight and echoes
 * permissive headers; the rest of the qits API stays same-origin-only (deliberately not {@code
 * quarkus.http.cors}, which would open every endpoint).
 */
@ApplicationScoped
public class CaptureCorsRoute {

  /**
   * Before RESTEasy Reactive (router order 1500 — see {@code DevModeSpaFallbackRoute}'s order map):
   * REST's automatic OPTIONS handling would otherwise answer the preflight without CORS headers,
   * failing every browser's check. A plain {@code route()} without an explicit order is not
   * guaranteed to precede 1500.
   */
  private static final int BEFORE_REST = 500;

  void init(@Observes Router router) {
    router.route("/api/capture").order(BEFORE_REST).handler(this::cors);
  }

  private void cors(RoutingContext rc) {
    // On every response — including REST's 404/413 errors, which the browser can only read
    // cross-origin if they carry the header too.
    rc.response().putHeader("Access-Control-Allow-Origin", "*");
    if (rc.request().method() == HttpMethod.OPTIONS) {
      rc.response()
          .putHeader("Access-Control-Allow-Methods", "POST, OPTIONS")
          // Content-Encoding is not CORS-safelisted; the library sends gzip.
          .putHeader("Access-Control-Allow-Headers", "Content-Type, Content-Encoding")
          .putHeader("Access-Control-Max-Age", "86400")
          .setStatusCode(204)
          .end();
      return;
    }
    rc.next();
  }
}
