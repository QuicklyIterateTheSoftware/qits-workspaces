package eu.wohlben.qits.workspaces.control;

import java.util.List;
import java.util.Map;

/**
 * The parsed, framework-free representation of a workspace checkout's committed qits config file
 * ({@code .config/qits/repository.yml}, or the legacy root-level {@code .qits-config.yml} as
 * fallback). The file is <strong>authoritative</strong>: it is read in-container by the
 * workspace-daemon and surfaced to the host over the control socket as the Part-2 wire schema
 * ({@link WorkspaceConfigView} wraps it; {@code WorkspaceDaemonRegistry} Jackson-deserializes it).
 * There is no host-side DB config store and no reconciler — declared actions/services/bootstrap
 * steps live only in the file.
 *
 * <p>Every declared entry carries an explicit, deterministic string {@code id:} (defaulting to its
 * {@code name} when absent) that identifies it across the wire; a duplicate id is a user error,
 * allowed to collide.
 *
 * <p>The {@code targets} list registers this record <em>and every nested one</em> for reflection,
 * because {@code WorkspaceDaemonRegistry} deserializes it with an injected {@code ObjectMapper} and
 * nothing on a JAX-RS signature ever mentions it — so native-image has no reason to keep the
 * members, and the daemon's first {@code configView} frame would come back as {@link #EMPTY} with a
 * "doesn't map to a QitsConfig" warning that looks like a daemon bug. {@code @RegisterForReflection}
 * does not descend into nested types, so a record added below has to be added here too. See {@link
 * WorkspaceMetadata} for the same defect caught the harder way.
 */
@io.quarkus.runtime.annotations.RegisterForReflection(
    targets = {
      QitsConfig.class,
      QitsConfig.RepositorySection.class,
      QitsConfig.FrameworkDecl.class,
      QitsConfig.ActionDecl.class,
      QitsConfig.ServiceDecl.class,
      QitsConfig.BootstrapDecl.class,
      QitsConfig.WebViewDecl.class,
      QitsConfig.HealthCheckDecl.class
    })
public record QitsConfig(
    RepositorySection repository,
    List<FrameworkDecl> frameworks,
    List<ActionDecl> actions,
    // TEMPORARY alias: the committed `.qits-config.yml` (incl. the test fixtures) still uses the
    // old
    // `daemons:` key; accept it so a stale file / stale daemon image (whose ConfigJson may still
    // emit
    // `daemons`) deserializes. Drop the alias once the fixtures' two-level submodule round-trip
    // lands
    // the `services:` key (docs/epics/qits-workspace-daemon/features/2026-07-24_*).
    @com.fasterxml.jackson.annotation.JsonAlias("daemons") List<ServiceDecl> services,
    List<BootstrapDecl> bootstrap) {

  /** An absent/empty file — the no-op that keeps a config-free workspace on the old path. */
  public static final QitsConfig EMPTY =
      new QitsConfig(null, List.of(), List.of(), List.of(), List.of());

  /** Normalize the collections to non-null so callers never null-check. */
  public QitsConfig {
    frameworks = frameworks == null ? List.of() : List.copyOf(frameworks);
    actions = actions == null ? List.of() : List.copyOf(actions);
    services = services == null ? List.of() : List.copyOf(services);
    bootstrap = bootstrap == null ? List.of() : List.copyOf(bootstrap);
  }

  public boolean isEmpty() {
    return repository == null
        && frameworks.isEmpty()
        && actions.isEmpty()
        && services.isEmpty()
        && bootstrap.isEmpty();
  }

  /**
   * The {@code repository:} section: fields the file may own on the repository itself.
   *
   * <p>{@code archetype} is a plain String here, not the repositories context's {@code
   * RepositoryArchetype} enum: this context only carries the value through from the daemon's {@code
   * ConfigView} — it never branches on it, and the enum's skeleton-directory behaviour and Flyway
   * check-constraint belong to whoever owns repositories. An unrecognized value therefore
   * round-trips instead of failing deserialization.
   */
  public record RepositorySection(String mainBranch, String archetype) {}

  /** One {@code frameworks[]} entry — a detection override/hint, consumed live, never stored. */
  public record FrameworkDecl(String kind, String root) {}

  /** One {@code actions[]} entry — a config-declared workspace action. */
  public record ActionDecl(
      String id,
      String name,
      String description,
      String execute,
      String check,
      boolean interactive,
      Map<String, String> environment) {
    /** {@code id} defaults to {@code name} when absent/blank. */
    public ActionDecl {
      id = id == null || id.isBlank() ? name : id;
    }
  }

  /** One {@code services[]} entry — a config-declared workspace service (dev server). */
  public record ServiceDecl(
      String id,
      String name,
      String description,
      String start,
      String readyPattern,
      Boolean autoStart,
      RestartPolicy restartPolicy,
      Integer maxRestarts,
      String stopSignal,
      Map<String, String> environment,
      WebViewDecl webView,
      List<HealthCheckDecl> healthChecks) {
    /** {@code id} defaults to {@code name} when absent/blank. */
    public ServiceDecl {
      id = id == null || id.isBlank() ? name : id;
    }
  }

  /**
   * One {@code bootstrap[]} entry — a config-declared bootstrap step; list position is the
   * execution order.
   */
  public record BootstrapDecl(
      String id,
      String name,
      String description,
      String execute,
      String check,
      Map<String, String> environment) {
    /** {@code id} defaults to {@code name} when absent/blank. */
    public BootstrapDecl {
      id = id == null || id.isBlank() ? name : id;
    }
  }

  /** The {@code web-view:} block of a service. */
  public record WebViewDecl(Integer port, String entryPath, String basePath) {}

  /** One {@code health-checks[]} entry of a service. */
  public record HealthCheckDecl(
      String name,
      HealthCheckKind kind,
      Integer port,
      String path,
      String expectStatus,
      String command,
      Long intervalMs,
      Long timeoutMs,
      Integer healthyThreshold,
      Integer unhealthyThreshold,
      Long initialDelayMs) {}
}
