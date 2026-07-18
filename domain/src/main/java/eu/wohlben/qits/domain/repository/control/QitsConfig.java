package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import eu.wohlben.qits.domain.daemon.entity.HealthCheckKind;
import eu.wohlben.qits.domain.daemon.entity.LogObserverKind;
import eu.wohlben.qits.domain.daemon.entity.RestartPolicy;
import eu.wohlben.qits.domain.repository.entity.RepositoryArchetype;
import java.util.List;
import java.util.Map;

/**
 * The parsed, framework-free representation of a repository's committed {@code .qits-config.yml}
 * (see {@code docs/features/2026-07-18_qits-config-in-repo-configuration.md}). Every field maps 1:1
 * onto an existing entity field — the schema is the entity model re-expressed in kebab-case, so
 * there are no new domain concepts here. {@link QitsConfigParser} produces it; {@link
 * QitsConfigReconciler} upserts it into the existing tables.
 *
 * <p>Declared actions/daemons are namespaced by {@link #CONFIG_NAME_SUFFIX}: their stored name is
 * {@code <declared-name>@qits-config}. The write API rejects that suffix in user-supplied names, so
 * config- and UI-origin entries can never collide and "which origin" is derivable from the name
 * alone (no persisted column). See {@link #configName}/{@link #isConfigName}.
 */
public record QitsConfig(
    RepositorySection repository,
    List<FrameworkDecl> frameworks,
    List<ActionDecl> actions,
    List<DaemonDecl> daemons) {

  /**
   * The reserved name suffix stamped on every config-declared action/daemon. URL-safe, no spaces.
   */
  public static final String CONFIG_NAME_SUFFIX = "@qits-config";

  /** An absent/empty file — the no-op that keeps a config-free repository on the old path. */
  public static final QitsConfig EMPTY = new QitsConfig(null, List.of(), List.of(), List.of());

  /** Normalize the collections to non-null so callers never null-check. */
  public QitsConfig {
    frameworks = frameworks == null ? List.of() : List.copyOf(frameworks);
    actions = actions == null ? List.of() : List.copyOf(actions);
    daemons = daemons == null ? List.of() : List.copyOf(daemons);
  }

  public boolean isEmpty() {
    return repository == null && frameworks.isEmpty() && actions.isEmpty() && daemons.isEmpty();
  }

  /** The stored name for a declared entry: {@code <base>@qits-config}. */
  public static String configName(String base) {
    return base + CONFIG_NAME_SUFFIX;
  }

  /** Whether {@code name} belongs to the config namespace (i.e. is a config-origin entry). */
  public static boolean isConfigName(String name) {
    return name != null && name.endsWith(CONFIG_NAME_SUFFIX);
  }

  /**
   * The declared base name for a stored config name (strips the suffix); pass-through otherwise.
   */
  public static String baseName(String name) {
    return isConfigName(name)
        ? name.substring(0, name.length() - CONFIG_NAME_SUFFIX.length())
        : name;
  }

  /**
   * Where an action/daemon came from: hand-made in the UI, or declared in {@code .qits-config.yml}.
   */
  public enum Origin {
    UI,
    CONFIG
  }

  /** An entry's origin, derived from whether its name carries the config suffix. */
  public static Origin originOf(String name) {
    return isConfigName(name) ? Origin.CONFIG : Origin.UI;
  }

  /** The {@code repository:} section: fields the file may own on the repository itself. */
  public record RepositorySection(String mainBranch, RepositoryArchetype archetype) {}

  /** One {@code frameworks[]} entry — a detection override/hint, consumed live, never stored. */
  public record FrameworkDecl(String kind, String root) {}

  /** One {@code actions[]} entry → a repository-scoped {@code ActionConfiguration}. */
  public record ActionDecl(
      String name,
      String description,
      String execute,
      String check,
      boolean interactive,
      Map<String, String> environment) {}

  /** One {@code daemons[]} entry → a {@code RepositoryDaemon} and its embeddables. */
  public record DaemonDecl(
      String name,
      String description,
      String start,
      String readyPattern,
      Boolean otel,
      Boolean autoStart,
      RestartPolicy restartPolicy,
      Integer maxRestarts,
      String stopSignal,
      Map<String, String> environment,
      WebViewDecl webView,
      List<ObserverDecl> observers,
      List<SourceDecl> sources,
      List<HealthCheckDecl> healthChecks) {}

  /** The {@code web-view:} block → {@code WebView} embeddable. */
  public record WebViewDecl(Integer port, String entryPath, String basePath) {}

  /** One {@code observers[]} entry → {@code LogObserver} embeddable. */
  public record ObserverDecl(LogObserverKind kind, String pattern, DaemonEventSeverity severity) {}

  /** One {@code sources[]} entry → {@code LogSource} embeddable (FILE sources only). */
  public record SourceDecl(String path, String label) {}

  /** One {@code health-checks[]} entry → {@code HealthCheck} embeddable. */
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
