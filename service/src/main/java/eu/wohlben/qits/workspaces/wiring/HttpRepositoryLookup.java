package eu.wohlben.qits.workspaces.wiring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@link RepositoryLookup} this context was always specified to have: the repository registry
 * lives in <b>qits-projects</b>, and this asks it over HTTP. It replaces
 * {@code UnconfiguredRepositoryLookup}, the scaffold whose javadoc named this class as its
 * replacement and said it should be deleted rather than configured.
 *
 * <p><b>Why a call and not a join.</b> A workspace is a branch of a repository, but this context
 * holds no foreign key into the repositories tables — {@code Workspace.repositoryId} is a plain
 * String column, and the two contexts own separate physical databases with separate Flyway
 * lineages. There is no join to make. The port is what replaces it, and it stays deliberately
 * narrow: does this repository exist, and what is its main branch.
 *
 * <p><b>Addressing.</b> One explicit key, {@code qits.projects.url}, naming the SERVICE — scheme,
 * host and port, no path. The {@code /projects/api} prefix below is qits-projects' own gateway
 * segment, served by that service rather than added by a proxy, so it is the same path whether this
 * call goes direct on {@code qits-net} or through the gateway. That is why one base url covers both
 * topologies, and why the key is not derived from anything: deriving one service's address from
 * another is the mistake qits-workspace-daemon's notes call out by name, because a wrong address
 * surfaces as a 404 that looks like an empty registry.
 *
 * <p><b>A missing address fails closed at startup</b>, in a production build, exactly as the
 * scaffold did. The alternative — coming up and answering 404 to every repository-scoped route — is
 * a misconfiguration wearing the costume of an empty system. Dev and test skip the check and the
 * suite's {@code FakeRepositoryLookup} wins over this bean anyway, which is why this one keeps
 * {@link DefaultBean}: without it the two beans are an ambiguous dependency and every test fails.
 *
 * <p><b>"Not found" and "could not ask" are different answers.</b> Only a 404 becomes
 * {@link Optional#empty()}. A connection failure or a 5xx throws, because reporting an unreachable
 * qits-projects as "that repository does not exist" would let a whole-service outage look like a
 * user typo — and {@code require()} turns empty into a 404 the caller cannot tell apart.
 *
 * <p>Not cached, deliberately, while this is a prototype: {@code find} sits on nearly every
 * repository-scoped route, so a cache is a real optimisation but also a staleness policy
 * (a repository's main branch can change), and that is a decision to take on purpose rather than
 * inherit from the first implementation.
 */
@ApplicationScoped
@DefaultBean
public class HttpRepositoryLookup implements RepositoryLookup {

  private static final Logger LOG = Logger.getLogger(HttpRepositoryLookup.class);

  /**
   * qits-projects' base address — scheme, host and port only. Empty means unconfigured, which
   * {@link #assertConfigured} turns into a startup failure in a production build.
   */
  @ConfigProperty(name = "qits.projects.url")
  Optional<String> baseUrl;

  private final ObjectMapper json = new ObjectMapper();

  /**
   * A bean field built here rather than a {@code static final} one. An {@link HttpClient} is live
   * machinery — a selector thread and a connection pool — and Quarkus initializes application
   * classes at <em>build</em> time for the native image, so a static initializer would put a running
   * client into the image heap and native-image rejects that outright ("An object of type
   * 'jdk.internal.net.http.HttpClientFacade' was found in the image heap"). qits-observability and
   * qits-artifacts both hit this; see their {@code OtelForwarder} / {@code CiPostReceiveNotifier}.
   */
  private HttpClient client;

  @PostConstruct
  void openClient() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  void assertConfigured(@Observes StartupEvent event) {
    if (baseUrl.filter(u -> !u.isBlank()).isPresent()) {
      LOG.infof("Repository registry: %s", baseUrl.get());
      return;
    }
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      LOG.warnf(
          "qits.projects.url is unset: every repository-scoped route will 404. Tolerated in %s; a"
              + " production build refuses to start.",
          LaunchMode.current());
      return;
    }
    throw new IllegalStateException(
        """
        qits-workspaces started with no address for qits-projects.

        A workspace is a branch of a repository, so this service cannot answer anything without a \
        repository registry to ask. Every repository-scoped route would 404 and every workspace \
        create would fail — which is a misconfiguration wearing the costume of an empty system, \
        and is why this fails closed instead.

        Set qits.projects.url to qits-projects' base address — scheme, host and port, no path:

          running both processes on one host   QITS_PROJECTS_URL=http://localhost:8090
          containers on qits-net               QITS_PROJECTS_URL=http://qits-projects:8080\
        """);
  }

  @Override
  public Optional<RepositoryView> find(String repoId) {
    if (repoId == null || repoId.isBlank()) {
      return Optional.empty();
    }
    String base = baseUrl.map(String::trim).filter(u -> !u.isEmpty()).orElse(null);
    if (base == null) {
      // Only reachable in dev/test, where assertConfigured warned instead of throwing.
      return Optional.empty();
    }
    // The path segment is qits-projects', not ours: it serves /projects/api itself, so this is the
    // same url through the gateway or straight at the container.
    URI uri =
        URI.create(
            base.replaceAll("/+$", "")
                + "/projects/api/repositories/"
                + URLEncoder.encode(repoId, StandardCharsets.UTF_8));
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .GET()
            .build();
    HttpResponse<String> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted asking qits-projects about " + repoId, interrupted);
    } catch (java.io.IOException unreachable) {
      // NOT empty: an unreachable registry is not the same answer as "no such repository", and
      // require() would turn empty into a 404 the caller cannot tell apart from a typo.
      throw new IllegalStateException(
          "qits-projects unreachable at " + base + " while resolving repository " + repoId,
          unreachable);
    }
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "qits-projects answered "
              + response.statusCode()
              + " while resolving repository "
              + repoId);
    }
    return parse(response.body(), repoId);
  }

  /**
   * Reads {@code {"repository":{"id":…,"mainBranch":…}}} — qits-projects' {@code RepositoryDto}
   * wrapped in the controller's Response record. Only the two fields this port declares are read;
   * the rest of that DTO (url, archetype, projectId) is none of this context's business, and not
   * binding to it is what keeps qits-projects free to change it.
   */
  private Optional<RepositoryView> parse(String body, String repoId) {
    try {
      JsonNode repository = json.readTree(body).path("repository");
      if (repository.isMissingNode() || repository.isNull()) {
        return Optional.empty();
      }
      String id = repository.path("id").asText(null);
      String mainBranch = repository.path("mainBranch").asText(null);
      if (id == null || id.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(new RepositoryView(id, mainBranch));
    } catch (Exception malformed) {
      throw new IllegalStateException(
          "Unreadable answer from qits-projects while resolving repository " + repoId, malformed);
    }
  }
}
