package eu.wohlben.qits.domain.repository.control;

/**
 * Where the qits process connects to reach a container-internal port — the host→container half of
 * the networking model, the sibling of {@link QitsHostResolver} (which resolves the container→host
 * direction). Resolved per environment by {@link ContainerRuntime#resolveTarget}: on the shared
 * {@code qits-net} it is the container's DNS name and the <em>real</em> container port (no host
 * publish); the test fake resolves it to {@code 127.0.0.1} and the port a host-clone daemon bound.
 *
 * <p>Because the target is the actual container port (not an ephemeral host port frozen at {@code
 * docker run}), a daemon that gains a web-view port after its container exists is reachable
 * immediately — no container recreation.
 */
public record ProxyOrigin(String host, int port) {}
