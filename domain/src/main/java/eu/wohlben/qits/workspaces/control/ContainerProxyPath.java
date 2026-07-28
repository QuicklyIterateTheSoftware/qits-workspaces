package eu.wohlben.qits.workspaces.control;

/**
 * The single source of truth for the workspace-daemon proxy's path shape: {@code
 * /workspaces/container/{workspaceId}/…}, forwarded verbatim to that workspace's in-container
 * {@code qits-workspace-daemon}. The sibling of {@link ServiceProxyPath}, and deliberately shaped
 * the same way — {@code /workspaces/service/{w}/{s}/*} for a dev server, {@code
 * /workspaces/container/{w}/*} for the daemon.
 *
 * <p><b>Why the daemon is this service's resource and not a gateway route.</b> The gateway's route
 * table is static configuration mapping one path prefix to one {@code host:port}. A daemon is one
 * process per workspace container, addressed per workspace and living for one container lifetime:
 * it has no stable address to configure, no health check to register, and no segment it could claim
 * without conflating itself with this service's own routes. qits-workspaces owns the workspace row
 * and the container lifecycle, so proxying the daemon under {@code /workspaces} here is simply this
 * service serving its own resource. Nothing else may reach a daemon.
 *
 * <p><b>{@code container}, not {@code daemon}.</b> {@code /workspaces/daemon/{id}} is taken by the
 * control socket, and that literal is a baked cross-repo contract — {@code
 * WorkspaceContainerFactory} injects it as {@code QITS_WORKSPACE_DAEMON_URL} and only a container
 * recreate re-injects it, which is the whole reason {@code LegacyDaemonControlSocket} exists.
 * Overloading the one segment that is hardest to change would be the wrong economy.
 *
 * <p>The workspace is named by its own {@code Long} id, like every other route here: the
 * branch-derived label is unique only per repository and only among ACTIVE rows.
 */
public final class ContainerProxyPath {

  public static final String PREFIX = "/workspaces/container/";

  private ContainerProxyPath() {}

  /** The proxied base path for one workspace's daemon, with trailing slash. */
  public static String base(Long workspaceId) {
    return PREFIX + workspaceId + "/";
  }
}
