package eu.wohlben.qits.workspaces.wiring;

import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Supplies the separately audience-bound bearer for Workspaces' qits-projects REST client. */
@ApplicationScoped
public class IdpProjectsBearer {

  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @ConfigProperty(name = "quarkus.oidc-client.projects.client-enabled")
  boolean enabled;

  @Inject @NamedOidcClient("projects") OidcClient oidcClient;

  private final TokensHelper tokens = new TokensHelper();

  public Optional<String> authorization() {
    if (!enabled) {
      return Optional.empty();
    }
    String token = tokens.getTokens(oidcClient).await().atMost(TOKEN_TIMEOUT).getAccessToken();
    return Optional.ofNullable(token).filter(value -> !value.isBlank()).map(value -> "Bearer " + value);
  }
}
