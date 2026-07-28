package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * The {@link RepositoryLookup} this context was always specified to have: the repository registry
 * lives in <b>qits-projects</b>, and this asks it over HTTP through {@link ProjectsRepositories}. It
 * replaces {@code UnconfiguredRepositoryLookup}, the scaffold whose javadoc named this class as its
 * replacement and said it should be deleted rather than configured.
 *
 * <p><b>Why a call and not a join.</b> A workspace is a branch of a repository, but this context
 * holds no foreign key into the repositories tables — {@code Workspace.repositoryId} is a plain
 * String column, and the two contexts own separate physical databases with separate Flyway
 * lineages. There is no join to make. The port is what replaces it, and it stays deliberately
 * narrow: does this repository exist, and what is its main branch.
 *
 * <p><b>Addressing</b> is one explicit key, {@code qits.projects.url}, naming the SERVICE — scheme,
 * host and port, no path. It is derived from nothing: deriving one service's address from another's
 * is the mistake qits-workspace-daemon's notes call out by name, because a wrong address surfaces
 * as a 404 that looks like an empty registry.
 *
 * <p><b>A missing address fails closed at startup</b> in a production build, exactly as the
 * scaffold did. The alternative — coming up and answering 404 to every repository-scoped route — is
 * a misconfiguration wearing the costume of an empty system. Dev and test skip the check and the
 * suite's {@code FakeRepositoryLookup} wins over this bean anyway, which is why this one keeps
 * {@link DefaultBean}: without it the two are an ambiguous dependency and the build fails in
 * {@code ArcProcessor#validate} for every test at once.
 *
 * <p><b>"Not found" and "could not ask" are different answers</b>, and that distinction is most of
 * what this class is for. Only a 404 becomes {@link Optional#empty()}. Any other status, and any
 * transport failure, throws — because {@code require()} turns empty into a 404 the caller cannot
 * tell apart from a mistyped id, so folding an outage into it would report a dead qits-projects as
 * a user error.
 *
 * <p>Not cached, deliberately, while this is a prototype: {@code find} sits on nearly every
 * repository-scoped route, so a cache is a real optimisation but also a staleness policy (a
 * repository's main branch can change), and that is a decision to take on purpose rather than
 * inherit from the first implementation.
 */
@ApplicationScoped
@DefaultBean
public class HttpRepositoryLookup implements RepositoryLookup {

  private static final Logger LOG = Logger.getLogger(HttpRepositoryLookup.class);

  /**
   * The configured address, read only to decide whether this service is wired at all and to name it
   * in errors. The client itself is addressed by {@code quarkus.rest-client.qits-projects.url},
   * which application.properties derives from this same key.
   */
  @ConfigProperty(name = "qits.projects.url")
  Optional<String> baseUrl;

  @Inject @RestClient ProjectsRepositories repositories;

  void assertConfigured(@Observes StartupEvent event) {
    if (configuredAddress() != null) {
      LOG.infof("Repository registry: %s", configuredAddress());
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
          containers on qits-net               QITS_PROJECTS_URL=http://qits-projects:8080

        A service-discovery url works too, if one is configured: QITS_PROJECTS_URL=stork://qits-projects\
        """);
  }

  @Override
  public Optional<RepositoryView> find(String repoId) {
    if (repoId == null || repoId.isBlank()) {
      return Optional.empty();
    }
    String address = configuredAddress();
    if (address == null) {
      // Only reachable in dev/test, where assertConfigured warned instead of throwing.
      return Optional.empty();
    }
    ProjectsRepositories.GetRepositoryResponse answer;
    try {
      answer = repositories.get(repoId);
    } catch (WebApplicationException http) {
      int status = http.getResponse().getStatus();
      if (status == 404) {
        return Optional.empty();
      }
      throw new IllegalStateException(
          "qits-projects answered " + status + " while resolving repository " + repoId, http);
    } catch (RuntimeException transportFailure) {
      // Connection refused, DNS failure, timeout: NOT empty. An unreachable registry is not the
      // same answer as "no such repository", and require() would make them indistinguishable.
      throw new IllegalStateException(
          "qits-projects unreachable at " + address + " while resolving repository " + repoId,
          transportFailure);
    }
    if (answer == null || answer.repository() == null) {
      return Optional.empty();
    }
    String id = answer.repository().id();
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new RepositoryView(id, answer.repository().mainBranch()));
  }

  /** The configured address, or null when this service is unwired. */
  private String configuredAddress() {
    return baseUrl.map(String::trim).filter(u -> !u.isEmpty()).orElse(null);
  }
}
