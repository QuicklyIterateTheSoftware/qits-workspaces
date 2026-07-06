package eu.wohlben.qits.domain.daemon.control;

/**
 * The single source of truth for the daemon web-view proxy's path shape: {@code
 * /daemon/{workspaceId}/{daemonId}/}. Shared by the launch-time {@code QITS_PUBLIC_BASE} injection,
 * the {@code DaemonInstanceDto.proxyPath} projection, and the service module's proxy route — the
 * base baked into the dev server's emitted URLs at spawn must match what the proxy serves.
 *
 * <p>Keyed by (workspaceId, daemonId), <em>not</em> commandId: the supervisor creates a new command
 * row per relaunch, but the pair is stable across restarts and known before spawn (the base must be
 * in the environment at launch). Workspace ids are branch-derived slugs (URL-safe, unique only per
 * repository); the pair stays unambiguous because a daemon id is a UUID owned by exactly one
 * repository.
 */
public final class DaemonProxyPath {

  public static final String PREFIX = "/daemon/";

  private DaemonProxyPath() {}

  /** The proxied base path for one daemon in one workspace, with trailing slash. */
  public static String base(String workspaceId, String daemonId) {
    return PREFIX + workspaceId + "/" + daemonId + "/";
  }

  /**
   * The base the daemon's app is actually served under: the proxy prefix plus the definition's
   * {@code webView.basePath} when set (stored slash-less), with trailing slash. This is both {@code
   * QITS_PUBLIC_BASE} and {@code DaemonInstanceDto.proxyPath} — the invariant that the dev server
   * serves under exactly the path the proxy exposes it at.
   */
  public static String servedBase(String workspaceId, String daemonId, String basePath) {
    String base = base(workspaceId, daemonId);
    return basePath == null || basePath.isEmpty() ? base : base + basePath + "/";
  }
}
