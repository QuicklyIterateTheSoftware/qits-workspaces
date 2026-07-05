package eu.wohlben.qits.domain.repository.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produces a {@link WorkspaceContainer} already seeded with the cross-cutting configuration every
 * worktree container must have — the container-creation analog of {@code CodingAgentFactory}.
 * Routing all creation through {@link #forWorktree} makes it structurally impossible to start a
 * workspace container without the shared credential volume, the {@code qits.*} reconciliation
 * labels, the docker-host alias and the host uid. {@link DockerExecutor#run} is the sole caller; it
 * only prepends the runtime binary + {@code run} and executes the rendered argv.
 */
@ApplicationScoped
public class WorkspaceContainerFactory {

  @ConfigProperty(name = "qits.workspace.image", defaultValue = "qits/workspace:latest")
  String image;

  /**
   * The shared named volume holding the coding agent's home ({@code ~/.claude} — the one-time OAuth
   * login). Mounted read/write into every worktree container so an in-container {@code claude} can
   * authenticate; blank disables the mount. See {@code docker/workspace/agent-login.sh}.
   */
  @ConfigProperty(name = "qits.workspace.claude-volume", defaultValue = "qits_shared_dot_claude")
  String claudeVolume;

  /** Where {@link #claudeVolume} mounts (and where agent launches point {@code HOME}). */
  @ConfigProperty(name = "qits.workspace.claude-mount", defaultValue = "/claude-home")
  String claudeMount;

  /**
   * The shared credential volume name (blank when the mount is disabled) — the single source of
   * truth {@link DockerExecutor} reuses for its startup {@code docker volume create}.
   */
  public String claudeVolume() {
    return claudeVolume;
  }

  /**
   * A {@link WorkspaceContainer} seeded for {@code worktreeId} of {@code repoId}: its deterministic
   * name, the host uid, the four {@code qits.*} labels startup reconciliation reads back, the
   * {@code host.docker.internal} alias Linux needs, the shared credential volume (whenever
   * configured), every declared publish port, the image, and {@code sleep infinity} as the command.
   * Everything safety-critical is already in place; the caller may keep chaining but need not.
   */
  public WorkspaceContainer forWorktree(
      String repoId,
      String worktreeId,
      String branch,
      String parent,
      Collection<Integer> publishPorts) {
    WorkspaceContainer container =
        new WorkspaceContainer()
            .name(containerName(worktreeId, repoId))
            .user(Long.toString(hostUid()))
            .label("qits.repository", repoId)
            .label("qits.worktree", worktreeId)
            .label("qits.branch", branch == null ? "" : branch)
            .label("qits.parent", parent == null ? "" : parent)
            // Linux needs this for host.docker.internal to resolve to the docker bridge gateway;
            // qits
            // controls container creation, so it is always set.
            .addHost("host.docker.internal:host-gateway");
    // The shared credential volume so an in-container `claude` can read the one-time OAuth login.
    // Mounted read/write on every worktree container (agent and daemon share the container), so any
    // command in the container can read the token off the volume — the accepted trade for the
    // shared-login model (docs/features/2026-07-04_container-agent-sessions.md).
    if (claudeVolume != null && !claudeVolume.isBlank()) {
      container.volume(claudeVolume, claudeMount);
    }
    // Ephemeral localhost publishing for web-viewable daemon ports (see ContainerRuntime#run).
    return container.publishPorts(publishPorts).image(image).command("sleep", "infinity");
  }

  /**
   * The deterministic container name for a worktree — mirrors {@link
   * ContainerRuntime#containerName}. The short repo prefix keeps the name readable and well under
   * docker's length cap while staying effectively unique per repo.
   */
  private String containerName(String worktreeId, String repoId) {
    String shortRepo = repoId.length() > 8 ? repoId.substring(0, 8) : repoId;
    return "qits-wt-" + worktreeId + "-" + shortRepo;
  }

  /**
   * The host uid the container runs as, so cloned {@code /workspace} files are owned by the user.
   */
  private long hostUid() {
    try {
      Object uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid");
      return ((Number) uid).longValue();
    } catch (Exception e) {
      // Fall back to a sane default; the container just won't match the host uid.
      return 1000L;
    }
  }
}
