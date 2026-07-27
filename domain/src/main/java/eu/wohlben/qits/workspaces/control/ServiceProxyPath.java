package eu.wohlben.qits.workspaces.control;

/**
 * The single source of truth for the service web-view proxy's path shape: {@code
 * /service/{workspaceId}/{serviceId}/}. Shared by the launch-time {@code QITS_PUBLIC_BASE}
 * injection, the {@code ServiceInstanceDto.proxyPath} projection, and the service module's proxy
 * route — the base baked into the dev server's emitted URLs at spawn must match what the proxy
 * serves.
 *
 * <p>Keyed by (workspace id, serviceId), <em>not</em> commandId: the supervisor creates a new
 * command row per relaunch, but the pair is stable across restarts and known before spawn (the base
 * must be in the environment at launch). The workspace is named by its own id — the branch-derived
 * label it used to carry is unique only per repository.
 */
public final class ServiceProxyPath {

  public static final String PREFIX = "/service/";

  private ServiceProxyPath() {}

  /** The proxied base path for one service in one workspace, with trailing slash. */
  public static String base(Long workspaceId, String serviceId) {
    return PREFIX + workspaceId + "/" + serviceId + "/";
  }

  /**
   * The base the service's app is actually served under: the proxy prefix plus the definition's
   * {@code webView.basePath} when set (stored slash-less), with trailing slash. This is both {@code
   * QITS_PUBLIC_BASE} and {@code ServiceInstanceDto.proxyPath} — the invariant that the dev server
   * serves under exactly the path the proxy exposes it at.
   */
  public static String servedBase(Long workspaceId, String serviceId, String basePath) {
    String base = base(workspaceId, serviceId);
    return basePath == null || basePath.isEmpty() ? base : base + basePath + "/";
  }
}
