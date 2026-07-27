package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The fallback {@link RepositoryLookup} that exists so this module can be <em>packaged</em>, and
 * refuses to let it <em>run</em> unwired.
 *
 * <p>The distinction is the whole point. {@code RepositoryLookup} is a mandatory {@code @Inject} on
 * purpose — its javadoc says an application without one "should fail at startup rather than 404
 * every workspace at runtime" — but Quarkus resolves injection during augmentation, so with no bean
 * of this type on the classpath the <strong>build</strong> fails, not the startup. That made
 * qits-workspaces the one extracted context that could not be turned into a process at all: three
 * unsatisfied injection points ({@code WorkspaceService}, {@code WorkspaceResolver}, {@code
 * CaptureService}) took the quarkus-maven-plugin's build goal down with them.
 *
 * <p>So this bean satisfies augmentation and then reinstates the documented behaviour one layer
 * later: in a {@link LaunchMode#NORMAL} build it throws on startup, naming what is missing. The
 * contract is unchanged — an unwired deployment still dies immediately and loudly, it just dies
 * when you start it rather than when you build it.
 *
 * <p>In dev and test the check is skipped and lookups simply return empty, matching {@code
 * ForwardAuthMechanism}'s existing {@code LaunchMode} guard in this repo: {@code quarkus:dev} and
 * the suite have to stay runnable with nothing wired, and the suite supplies its own {@code
 * FakeRepositoryLookup} anyway — which wins over this one, because {@link DefaultBean} yields to any
 * other bean of the type.
 *
 * <p><strong>This is scaffolding with an expiry date.</strong> Its replacement is a real
 * implementation backed by qits-projects over HTTP, at which point this class is deleted rather than
 * configured — see {@code migration-deployables-plan.md} §3 in the superproject.
 */
@ApplicationScoped
@DefaultBean
public class UnconfiguredRepositoryLookup implements RepositoryLookup {

  private static final Logger LOG = Logger.getLogger(UnconfiguredRepositoryLookup.class);

  void assertConfigured(@Observes StartupEvent event) {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      LOG.warnf(
          "No RepositoryLookup implementation: every repository-scoped route will 404. Tolerated in"
              + " %s; a production build refuses to start.",
          LaunchMode.current());
      return;
    }
    throw new IllegalStateException(
        """
        qits-workspaces started with no RepositoryLookup implementation.

        A workspace is a branch of a repository, so this service cannot answer anything without a \
        repository registry to ask. Every repository-scoped route would 404 and every workspace \
        create would fail — which is a misconfiguration wearing the costume of an empty system, \
        and is why this fails closed instead.

        Provide a bean implementing eu.wohlben.qits.workspaces.control.RepositoryLookup. The \
        intended one is backed by qits-projects over HTTP; until it exists, this service is not \
        deployable. See migration-deployables-plan.md in the superproject.\
        """);
  }

  @Override
  public Optional<RepositoryView> find(String repoId) {
    return Optional.empty();
  }
}
