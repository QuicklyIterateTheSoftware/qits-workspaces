package eu.wohlben.qits.domain.repository.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A framework-free fluent builder for a workspace container's {@code docker run} argv — the
 * container-creation sibling of {@link eu.wohlben.qits.domain.agent.control.CodingAgent}. It
 * accumulates the run parameters (name, user, labels, host aliases, network, volumes, image,
 * command) and renders them, in a fixed order, into the argv that follows {@code docker run}.
 *
 * <p>Callers do not construct this directly with the cross-cutting config; they obtain a pre-seeded
 * instance from {@link WorkspaceContainerFactory} (which guarantees the shared credential volume,
 * the {@code qits.*} labels, the docker-host alias and the host uid are always present) and only
 * add what varies. {@link DockerExecutor#run} prepends the runtime binary and {@code run} and
 * executes the result. Setter call order is irrelevant — {@link #toRunArgv()} always emits the same
 * order.
 */
public final class WorkspaceContainer {

  private String name;
  private String user;
  private final Map<String, String> labels = new LinkedHashMap<>();
  private final List<String> addHosts = new ArrayList<>();
  private final Map<String, String> env = new LinkedHashMap<>();
  private final List<String[]> volumes = new ArrayList<>(); // {volumeName, mountPath}
  private String network;
  private String image;
  private final List<String> command = new ArrayList<>();

  public WorkspaceContainer name(String name) {
    this.name = name;
    return this;
  }

  public WorkspaceContainer user(String user) {
    this.user = user;
    return this;
  }

  public WorkspaceContainer label(String key, String value) {
    this.labels.put(key, value == null ? "" : value);
    return this;
  }

  /** Add a {@code --add-host=<hostSpec>} entry (e.g. {@code host.docker.internal:host-gateway}). */
  public WorkspaceContainer addHost(String hostSpec) {
    this.addHosts.add(hostSpec);
    return this;
  }

  /**
   * Set a container environment variable ({@code -e key=value}). Applied to the container's main
   * process <em>and</em> inherited by every {@code docker exec} session — so it is the seam for
   * config that must be present on every command in the container, not just the entrypoint.
   */
  public WorkspaceContainer env(String key, String value) {
    this.env.put(key, value == null ? "" : value);
    return this;
  }

  /** Mount {@code volumeName} at {@code mountPath} read/write ({@code -v volumeName:mountPath}). */
  public WorkspaceContainer volume(String volumeName, String mountPath) {
    this.volumes.add(new String[] {volumeName, mountPath});
    return this;
  }

  /**
   * Attach the container to a user-defined Docker network ({@code --network <name>}) so qits (also
   * on it) can reach the container's ports by its DNS name — no host-port publishing. A blank name
   * adds nothing (the default bridge).
   */
  public WorkspaceContainer network(String network) {
    this.network = network;
    return this;
  }

  public WorkspaceContainer image(String image) {
    this.image = image;
    return this;
  }

  /** The container entrypoint command (e.g. {@code sleep infinity}), appended last. */
  public WorkspaceContainer command(String... command) {
    for (String c : command) {
      this.command.add(c);
    }
    return this;
  }

  /**
   * The {@code docker run} argv <em>after</em> the runtime binary and the {@code run} verb: {@code
   * -d --init --name … --user … --label … --add-host=… --network … -e … -v … <image> <command…>},
   * in that fixed order regardless of the order setters were called in.
   */
  public List<String> toRunArgv() {
    List<String> argv = new ArrayList<>();
    argv.add("-d");
    argv.add("--init");
    if (name != null) {
      argv.add("--name");
      argv.add(name);
    }
    if (user != null) {
      argv.add("--user");
      argv.add(user);
    }
    for (Map.Entry<String, String> label : labels.entrySet()) {
      argv.add("--label");
      argv.add(label.getKey() + "=" + label.getValue());
    }
    for (String host : addHosts) {
      argv.add("--add-host=" + host);
    }
    if (network != null && !network.isBlank()) {
      argv.add("--network");
      argv.add(network);
    }
    for (Map.Entry<String, String> variable : env.entrySet()) {
      argv.add("-e");
      argv.add(variable.getKey() + "=" + variable.getValue());
    }
    for (String[] volume : volumes) {
      argv.add("-v");
      argv.add(volume[0] + ":" + volume[1]);
    }
    if (image != null) {
      argv.add(image);
    }
    argv.addAll(command);
    return argv;
  }
}
