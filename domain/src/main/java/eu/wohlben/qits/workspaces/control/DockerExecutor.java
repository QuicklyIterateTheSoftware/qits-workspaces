package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.InternalServerErrorException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link ContainerRuntime} backed by the {@code docker} CLI, shelled out via {@link ProcessBuilder}
 * — the sibling of {@link GitExecutor}, deliberately with no docker-java dependency. The runtime
 * binary is configurable ({@code qits.workspace.container-runtime}) so a rootless {@code podman}
 * can be dropped in without code changes; the argv shape below is the docker/podman common subset.
 */
@ApplicationScoped
public class DockerExecutor implements ContainerRuntime {

  private static final Logger LOG = Logger.getLogger(DockerExecutor.class);

  @ConfigProperty(name = "qits.workspace.container-runtime", defaultValue = "docker")
  String runtime;

  /**
   * How the qits process reaches a workspace container's ports. {@code network} (default): qits and
   * the container share {@code qits-net}, so the target is the container's DNS name + real port —
   * no host publish. {@code bridge-ip}: read the container's IP off the network via {@code inspect}
   * (plain-Linux hosts where the bridge is host-routable); still no publish.
   */
  @ConfigProperty(name = "qits.workspace.container-network", defaultValue = "network")
  String containerNetwork;

  /**
   * The bound on the {@code pull} in {@link #ensureImage}. See the config key's own comment for why
   * it is generous and why it exists at all.
   */
  @ConfigProperty(name = "qits.workspace.image-pull-timeout-ms", defaultValue = "900000")
  long imagePullTimeoutMs;

  /**
   * Assembles the {@code docker run} argv (with the always-on cross-cutting config — credential
   * volume, {@code qits.*} labels, host alias, host uid). This executor only prepends the runtime
   * binary + {@code run} and shells it out.
   */
  @Inject WorkspaceContainerFactory containerFactory;

  /**
   * Create the shared credential volume once at startup so it exists before any workspace container
   * mounts it — and so an operator can run the one-time login before the first workspace is
   * created. Best-effort: a missing/broken runtime just logs, exactly like the rest of this
   * executor.
   */
  void onStart(@Observes StartupEvent event) {
    // The shared volumes every workspace container mounts: the coding-agent credential store and
    // the
    // Maven repo / pnpm store build caches. Created here so they exist before the first container.
    ensureVolume(
        containerFactory.claudeVolume(), "agent credential (agent auth may be unavailable)");
    ensureVolume(containerFactory.mavenVolume(), "Maven repo cache");
    ensureVolume(containerFactory.pnpmVolume(), "pnpm store cache");
    ensureNetwork();
  }

  /** Idempotent {@code docker volume create}; no-op when the volume name is blank. */
  void ensureVolume(String volume, String purpose) {
    if (volume == null || volume.isBlank()) {
      return;
    }
    ExecResult result = runCapturing(null, List.of(runtime, "volume", "create", volume));
    if (result.exitCode() != 0) {
      LOG.warnf("Could not ensure shared %s volume '%s': %s", purpose, volume, result.output());
    }
  }

  /**
   * Ensure the shared workspace network exists before any container joins it. Inspect-then-create
   * so a network already provisioned by the devcontainer compose (its usual owner) is left
   * untouched; this only covers qits run outside compose. Best-effort — a broken runtime just logs.
   */
  void ensureNetwork() {
    String net = containerFactory.network();
    if (net == null || net.isBlank()) {
      return;
    }
    if (runCapturing(null, List.of(runtime, "network", "inspect", net)).exitCode() == 0) {
      return;
    }
    ExecResult result = runCapturing(null, List.of(runtime, "network", "create", net));
    if (result.exitCode() != 0) {
      LOG.warnf(
          "Could not ensure shared workspace network '%s' (web view may be unreachable): %s",
          net, result.output());
    }
  }

  @Override
  public String containerName(String workspaceId, String repoId) {
    // repoId is a UUID; the short prefix keeps the name readable and well under docker's length
    // cap while still being effectively unique per repo. Prefix keeps the first char alphanumeric.
    String shortRepo = repoId.length() > 8 ? repoId.substring(0, 8) : repoId;
    return "qits-ws-" + workspaceId + "-" + shortRepo;
  }

  @Override
  public String run(String repoId, String workspaceId, Long rowId, String branch, String parent) {
    // One implementation, taking no tap. The capturing overload used to be a second copy of the
    // whole sequence, which is one copy too many now that a pull sits in front of it.
    return run(repoId, workspaceId, rowId, branch, parent, null);
  }

  @Override
  public ExecResult exec(
      String container, String workdir, Map<String, String> env, String... argv) {
    return exec(container, workdir, env, (java.util.function.Consumer<String>) null, argv);
  }

  @Override
  public ExecResult exec(
      String container,
      String workdir,
      Map<String, String> env,
      java.util.function.Consumer<String> onLine,
      String... argv) {
    List<String> command = new ArrayList<>(execArgv(container, false, workdir, env));
    for (String arg : argv) {
      command.add(arg);
    }
    return runCapturing(null, command, onLine);
  }

  @Override
  public String run(
      String repoId,
      String workspaceId,
      Long rowId,
      String branch,
      String parent,
      java.util.function.Consumer<String> onLine) {
    String name = containerName(workspaceId, repoId);
    // The image before anything else: the pin is a registry reference and the host daemon may not
    // hold it. A `docker run` on an absent reference would pull on its own, silently and unbounded;
    // pulling here is what makes the wait visible on the tap and the failure legible.
    ensureImage(onLine);
    // Create-if-absent the labeled per-workspace /workspace volume before the container mounts it,
    // so recreation reattaches the same checkout (and dangling-volume reconcile has its handle).
    ensureWorkspaceVolumeIfPersistent(repoId, workspaceId, branch, parent);
    // The factory owns the argv shape and the always-on cross-cutting config (credential volume,
    // qits.* labels, host alias, host uid, shared network); this executor only prepends the runtime
    // + `run` verb.
    List<String> argv = new ArrayList<>();
    argv.add(runtime);
    argv.add("run");
    argv.addAll(
        containerFactory.forWorkspace(repoId, workspaceId, rowId, branch, parent).toRunArgv());

    ExecResult result = runCapturing(null, argv, onLine);
    if (result.exitCode() != 0) {
      throw new InternalServerErrorException(
          "Failed to start container " + name + ": " + result.output());
    }
    return name;
  }

  /**
   * Pull the workspace image when the local daemon does not hold it.
   *
   * <p><b>Why this exists at all.</b> {@code qits.workspace.image} no longer names a hand-built
   * local tag. It is a registry reference at a pinned version, so a host that has never pulled it —
   * or a host a platform unwrap has just swept — holds nothing under that name. Left to itself
   * {@code docker run} pulls such a reference on its own: unbounded, invisible on the tap, and on a
   * bad pin failing in a sentence nobody reads as "the pin is wrong".
   *
   * <p><b>Inspect first, and the inspect never pulls.</b> That is the whole reason it is two calls:
   * the ordinary case is an image already present, and it must cost nothing on the wire. A present
   * image is also never re-checked against the registry — the pin is a version, so what is local
   * under that name IS the release, and a launch is not the place to re-litigate it.
   *
   * <p><b>The failure names the image and the registry, and it throws.</b> A wrong pin is the
   * expected failure here — the tag is written by a release train and the image is published by
   * another repository's pipeline, so the two can disagree — and the one thing it must not do is
   * hang or degrade to a stale local image. Bounded by {@code
   * qits.workspace.image-pull-timeout-ms}, which is what stops a registry that accepts a connection
   * and then says nothing from parking the request thread.
   */
  void ensureImage(java.util.function.Consumer<String> onLine) {
    String image = containerFactory.image();
    if (runCapturing(null, List.of(runtime, "image", "inspect", image)).exitCode() == 0) {
      return;
    }
    LOG.infof("Pulling workspace image %s — the local %s daemon does not hold it", image, runtime);
    ExecResult pull =
        runCapturing(
            null,
            List.of(runtime, "pull", image),
            onLine,
            Duration.ofMillis(imagePullTimeoutMs));
    if (pull.exitCode() != 0) {
      throw new InternalServerErrorException(
          "Could not pull the workspace image "
              + image
              + " from "
              + registryOf(image)
              + " — check that qits.workspace.image names a published tag: "
              + pull.output());
    }
  }

  /**
   * The registry a reference is pulled from, for the failure message. Docker's own rule: the leading
   * path segment is a host when it carries a dot or a port, or when it is literally {@code
   * localhost}; anything else is a repository namespace on the default registry.
   */
  static String registryOf(String image) {
    int slash = image == null ? -1 : image.indexOf('/');
    if (slash < 0) {
      return "docker.io";
    }
    String head = image.substring(0, slash);
    boolean host = head.indexOf('.') >= 0 || head.indexOf(':') >= 0 || "localhost".equals(head);
    return host ? head : "docker.io";
  }

  @Override
  public List<String> execArgv(
      String container, boolean tty, String workdir, Map<String, String> env) {
    List<String> argv = new ArrayList<>();
    argv.add(runtime);
    argv.add("exec");
    argv.add(tty ? "-it" : "-i");
    if (workdir != null && !workdir.isBlank()) {
      argv.add("-w");
      argv.add(workdir);
    }
    if (env != null) {
      for (Map.Entry<String, String> e : env.entrySet()) {
        argv.add("-e");
        argv.add(e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue()));
      }
    }
    argv.add(container);
    return argv;
  }

  @Override
  public ProxyOrigin resolveTarget(String container, int containerPort) {
    // bridge-ip: the container's IP on the shared network, host-routable on plain-Linux docker.
    if ("bridge-ip".equals(containerNetwork)) {
      String ip = bridgeIp(container);
      return ip == null ? null : new ProxyOrigin(ip, containerPort);
    }
    // network (default): qits shares qits-net with the container, so its DNS name resolves and the
    // real container port is reachable directly — no host publish, no create-time port constraint.
    return new ProxyOrigin(container, containerPort);
  }

  /** The container's IPv4 on its first attached network ({@code docker inspect}), or null. */
  private String bridgeIp(String container) {
    ExecResult result =
        runCapturing(
            null,
            List.of(
                runtime,
                "inspect",
                "-f",
                "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                container));
    if (result.exitCode() != 0) {
      return null;
    }
    String ip = result.output().trim();
    return ip.isEmpty() ? null : ip;
  }

  @Override
  public boolean exists(String container) {
    return runCapturing(null, List.of(runtime, "container", "inspect", container)).exitCode() == 0;
  }

  @Override
  public boolean isRunning(String container) {
    ExecResult result =
        runCapturing(
            null, List.of(runtime, "container", "inspect", "-f", "{{.State.Running}}", container));
    // A missing container inspects non-zero; a present one prints "true"/"false" for its run state.
    return result.exitCode() == 0 && "true".equals(result.output().trim());
  }

  @Override
  public void start(String container) {
    ExecResult result = runCapturing(null, List.of(runtime, "start", container));
    if (result.exitCode() != 0) {
      throw new InternalServerErrorException(
          "Failed to start container " + container + ": " + result.output());
    }
  }

  @Override
  public void stop(String container) {
    ExecResult result = runCapturing(null, List.of(runtime, "stop", container));
    if (result.exitCode() != 0) {
      LOG.debugf("Failed to stop container %s: %s", container, result.output());
    }
  }

  @Override
  public void rm(String container) {
    ExecResult result = runCapturing(null, List.of(runtime, "rm", "-f", container));
    if (result.exitCode() != 0) {
      LOG.debugf("Failed to remove container %s: %s", container, result.output());
    }
  }

  @Override
  public void restart(String container) {
    runCapturing(null, List.of(runtime, "restart", container));
  }

  @Override
  public List<ContainerInfo> listWorkspaceContainers(String repoId) {
    ExecResult result =
        runCapturing(
            null,
            List.of(
                runtime,
                "ps",
                "-a",
                "--filter",
                "label=qits.repository=" + repoId,
                "--format",
                // The trailing qits.worktree column is a one-release back-compat read: containers
                // provisioned before the worktree→workspace rename carry the old label, so a
                // reconcile can still adopt them instead of forcing a recreate. Remove once no
                // pre-rename containers remain.
                "{{.Names}}\t{{.Label \"qits.workspace\"}}\t{{.Label \"qits.branch\"}}\t{{.Label"
                    + " \"qits.parent\"}}\t{{.Label \"qits.worktree\"}}\t{{.State}}"));
    if (result.exitCode() != 0) {
      LOG.warnf("Failed to list containers for repo %s: %s", repoId, result.output());
      return List.of();
    }
    List<ContainerInfo> infos = new ArrayList<>();
    for (String line : result.output().split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      String[] parts = line.split("\t", -1);
      String name = parts.length > 0 ? parts[0] : "";
      String workspaceId = parts.length > 1 ? parts[1] : "";
      String branch = parts.length > 2 ? emptyToNull(parts[2]) : null;
      String parent = parts.length > 3 ? emptyToNull(parts[3]) : null;
      String legacyWorkspaceId = parts.length > 4 ? parts[4] : "";
      // `docker ps -a` lists stopped containers too (reconcile/adoption need them); {{.State}} is
      // "running" only when the container is actually up — a deliberate stop leaves it "exited".
      boolean running = parts.length > 5 && "running".equals(parts[5]);
      if (workspaceId.isBlank()) {
        workspaceId = legacyWorkspaceId; // pre-rename container labelled qits.worktree
      }
      if (!workspaceId.isBlank()) {
        infos.add(new ContainerInfo(name, workspaceId, branch, parent, running));
      }
    }
    return infos;
  }

  // --- Per-workspace /workspace volumes -------------------------------------------------------

  @Override
  public String workspaceVolumeName(String workspaceId) {
    return containerFactory.workspaceVolumeName(workspaceId);
  }

  /** {@link #ensureWorkspaceVolume} only when the persistent-workspace flag is on. */
  private void ensureWorkspaceVolumeIfPersistent(
      String repoId, String workspaceId, String branch, String parent) {
    if (containerFactory.persistWorkspace()) {
      ensureWorkspaceVolume(repoId, workspaceId, branch, parent);
    }
  }

  @Override
  public void ensureWorkspaceVolume(
      String repoId, String workspaceId, String branch, String parent) {
    String name = containerFactory.workspaceVolumeName(workspaceId);
    List<String> cmd = new ArrayList<>(List.of(runtime, "volume", "create"));
    // Labels are set only at create time; docker ignores label changes on an existing volume, so a
    // later branch rename leaves qits.branch stale — acceptable, the stable qits.workspace is the
    // reconcile key. The factory owns the label set (it resolves qits.project and mirrors the
    // container labels).
    containerFactory
        .workspaceVolumeLabels(repoId, workspaceId, branch, parent)
        .forEach(
            (k, v) -> {
              cmd.add("--label");
              cmd.add(k + "=" + v);
            });
    cmd.add(name);
    ExecResult result = runCapturing(null, cmd);
    if (result.exitCode() != 0) {
      LOG.warnf("Could not ensure workspace volume '%s': %s", name, result.output());
    }
  }

  @Override
  public void removeWorkspaceVolume(String workspaceId) {
    String name = containerFactory.workspaceVolumeName(workspaceId);
    // Best-effort: the container must already be rm'd (docker refuses an in-use volume) and a
    // missing volume is fine — both just log at debug and continue.
    ExecResult result = runCapturing(null, List.of(runtime, "volume", "rm", name));
    if (result.exitCode() != 0) {
      LOG.debugf("Failed to remove workspace volume %s: %s", name, result.output());
    }
  }

  @Override
  public List<VolumeInfo> listWorkspaceVolumes() {
    ExecResult ls =
        runCapturing(
            null,
            List.of(
                runtime,
                "volume",
                "ls",
                "--filter",
                "label=qits.managed=workspace-volume",
                "--format",
                "{{.Name}}"));
    if (ls.exitCode() != 0) {
      LOG.warnf("Failed to list workspace volumes: %s", ls.output());
      return List.of();
    }
    List<VolumeInfo> infos = new ArrayList<>();
    for (String name : ls.output().split("\n")) {
      if (name.isBlank()) {
        continue;
      }
      // Read the identity labels back one inspect at a time (the ls --format above can't emit
      // labels portably across docker/podman); a volume whose labels can't be read is skipped.
      ExecResult inspect =
          runCapturing(
              null,
              List.of(
                  runtime,
                  "volume",
                  "inspect",
                  "--format",
                  "{{index .Labels \"qits.project\"}}\t{{index .Labels \"qits.repository\"}}\t"
                      + "{{index .Labels \"qits.workspace\"}}\t{{index .Labels \"qits.branch\"}}",
                  name));
      if (inspect.exitCode() != 0) {
        continue;
      }
      String[] parts = inspect.output().trim().split("\t", -1);
      String projectId = parts.length > 0 ? emptyToNull(parts[0]) : null;
      String repoId = parts.length > 1 ? emptyToNull(parts[1]) : null;
      String workspaceId = parts.length > 2 ? parts[2] : "";
      String branch = parts.length > 3 ? emptyToNull(parts[3]) : null;
      if (!workspaceId.isBlank()) {
        infos.add(new VolumeInfo(name, projectId, repoId, workspaceId, branch));
      }
    }
    return infos;
  }

  private static String emptyToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private ExecResult runCapturing(Path cwd, List<String> command) {
    return runCapturing(cwd, command, null);
  }

  private ExecResult runCapturing(
      Path cwd, List<String> command, java.util.function.Consumer<String> onLine) {
    return runCapturing(cwd, command, onLine, null);
  }

  /**
   * Runs the command capturing its combined output; a non-null {@code onLine} additionally receives
   * each line as it arrives — the live tap the technical-process log stream rides on. Tap failures
   * are swallowed so a broken consumer can never fail the underlying docker verb.
   *
   * <p>A non-null {@code timeout} bounds the <b>whole</b> invocation, and only the calls that reach
   * the network pass one. The bound cannot be enforced on this thread: a registry that accepts the
   * connection and then says nothing blocks in {@code readLine()} and never in {@code waitFor()}, so
   * the drain runs on its own thread and {@code destroyForcibly} is what unblocks it. That is
   * {@code gitmirror}'s {@code GitCli} discipline, arrived at the same way and deliberately not
   * shared with it — that class forces {@code GIT_TERMINAL_PROMPT} and an English locale into every
   * process it spawns, which is git's contract rather than docker's.
   *
   * <p>Package-private, and the single place this class starts a process, so a test can replace the
   * whole runtime by overriding one method.
   */
  ExecResult runCapturing(
      Path cwd,
      List<String> command,
      java.util.function.Consumer<String> onLine,
      Duration timeout) {
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.redirectErrorStream(true);
    try {
      Process p = pb.start();
      if (timeout == null) {
        String output = drain(p, onLine);
        return new ExecResult(p.waitFor(), output);
      }
      StringBuilder collected = new StringBuilder();
      Thread drain =
          new Thread(
              () -> {
                try {
                  collected.append(drain(p, onLine));
                } catch (Exception ignored) {
                  // the pipe closing under a destroyForcibly is this thread's expected end
                }
              },
              "docker-exec-drain");
      drain.setDaemon(true);
      drain.start();
      if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        p.destroyForcibly();
        drain.join(DRAIN_JOIN.toMillis());
        return new ExecResult(-1, "timed out after " + timeout + ": " + String.join(" ", command));
      }
      drain.join(DRAIN_JOIN.toMillis());
      return new ExecResult(p.exitValue(), collected.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ExecResult(-1, "interrupted");
    } catch (Exception e) {
      return new ExecResult(-1, e.getMessage());
    }
  }

  /** How long to wait for the drain thread once the process has settled. */
  private static final Duration DRAIN_JOIN = Duration.ofSeconds(5);

  private static String drain(Process p, java.util.function.Consumer<String> onLine)
      throws Exception {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      if (onLine == null) {
        return reader.lines().collect(Collectors.joining("\n"));
      }
      StringBuilder collected = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        if (collected.length() > 0) {
          collected.append('\n');
        }
        collected.append(line);
        try {
          onLine.accept(line);
        } catch (RuntimeException ignored) {
          // the tap is observational only
        }
      }
      return collected.toString();
    }
  }
}
