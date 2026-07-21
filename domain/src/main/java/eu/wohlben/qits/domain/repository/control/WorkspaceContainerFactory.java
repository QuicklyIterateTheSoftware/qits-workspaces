package eu.wohlben.qits.domain.repository.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produces a {@link WorkspaceContainer} already seeded with the cross-cutting configuration every
 * workspace container must have — the container-creation analog of {@code CodingAgentFactory}.
 * Routing all creation through {@link #forWorkspace} makes it structurally impossible to start a
 * workspace container without the shared credential volume, the {@code qits.*} reconciliation
 * labels, the docker-host alias and the host uid. {@link DockerExecutor#run} is the sole caller; it
 * only prepends the runtime binary + {@code run} and executes the rendered argv.
 */
@ApplicationScoped
public class WorkspaceContainerFactory {

  @ConfigProperty(name = "qits.workspace.image", defaultValue = "qits/workspace:latest")
  String image;

  /**
   * The shared Docker network every workspace container joins (and qits is on), so qits reaches a
   * container's ports by its DNS name with no host-port publishing. Created if absent at startup by
   * {@link DockerExecutor}.
   */
  @ConfigProperty(name = "qits.workspace.network", defaultValue = "qits-net")
  String network;

  /**
   * The shared named volume holding the coding agent's home ({@code ~/.claude} — the one-time OAuth
   * login). Mounted read/write into every workspace container so an in-container {@code claude} can
   * authenticate; blank disables the mount. See {@code docker/workspace/agent-login.sh}.
   */
  @ConfigProperty(name = "qits.workspace.claude-volume", defaultValue = "qits_shared_dot_claude")
  String claudeVolume;

  /** Where {@link #claudeVolume} mounts (and where agent launches point {@code HOME}). */
  @ConfigProperty(name = "qits.workspace.claude-mount", defaultValue = "/claude-home")
  String claudeMount;

  /**
   * Shared build caches mounted into every workspace container (and qits' own devcontainer), so a
   * dependency downloaded by one build is reused by all — the Maven local repo and the pnpm store.
   * Blank disables the mount. Mount points are fixed ({@code /caches/m2}, {@code /caches/pnpm},
   * both {@code chmod 0777} in the image) and Maven/pnpm are pointed at them via {@code MAVEN_OPTS}
   * / {@code npm_config_store_dir}.
   */
  @ConfigProperty(name = "qits.workspace.maven-volume", defaultValue = "qits_shared_m2")
  String mavenVolume;

  @ConfigProperty(name = "qits.workspace.pnpm-volume", defaultValue = "qits_shared_pnpm")
  String pnpmVolume;

  /**
   * The IANA timezone every workspace container runs in ({@code TZ} env, honored by glibc, the JVM
   * and node — tzdata is in the image). Blank/absent (the default) inherits qits' own zone, so
   * wall-clock output in the container (logs, {@code date}, commit timestamps) matches the
   * environment qits runs in — which the devcontainer in turn inherits from the host via compose.
   * Containers already share the host kernel clock; only the rendered zone can differ. Optional
   * because SmallRye treats an empty property value as "no value" and would fail a plain String.
   */
  @ConfigProperty(name = "qits.workspace.timezone")
  Optional<String> timezone;

  /**
   * Hard memory cap for every workspace container ({@code --memory} + {@code --memory-swap} set to
   * the same value, so a container can neither exceed the cap nor swap-thrash the host past it).
   * Without it a container sees the whole host's RAM and every JVM inside sizes its default heap
   * against that — a dev daemon (Maven launcher JVM + forked dev JVM + node dev server) can then
   * OOM the entire host
   * (docs/issues/resolved/2026-07-21_workspace-container-unbounded-memory-host-oom.md). With the
   * cgroup limit in place the JVMs size against it automatically (container support is default-on),
   * so no per-tool {@code -Xmx} plumbing is needed. Blank/absent disables the cap; the shipped
   * default is {@code 4g} (service/cli application.properties). Optional because SmallRye treats an
   * empty property value as "no value".
   */
  @ConfigProperty(name = "qits.workspace.memory-limit")
  Optional<String> memoryLimit;

  /**
   * Process/thread cap ({@code --pids-limit}, fork-bomb guard). Blank/absent (default) disables.
   */
  @ConfigProperty(name = "qits.workspace.pids-limit")
  Optional<String> pidsLimit;

  /** CPU cap ({@code --cpus}). Blank/absent (default) disables. */
  @ConfigProperty(name = "qits.workspace.cpus")
  Optional<String> cpus;

  @Inject GitIdentity gitIdentity;

  static final String MAVEN_MOUNT = "/caches/m2";
  static final String PNPM_MOUNT = "/caches/pnpm";

  /**
   * The shared credential volume name (blank when the mount is disabled) — the single source of
   * truth {@link DockerExecutor} reuses for its startup {@code docker volume create}.
   */
  public String claudeVolume() {
    return claudeVolume;
  }

  /** The shared Maven-repo volume name (blank when disabled) — ensured at startup by executor. */
  public String mavenVolume() {
    return mavenVolume;
  }

  /** The shared pnpm-store volume name (blank when disabled) — ensured at startup by executor. */
  public String pnpmVolume() {
    return pnpmVolume;
  }

  /** The shared network name — the single source of truth {@link DockerExecutor} ensures exists. */
  public String network() {
    return network;
  }

  /**
   * A {@link WorkspaceContainer} seeded for {@code workspaceId} of {@code repoId}: its
   * deterministic name, the host uid, the four {@code qits.*} labels startup reconciliation reads
   * back, the {@code host.docker.internal} alias Linux needs, the shared {@code qits-net} network,
   * the configured git commit identity as {@code GIT_*} env ({@link GitIdentity}), the shared
   * credential + build-cache volumes (whenever configured), the configured resource limits (memory
   * hard cap, pids, cpus — whenever configured), the image, and {@code sleep infinity} as the
   * command. Everything safety-critical is already in place; the caller may keep chaining but need
   * not.
   */
  public WorkspaceContainer forWorkspace(
      String repoId, String workspaceId, String branch, String parent) {
    WorkspaceContainer container =
        new WorkspaceContainer()
            .name(containerName(workspaceId, repoId))
            .user(Long.toString(hostUid()))
            .label("qits.repository", repoId)
            .label("qits.workspace", workspaceId)
            .label("qits.branch", branch == null ? "" : branch)
            .label("qits.parent", parent == null ? "" : parent)
            // Linux needs this for host.docker.internal to resolve to the docker bridge gateway;
            // qits
            // controls container creation, so it is always set.
            .addHost("host.docker.internal:host-gateway")
            // Join the shared network so qits reaches the container's ports by DNS name (no -p).
            .network(network)
            // Same timezone as qits (host -> devcontainer -> workspace container), so wall-clock
            // output agrees everywhere. The kernel clock is shared already; TZ is the only delta.
            .env("TZ", timezone());
    // Resource limits (opt-out): without a memory cap, every JVM in the container sizes its heap
    // against the whole host's RAM and a dev daemon can OOM the host. Blank config disables a cap.
    memoryLimit.filter(v -> !v.isBlank()).ifPresent(container::memory);
    pidsLimit.filter(v -> !v.isBlank()).ifPresent(container::pidsLimit);
    cpus.filter(v -> !v.isBlank()).ifPresent(container::cpus);
    // The commit identity, as container-level env so *every* git process in the container — qits'
    // own verbs, the coding agent, actions, ad-hoc shells — inherits it regardless of cwd or
    // .git/config (identity env beats every git config level).
    gitIdentity.envMap().forEach(container::env);
    // The shared credential volume so an in-container `claude` can read the one-time OAuth login.
    // Mounted read/write on every workspace container (agent and daemon share the container), so
    // any
    // command in the container can read the token off the volume — the accepted trade for the
    // shared-login model
    // (docs/epics/qits-coding-agents/features/2026-07-04_container-agent-sessions.md).
    if (claudeVolume != null && !claudeVolume.isBlank()) {
      container.volume(claudeVolume, claudeMount);
      // Point every in-container `claude` at the shared credential dir regardless of HOME. The
      // image
      // sets HOME=/workspace (container-local), so without this a `claude` that doesn't override
      // HOME
      // (an ad-hoc bash `claude`, or any missed code path) would store its login under
      // /workspace/.claude — invisible to other containers. As a container env it is inherited by
      // every `docker exec`, so cross-container persistence no longer relies on each launcher
      // remembering the HOME overlay.
      container.env("CLAUDE_CONFIG_DIR", claudeMount + "/.claude");
      // Same for Kimi Code (the second harness —
      // docs/epics/qits-coding-agents/feature-ideas/kimi-code-harness.md):
      // KIMI_CODE_HOME relocates its entire data root (config.toml, credentials, sessions) onto the
      // volume. Without it an in-container kimi would default to ~/.kimi-code =
      // /workspace/.kimi-code
      // (the image's HOME) — the clone, container-local and invisible to every other container.
      container.env("KIMI_CODE_HOME", claudeMount + "/.kimi-code");
    }
    // Shared build caches (Maven repo + pnpm store), the same named volumes qits' devcontainer
    // mounts — so a dependency fetched by one build (a fixture `./mvnw`, an action, the agent, or
    // qits itself) is reused by every other container. Point the tools at the fixed mount paths via
    // env, inherited by every `docker exec` (HOME is /workspace, so the defaults would otherwise
    // land in the clone and never be shared).
    if (mavenVolume != null && !mavenVolume.isBlank()) {
      container.volume(mavenVolume, MAVEN_MOUNT);
      container.env("MAVEN_OPTS", "-Dmaven.repo.local=" + MAVEN_MOUNT);
    }
    if (pnpmVolume != null && !pnpmVolume.isBlank()) {
      container.volume(pnpmVolume, PNPM_MOUNT);
      container.env("npm_config_store_dir", PNPM_MOUNT + "/store");
    }
    return container.image(image).command("sleep", "infinity");
  }

  /**
   * The deterministic container name for a workspace — mirrors {@link
   * ContainerRuntime#containerName}. The short repo prefix keeps the name readable and well under
   * docker's length cap while staying effectively unique per repo.
   */
  private String containerName(String workspaceId, String repoId) {
    String shortRepo = repoId.length() > 8 ? repoId.substring(0, 8) : repoId;
    return "qits-ws-" + workspaceId + "-" + shortRepo;
  }

  /** The configured zone, or qits' own default zone when blank ({@code TZ}-aware via the JVM). */
  private String timezone() {
    return timezone.filter(tz -> !tz.isBlank()).orElseGet(() -> ZoneId.systemDefault().getId());
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
