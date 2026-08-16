package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The dial-home control socket with machine authentication enabled. The tokens are real RS256
 * bearers and Quarkus validates them against the profile's public key, so this proves the daemon's
 * Authorization header reaches the protected WebSocket rather than only testing an annotation.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class DaemonControlSocketMachineAuthTest {

  private static final String OWN_AUDIENCE = "qits-workspaces";

  @Inject Vertx vertx;

  @TestHTTPResource("/workspaces/daemon/1")
  URI endpoint;

  @Test
  void aCommissionedDaemonBearerReachesTheControlSocket() {
    assertDoesNotThrow(() -> connect(DaemonMachineTokens.token("workspace-1", OWN_AUDIENCE)));
  }

  @Test
  void aMissingBearerIsRejectedBeforeTheControlSocketOpens() {
    assertThrows(Exception.class, () -> connect(null));
  }

  @Test
  void aBearerMintedForAnotherServiceIsRejectedBeforeTheControlSocketOpens() {
    assertThrows(
        Exception.class, () -> connect(DaemonMachineTokens.token("workspace-1", "qits-projects")));
  }

  private void connect(String token) throws Exception {
    WebSocketClient client = vertx.createWebSocketClient();
    try {
      WebSocketConnectOptions options =
          new WebSocketConnectOptions()
              .setHost(endpoint.getHost())
              .setPort(endpoint.getPort())
              .setURI(endpoint.getPath());
      if (token != null) {
        options.addHeader("Authorization", "Bearer " + token);
      }
      client.connect(options).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    } finally {
      client.close();
    }
  }

  /** Gate-on production posture, with a local verification key instead of a live IdP. */
  public static class GateOn implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.auth.machine.required", "true",
          "qits.auth.forward.dev-user", "",
          "quarkus.oidc.auth-server-url", "",
          "quarkus.oidc.token.issuer", DaemonMachineTokens.ISSUER,
          "quarkus.oidc.public-key", base64Key());
    }

    private static String base64Key() {
      return DaemonMachineTokens.pem(DaemonMachineTokens.VERIFICATION_KEY)
          .replace("-----BEGIN PUBLIC KEY-----", "")
          .replace("-----END PUBLIC KEY-----", "")
          .replaceAll("\\s", "");
    }
  }
}
