package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.error.InternalServerErrorException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
   * Assembles the {@code docker run} argv (with the always-on cross-cutting config — credential
   * volume, {@code qits.*} labels, host alias, host uid). This executor only prepends the runtime
   * binary + {@code run} and shells it out.
   */
  @Inject WorkspaceContainerFactory containerFactory;

  /**
   * Create the shared credential volume once at startup so it exists before any worktree container
   * mounts it — and so an operator can run the one-time login before the first worktree is created.
   * Best-effort: a missing/broken runtime just logs, exactly like the rest of this executor.
   */
  void onStart(@Observes StartupEvent event) {
    ensureClaudeVolume();
  }

  /** Idempotent {@code docker volume create}; no-op when the volume name is blank. */
  void ensureClaudeVolume() {
    String claudeVolume = containerFactory.claudeVolume();
    if (claudeVolume == null || claudeVolume.isBlank()) {
      return;
    }
    ExecResult result = runCapturing(null, List.of(runtime, "volume", "create", claudeVolume));
    if (result.exitCode() != 0) {
      LOG.warnf(
          "Could not ensure shared agent credential volume '%s' (agent auth may be unavailable): %s",
          claudeVolume, result.output());
    }
  }

  @Override
  public String containerName(String worktreeId, String repoId) {
    // repoId is a UUID; the short prefix keeps the name readable and well under docker's length
    // cap while still being effectively unique per repo. Prefix keeps the first char alphanumeric.
    String shortRepo = repoId.length() > 8 ? repoId.substring(0, 8) : repoId;
    return "qits-wt-" + worktreeId + "-" + shortRepo;
  }

  @Override
  public String run(
      String repoId,
      String worktreeId,
      String branch,
      String parent,
      Collection<Integer> publishPorts) {
    String name = containerName(worktreeId, repoId);
    // The factory owns the argv shape and the always-on cross-cutting config (credential volume,
    // qits.* labels, host alias, host uid); this executor only prepends the runtime + `run` verb.
    List<String> argv = new ArrayList<>();
    argv.add(runtime);
    argv.add("run");
    argv.addAll(
        containerFactory.forWorktree(repoId, worktreeId, branch, parent, publishPorts).toRunArgv());

    ExecResult result = runCapturing(null, argv);
    if (result.exitCode() != 0) {
      throw new InternalServerErrorException(
          "Failed to start container " + name + ": " + result.output());
    }
    return name;
  }

  @Override
  public ExecResult exec(
      String container, String workdir, Map<String, String> env, String... argv) {
    List<String> command = new ArrayList<>(execArgv(container, false, workdir, env));
    for (String arg : argv) {
      command.add(arg);
    }
    return runCapturing(null, command);
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
  public Integer hostPort(String container, int containerPort) {
    ExecResult result =
        runCapturing(null, List.of(runtime, "port", container, containerPort + "/tcp"));
    if (result.exitCode() != 0) {
      return null;
    }
    // One line per bound address, e.g. "127.0.0.1:32768" (IPv6 lines look like "[::1]:32768").
    for (String line : result.output().split("\n")) {
      int colon = line.lastIndexOf(':');
      if (colon < 0) {
        continue;
      }
      try {
        return Integer.parseInt(line.substring(colon + 1).trim());
      } catch (NumberFormatException ignored) {
        // fall through to the next line
      }
    }
    return null;
  }

  @Override
  public boolean exists(String container) {
    return runCapturing(null, List.of(runtime, "container", "inspect", container)).exitCode() == 0;
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
  public List<ContainerInfo> listWorktreeContainers(String repoId) {
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
                "{{.Names}}\t{{.Label \"qits.worktree\"}}\t{{.Label \"qits.branch\"}}\t{{.Label"
                    + " \"qits.parent\"}}"));
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
      String worktreeId = parts.length > 1 ? parts[1] : "";
      String branch = parts.length > 2 ? emptyToNull(parts[2]) : null;
      String parent = parts.length > 3 ? emptyToNull(parts[3]) : null;
      if (!worktreeId.isBlank()) {
        infos.add(new ContainerInfo(name, worktreeId, branch, parent));
      }
    }
    return infos;
  }

  private static String emptyToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private ExecResult runCapturing(Path cwd, List<String> command) {
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.redirectErrorStream(true);
    try {
      Process p = pb.start();
      String output;
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
        output = reader.lines().collect(Collectors.joining("\n"));
      }
      int exitCode = p.waitFor();
      return new ExecResult(exitCode, output);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ExecResult(-1, "interrupted");
    } catch (Exception e) {
      return new ExecResult(-1, e.getMessage());
    }
  }
}
