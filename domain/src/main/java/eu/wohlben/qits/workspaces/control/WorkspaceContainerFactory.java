package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.net.URI;
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
 * labels, the docker-host alias and the host uid. {@code containershost/WorkspaceContainers} is the
 * sole caller; it turns what this describes into the orchestrator's wire spec.
 */
@ApplicationScoped
public class WorkspaceContainerFactory {

  /**
   * The image every workspace container runs — registry-qualified and pinned to a released version
   * ({@code localhost:8081/qits/workspace:<calver>}). The value ships in this library's {@code
   * META-INF/microprofile-config.properties}, which carries the reasoning for both halves of that
   * shape, and which is also the single file the release train rewrites.
   *
   * <p><b>No {@code defaultValue}</b>, deliberately, and unlike every other key on this class. A
   * default here would be a second copy of the pin that the train does not move, so it would be
   * stale from the first bump onward — and it would be a stale copy of the exact thing the pin
   * exists to end: an unqualified {@code qits/workspace:latest} resolving to whatever a host
   * happens to have lying in its local image store. A deployment that loses the property should
   * fail at startup and say which key is missing, not quietly launch a hand-built tag. Same
   * arrangement, same reason, as {@code ReleaseIntegrator.entryBranch}.
   */
  @ConfigProperty(name = "qits.workspace.image")
  String image;

  /**
   * The shared Docker network every workspace container joins (and qits is on), so qits reaches a
   * container's ports by its DNS name with no host-port publishing. Creating it is the bootstrap's
   * job — the orchestrator only probes for it, and this service no longer creates networks at all.
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
   * Name prefix for the per-workspace {@code /workspace} volume — {@code prefix + workspaceId} (the
   * stable {@code workspace_id}, safe as a docker volume name and 1:1 with the branch).
   * Branch/repo/ project ride as labels, not the name, so a rename never strands the volume. See
   * docs/epics/qits-workspaces/features/2026-07-25_persistent-workspace-volume.md.
   */
  @ConfigProperty(name = "qits.workspace.workspace-volume-prefix", defaultValue = "qits_workspace_")
  String workspaceVolumePrefix;

  /**
   * Feature flag: when {@code true} (default), {@code /workspace} is a per-workspace named volume
   * that survives container recreation; when {@code false}, it reverts to the container's ephemeral
   * writable layer (no {@code -v /workspace}, no per-workspace volume lifecycle) — a reversible
   * per-deployment kill switch while the change beds in.
   */
  @ConfigProperty(name = "qits.workspace.persist-workspace", defaultValue = "true")
  boolean persistWorkspace;

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
   * against that — a dev-server service (Maven launcher JVM + forked dev JVM + node dev server) can
   * then OOM the entire host
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

  /**
   * The provision-time bootstrap kill switch, forwarded to the in-container daemon (which self-runs
   * the chain on boot — docs/epics/qits-workspace-daemon/ Part 3). Mirrors the host-side {@code
   * qits.bootstrap.autorun-enabled} default; when false the daemon skips the chain.
   */
  @ConfigProperty(name = "qits.bootstrap.autorun-enabled", defaultValue = "true")
  boolean bootstrapAutorunEnabled;

  /**
   * The auto-push kill switch, forwarded to the in-container daemon which pushes committed work to
   * origin on its own as it observes commits (docs/epics/qits-workspace-daemon/ bidirectional
   * auto-sync). When false the daemon never auto-pushes; incoming (host-triggered) pulls are
   * unaffected.
   */
  @ConfigProperty(name = "qits.workspace.auto-push.enabled", defaultValue = "true")
  boolean autoPushEnabled;

  /**
   * Service (dev-server) supervision knobs, forwarded to the in-container daemon which supervises
   * them itself (docs/epics/qits-workspace-daemon/ Part 4). Mirror the host-side {@code
   * qits.services.*} so host projection and container supervision agree: the auto-start kill
   * switch, the ready grace (no readyPattern), the restart backoff bounds, and the stop grace.
   */
  @ConfigProperty(name = "qits.services.autostart-enabled", defaultValue = "true")
  boolean servicesAutostartEnabled;

  @ConfigProperty(name = "qits.services.ready-grace-ms", defaultValue = "10000")
  long serviceReadyGraceMs;

  @ConfigProperty(name = "qits.services.restart-backoff-initial-ms", defaultValue = "1000")
  long serviceBackoffInitialMs;

  @ConfigProperty(name = "qits.services.restart-backoff-max-ms", defaultValue = "30000")
  long serviceBackoffMaxMs;

  @ConfigProperty(name = "qits.services.stop-grace-ms", defaultValue = "5000")
  long serviceStopGraceMs;

  @Inject GitIdentity gitIdentity;

  /**
   * Resolves the repository's project-scoped git-host name so the in-container workspace-daemon can
   * self-clone name-addressed ({@code /git/<projectId>/<name>}) — the addressing that lets
   * committed relative submodule urls resolve natively (docs/epics/qits-workspace-daemon/ Part 1).
   * Injected as {@code QITS_WORKSPACE_DAEMON_PROJECT_ID}/{@code …_REPO_NAME}.
   *
   * <p>Optional: the ordinary production path reads the same pair from {@link RepositoryLookup}; a
   * resolver can override it for an embedding that owns a different address registry.
   */
  @Inject Instance<RepositoryAddressResolver> nameResolver;

  /**
   * The owning project id, for {@code QITS_WORKSPACE_DAEMON_PROJECT_ID} and the {@code
   * qits.project} labels. No {@link RepositoryAddressResolver} implementation exists in the
   * deployable, so {@link RepositoryLookup} supplies both this id and the repository name. An
   * {@code Instance<>} lets the hand-built unit-test factory leave the registry empty.
   */
  @Inject Instance<RepositoryLookup> repositories;

  /**
   * The credential this workspace's container holds toward the platform, injected as {@code
   * QITS_COMMISSIONED_CLIENT_ID}/{@code …_SECRET} below. A lookup rather than an argument, and
   * optional rather than required — {@link WorkspaceCredentials} carries both reasons.
   */
  @Inject Instance<WorkspaceCredentials> credentials;

  /** The repo's project-scoped name, from an override resolver or the repository registry. */
  private Optional<RepositoryAddressResolver.ProjectScopedName> scopedName(String repoId) {
    if (nameResolver.isResolvable()) {
      Optional<RepositoryAddressResolver.ProjectScopedName> resolved =
          nameResolver.get().resolve(repoId);
      if (resolved.isPresent()) {
        return resolved;
      }
    }
    if (!repositories.isResolvable()) {
      return Optional.empty();
    }
    try {
      return repositories
          .get()
          .find(repoId)
          .filter(view -> present(view.projectId()) && present(view.name()))
          .map(
              view ->
                  new RepositoryAddressResolver.ProjectScopedName(
                      view.projectId(), view.name()));
    } catch (RuntimeException e) {
      return Optional.empty(); // address enrichment never prevents container creation
    }
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * The commissioned pair for this workspace, or none. A lookup failure costs the credential and
   * never the container: by the time this runs the provision has already decided a credential is in
   * hand (or that there is none to have), and a read that stumbles here must not turn a resume into
   * a failed launch. A blank half is no credential — see the env block for why never half a pair.
   */
  private Optional<WorkspaceCredential> workspaceCredential(Long rowId) {
    if (!credentials.isResolvable()) {
      return Optional.empty();
    }
    try {
      return credentials
          .get()
          .forWorkspace(rowId)
          .filter(pair -> present(pair.clientId()) && present(pair.secret()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * The repo's owning project id: the scoped name's when a name resolver answers, else {@link
   * RepositoryLookup}'s, else blank. Failures resolve to blank rather than failing the container —
   * the id is enrichment (labels, the daemon's MCP scoping), never a provisioning gate.
   */
  private String projectIdFor(String repoId) {
    Optional<String> scoped =
        scopedName(repoId).map(RepositoryAddressResolver.ProjectScopedName::projectId);
    if (scoped.isPresent()) {
      return scoped.get();
    }
    if (!repositories.isResolvable()) {
      return "";
    }
    try {
      return repositories
          .get()
          .find(repoId)
          .map(RepositoryLookup.RepositoryView::projectId)
          .orElse("");
    } catch (RuntimeException e) {
      return ""; // an unreachable registry costs the id, never the container
    }
  }

  /**
   * Resolves the address a container uses to reach qits — the same host {@code workspace-daemon}
   * dials for its control socket that git/OTLP/MCP already use ({@link QitsHostResolver}). Injected
   * here so {@code forWorkspace} can compose the dial-home URL as container env, since {@code
   * workspace-daemon} runs in-container and cannot call the resolver itself.
   */
  @Inject QitsHostResolver qitsHostResolver;

  /**
   * The qits HTTP port containers connect to (git/OTLP/MCP and now the workspace-daemon control
   * socket).
   */
  @ConfigProperty(name = "qits.workspace.qits-port", defaultValue = "8080")
  String qitsPort;

  /** The edge address containers use for Git; direct githost traffic is Bearer-only. */
  @ConfigProperty(name = "qits.workspace.container-git-url", defaultValue = "http://qits-platform-edge:8080")
  String containerGitUrl;

  @ConfigProperty(name = "qits.githost.audience", defaultValue = "qits-githost")
  String gitHostAudience;

  /** Reuse the one IdP authority this service already uses for its own machine client. */
  @ConfigProperty(name = "quarkus.oidc-client.auth-server-url")
  String idpUrl;

  /** The environment-qualified qits-workspaces audience the daemon's control socket requires. */
  @ConfigProperty(name = "qits.auth.machine.audience", defaultValue = "qits-workspaces")
  String machineAudience;

  /**
   * The bearer every container's {@code WorkspaceApi} requires — injected as the fifteenth {@code
   * QITS_WORKSPACE_DAEMON_*} var. Read the config key's comment before treating it as a secret.
   */
  @ConfigProperty(name = "qits.workspace.daemon-api-token", defaultValue = "qits-workspace-daemon")
  String daemonApiToken;

  static final String MAVEN_MOUNT = "/caches/m2";
  static final String PNPM_MOUNT = "/caches/pnpm";

  /**
   * The pinned workspace image reference. Exposed rather than read from config a second time, so
   * the reference the spec carries and the reference anything else names cannot be two values. Who
   * pulls it is the orchestrator, under pull policy MISSING — the inspect-then-pull this service
   * used to do itself went with the docker socket.
   */
  public String image() {
    return image;
  }

  /**
   * The shared credential volume name (blank when the mount is disabled). The orchestrator creates
   * it and the other two at its own boot; this service only names them, so that the adapter can tell
   * a platform volume from the workspace's own when it builds the spec.
   */
  public String claudeVolume() {
    return claudeVolume;
  }

  /** The shared Maven-repo volume name (blank when disabled). The orchestrator creates it. */
  public String mavenVolume() {
    return mavenVolume;
  }

  /** The shared pnpm-store volume name (blank when disabled). The orchestrator creates it. */
  public String pnpmVolume() {
    return pnpmVolume;
  }

  /** The shared network name. The bootstrap creates it; the orchestrator only probes for it. */
  public String network() {
    return network;
  }

  /** Whether {@code /workspace} is a persistent per-workspace volume (vs. the ephemeral layer). */
  public boolean persistWorkspace() {
    return persistWorkspace;
  }

  /**
   * The deterministic per-workspace {@code /workspace} volume name — {@code prefix + workspaceId}.
   */
  public String workspaceVolumeName(String workspaceId) {
    return workspaceVolumePrefix + workspaceId;
  }

  /**
   * The {@code qits.*} labels a per-workspace {@code /workspace} volume carries — {@code
   * qits.managed=workspace-volume} (the reconcile filter) plus {@code qits.project} (resolved from
   * the repo via {@link RepositoryAddressResolver}) and the same repo/workspace/branch/parent identity
   * the container labels carry, so a dangling volume is human-readable and matchable to its row.
   * Ordered (LinkedHashMap) only for stable argv/log output.
   */
  public java.util.Map<String, String> workspaceVolumeLabels(
      String repoId, String workspaceId, String branch, String parent) {
    java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
    labels.put("qits.managed", "workspace-volume");
    labels.put("qits.project", resolveProjectId(repoId));
    labels.put("qits.repository", repoId);
    labels.put("qits.workspace", workspaceId);
    labels.put("qits.branch", branch == null ? "" : branch);
    labels.put("qits.parent", parent == null ? "" : parent);
    return labels;
  }

  /**
   * The project id a repo belongs to (blank when unresolved), for the {@code qits.project} label.
   */
  private String resolveProjectId(String repoId) {
    return projectIdFor(repoId);
  }

  /**
   * A {@link WorkspaceContainer} seeded for {@code workspaceId} of {@code repoId}: its
   * deterministic name, the host uid, the four {@code qits.*} labels startup reconciliation reads
   * back, the {@code host.docker.internal} alias Linux needs, the shared {@code qits-net} network,
   * the configured git commit identity as {@code GIT_*} env ({@link GitIdentity}), the shared
   * credential + build-cache volumes (whenever configured), the configured resource limits (memory
   * hard cap, pids, cpus — whenever configured), the image, and the {@code qits-workspace-daemon}
   * dial-home env, and the commissioned platform credential when the workspace holds one. The
   * container's process is {@code qits-workspace-daemon} via the image
   * ENTRYPOINT (no command is appended) — with no {@code sleep infinity}
   * fallback, so a container that can't run the daemon fails to start rather than lingering
   * unmanaged. Everything safety-critical is already in place; the caller may keep chaining but
   * need not.
   */
  public WorkspaceContainer forWorkspace(
      String repoId, String workspaceId, Long rowId, String branch, String parent) {
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
    // workspace-daemon's dial-home coordinates + identity, as container env
    // (QITS_WORKSPACE_DAEMON_* -> the binary's
    // qits.workspace-daemon.* config). workspace-daemon is the container's process (below) and runs
    // in-container, so
    // it can't call QitsHostResolver — the URL is composed here from the same host/port
    // git/OTLP/MCP
    // use. Labels carry the same identity for host-side reconciliation, but labels aren't env, so
    // we
    // set it explicitly. A container whose workspace-daemon can't reach qits stays alive and idle
    // (the
    // binary
    // never exits on a failed dial), so this is behaviour-neutral (docs/epics/qits-workspace-daemon/).
    container.env(
        "QITS_WORKSPACE_DAEMON_URL",
        "ws://"
            + qitsHostResolver.qitsHost()
            + ":"
            + qitsPort
            + "/workspaces/daemon/"
            + rowId);
    // The git base the daemon self-clones from, told outright — never derived. The daemon's
    // fallback derives the pre-split address (/artifacts/git off the dial-home authority) and
    // 404s on a platform whose git host is qits-githost: the first real workspace on the
    // 2026-08-15 bare-server platform failed exactly there. /git is the githost's root-level
    // prefix, verbatim through the gateway, so the one authority above routes it too.
    container.env(
        "QITS_WORKSPACE_DAEMON_GIT_BASE_URL",
        gitBase(containerGitUrl));
    // The path ContainerProxyRoute addresses this container at. The proxy forwards a caller's path
    // untouched, so the daemon has to be told which leading part of it is its own address rather
    // than a route it serves — the same arrangement a spawned dev server has with QITS_PUBLIC_BASE,
    // and the reason neither hop has to rewrite anything. Injected from ContainerProxyPath so the
    // literal is spelled once: the route and the container's idea of the route cannot drift.
    container.env("QITS_WORKSPACE_DAEMON_API_BASE_PATH", ContainerProxyPath.base(rowId));
    // The per-workspace half of every web-viewable service's public base. The daemon spawns the
    // dev servers, so it is the daemon that must bake QITS_PUBLIC_BASE (= this base + the declared
    // service id + the declared web-view base-path) into each service's environment — on every
    // spawn, crash-restart included (N3: nothing told the respawned dev server its base, and the
    // verbatim service proxy 404'd the framed view). Same told-never-derived arrangement as the
    // API base path above.
    container.env("QITS_WORKSPACE_DAEMON_SERVICE_PROXY_BASE", ServiceProxyPath.PREFIX + rowId);
    container.env("QITS_WORKSPACE_DAEMON_WORKSPACE_ID", workspaceId);
    container.env("QITS_WORKSPACE_DAEMON_REPOSITORY_ID", repoId);
    container.env("QITS_WORKSPACE_DAEMON_BRANCH", branch == null ? "" : branch);
    container.env("QITS_WORKSPACE_DAEMON_PARENT", parent == null ? "" : parent);
    // The project-scoped name the daemon self-clones under (/git/<projectId>/<name>), so committed
    // relative submodule urls resolve natively in-container. Blank when the repo has no project —
    // the
    // daemon then id-addresses (/git/<repositoryId>), mirroring cloneUrl's fallback.
    Optional<RepositoryAddressResolver.ProjectScopedName> scopedName = scopedName(repoId);
    // The owning project id, also as a label so it mirrors the per-workspace volume's qits.project
    // (the volume labels carry it for dangling-volume reconcile; the container carries it for
    // symmetry). Resolved through projectIdFor — the RepositoryLookup fallback is what stopped
    // this env var from shipping empty (D2). Blank only when no registry answers.
    String projectId =
        scopedName
            .map(RepositoryAddressResolver.ProjectScopedName::projectId)
            .orElseGet(() -> projectIdFor(repoId));
    container.label("qits.project", projectId);
    container.env("QITS_WORKSPACE_DAEMON_PROJECT_ID", projectId);
    container.env(
        "QITS_WORKSPACE_DAEMON_REPO_NAME",
        scopedName.map(RepositoryAddressResolver.ProjectScopedName::name).orElse(""));
    // The bootstrap kill switch the daemon honours when it self-runs the chain on boot (Part 3).
    container.env(
        "QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN", String.valueOf(bootstrapAutorunEnabled));
    // The auto-push kill switch the daemon honours when it pushes committed work on its own
    // (docs/epics/qits-workspace-daemon/ bidirectional auto-sync).
    container.env("QITS_WORKSPACE_DAEMON_AUTO_PUSH_ENABLED", String.valueOf(autoPushEnabled));
    // Service (dev-server) supervision, self-run by the daemon as the boot-sequence tail (Part 4):
    // the auto-start kill switch + the knobs the in-container ServiceSupervisor honours.
    container.env(
        "QITS_WORKSPACE_DAEMON_SERVICES_AUTOSTART", String.valueOf(servicesAutostartEnabled));
    container.env(
        "QITS_WORKSPACE_DAEMON_SERVICE_READY_GRACE_MS", String.valueOf(serviceReadyGraceMs));
    container.env(
        "QITS_WORKSPACE_DAEMON_SERVICE_RESTART_BACKOFF_INITIAL_MS",
        String.valueOf(serviceBackoffInitialMs));
    container.env(
        "QITS_WORKSPACE_DAEMON_SERVICE_RESTART_BACKOFF_MAX_MS",
        String.valueOf(serviceBackoffMaxMs));
    container.env(
        "QITS_WORKSPACE_DAEMON_SERVICE_STOP_GRACE_MS", String.valueOf(serviceStopGraceMs));
    // The bearer the daemon's HTTP API requires. Without it WorkspaceApi does not bind at all —
    // fail-closed, because an omitted env is indistinguishable from a misconfiguration and serving
    // an untrusted checkout anonymously across the docker network would be silent. That is why this
    // is injected rather than the daemon's precondition being relaxed: the isolation already exists
    // and removing it to serve a host-side convenience would be the wrong repo for the decision.
    //
    // One shared value with a default, so a deployment needs no configuration. See the config key's
    // own comment for what it is NOT: it is a handshake constant, not a boundary.
    container.env("QITS_WORKSPACE_DAEMON_API_TOKEN", daemonApiToken);
    // The credential this container holds toward the PLATFORM — the other direction from the token
    // above, which is what the host presents to the daemon. It is an idp client id and secret
    // commissioned for this container alone (WorkspaceService provisions it, CredentialCommissioner
    // mints it), so registry pulls and pushes from inside the workspace authenticate as this
    // workspace rather than as a durable identity shared by everything on the network.
    //
    // Both vars or neither: half a pair is a credential that cannot be presented, and a container
    // launched with one would look configured and fail at the first pull. Absent is the shipped
    // posture and today's behaviour — no issuer wired, no credential on the row, no env here.
    workspaceCredential(rowId)
        .ifPresent(
            credential -> {
              container.env("QITS_COMMISSIONED_CLIENT_ID", credential.clientId());
              container.env("QITS_COMMISSIONED_CLIENT_SECRET", credential.secret());
              // The image's credential helper mints a fresh bearer for each Git authentication.
              // It compares Git's requested authority with this value before it ever exchanges the
              // client secret, so an absolute submodule or an ad-hoc external remote cannot obtain
              // a platform token.
              container.env("GIT_CONFIG_GLOBAL", "/etc/qits-gitconfig");
              container.env("QITS_GIT_AUTH_HOST", gitAuthority(containerGitUrl));
              container.env("QITS_GIT_AUTH_TOKEN_URL", tokenUrl(idpUrl));
              container.env("QITS_GIT_AUTH_AUDIENCE", gitHostAudience);
              // The same short-lived credential authenticates the daemon's dial-home socket, but
              // its audience is qits-workspaces rather than qits-githost. Keep the token endpoint
              // and target explicit: deriving either from the Git endpoint would silently put a
              // workspace's control plane behind a different service's policy.
              container.env("QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL", tokenUrl(idpUrl));
              container.env("QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE", machineAudience);
            });
    // Resource limits (opt-out): without a memory cap, every JVM in the container sizes its heap
    // against the whole host's RAM and a dev server can OOM the host. Blank config disables a cap.
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
      // docs/epics/qits-coding-agents/features/2026-07-20_kimi-code-harness.md):
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
    // The per-workspace /workspace volume: the workspace's checkout, persisted across container
    // recreation instead of dying with the writable layer. The first mount populates the empty
    // volume from the image's world-writable /workspace (docker copies the image dir's contents AND
    // permissions), so no permission fix is needed under the arbitrary-uid container user — the
    // same
    // reason the shared cache volumes work. It rides the spec as a row-claimed mount, so the
    // orchestrator creates it as part of the same ensure that starts the container. Flag off ⇒ /workspace stays the ephemeral writable layer (today's
    // behavior). See docs/epics/qits-workspaces/features/2026-07-25_persistent-workspace-volume.md.
    if (persistWorkspace) {
      container.volume(workspaceVolumeName(workspaceId), "/workspace");
    }
    // The container runs ONLY the workspace-daemon, via the image ENTRYPOINT
    // (docker/qits/Dockerfile),
    // so qits puts no command on the spec at all and the image alone boots the control plane.
    // There is deliberately NO `sleep infinity` fallback: a container that can't run the daemon
    // must
    // FAIL to start rather than linger. A daemon-less container never sends HELLO, so qits has no
    // control plane to it — after the epic it would be an unmanaged shadow holding qits' uid,
    // mounts
    // and network. So "no daemon ⇒ no workspace": a stale image (built before this change) exits
    // loudly, surfacing that the image must be rebuilt. `--init` (WorkspaceContainer.toRunArgv)
    // puts
    // tini at PID 1, so the daemon is tini's child (tini reaps zombies + forwards signals); the
    // daemon
    // holds the socket open and is otherwise idle in Part 1. `init` is a spec field now rather
    // than a run flag, and it is what keeps a long-lived daemon from collecting its children.
    return container.image(image);
  }

  private static String tokenUrl(String idpBase) {
    return idpBase.replaceAll("/+$", "") + "/token";
  }

  private static String gitAuthority(String gitBase) {
    try {
      URI uri = URI.create(gitBase);
      if (uri.getScheme() == null || uri.getRawAuthority() == null || uri.getUserInfo() != null) {
        throw new IllegalArgumentException("not an absolute git host URL");
      }
      return uri.getRawAuthority();
    } catch (RuntimeException badUrl) {
      throw new IllegalStateException("qits.githost.url must be an absolute URL", badUrl);
    }
  }

  private static String gitBase(String base) {
    return base.replaceAll("/+$", "") + "/git";
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
