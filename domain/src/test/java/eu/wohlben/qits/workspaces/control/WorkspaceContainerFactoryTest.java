package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Instance;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The always-on cross-cutting config the factory guarantees on every workspace container. Plain
 * JUnit (same package) sets the {@code @ConfigProperty} fields directly, so no docker or Quarkus
 * boot is needed — this is the coverage {@link FakeContainerRuntime} (which models neither the
 * volume nor the labels) cannot give.
 *
 * <p>Read through {@link WorkspaceContainer}'s readers, not through a rendered argv: the socket is
 * gone and the {@code docker run} flags with it, so what is asserted here is the decision (this
 * volume at this path, this env var with this value) rather than the string it used to become.
 */
class WorkspaceContainerFactoryTest {

  /**
   * A stand-in for {@code qits.workspace.image}, in the shape the service now ships: registry host
   * with a port, repository path, calver tag. The shape is what earns its keep here — the reference
   * carries two colons, so anything that ever tried to split it into name and tag would fail on
   * this reference rather than on a container launch.
   *
   * <p>The version is invented, and stays invented. The real pin lives in {@code
   * META-INF/microprofile-config.properties} and the release train moves it; a test that copied it
   * would only add a place the train forgets. This test proves the factory carries whatever image
   * it is handed, which is a claim no particular version makes truer.
   */
  private static final String IMAGE = "localhost:8081/qits/workspace:2026.101.1";

  private WorkspaceContainerFactory factory() {
    WorkspaceContainerFactory f = new WorkspaceContainerFactory();
    f.image = IMAGE;
    f.projectsUrl = "http://qits-projects:8080/";
    f.observabilityUrl = "http://qits-observability:8080/";
    f.network = "qits-net";
    f.claudeVolume = "qits_shared_dot_claude";
    f.claudeMount = "/claude-home";
    f.mavenVolume = "qits_shared_m2";
    f.pnpmVolume = "qits_shared_pnpm";
    // The shipped posture is the three registry keys BLANK — no default address exists to ship —
    // so the default factory here carries none, and the test that wants them sets them itself.
    f.mavenRepositoryUrl = Optional.empty();
    f.npmRegistryUrl = Optional.empty();
    f.npmProxyUrl = Optional.empty();
    f.timezone = Optional.empty();
    // Mirrors the shipped default (service/cli application.properties): a hard memory cap on every
    // container, pids/cpus off.
    f.memoryLimit = Optional.of("4g");
    f.pidsLimit = Optional.empty();
    f.cpus = Optional.empty();
    f.gitIdentity = identity("qits", "qits@local");
    // An explicit git-host so qitsHost() is deterministic (no WSL/host.docker.internal detection),
    // the same way the devcontainer pins the `qits` alias — this is what workspace-daemon dials
    // home to.
    f.qitsHostResolver = resolver("qits");
    f.qitsPort = "8080";
    f.containerGitUrl = "http://qits-platform-edge:8080";
    f.gitHostAudience = "dev-qits-githost";
    f.idpUrl = "http://qits-idp:8080/idp";
    f.machineAudience = "dev-qits-workspaces";
    // Mirrors the shipped default (qits.bootstrap.autorun-enabled): the daemon self-runs bootstrap.
    f.bootstrapAutorunEnabled = true;
    // Mirrors the shipped qits.services.* defaults, forwarded to the daemon's in-container
    // ServiceSupervisor (Part 4).
    f.servicesAutostartEnabled = true;
    f.serviceReadyGraceMs = 10000;
    f.serviceBackoffInitialMs = 1000;
    f.serviceBackoffMaxMs = 30000;
    f.serviceStopGraceMs = 5000;
    // A live project scope, so the daemon self-clones name-addressed. Stubbed (the real resolver
    // needs a tx + DB); the no-scope fallback has its own test.
    f.nameResolver =
        nameResolver(
            Optional.of(new RepositoryAddressResolver.ProjectScopedName("proj-1", "my-repo")));
    // No repository registry by default — the lookup fallback has its own test.
    f.repositories = StubInstance.empty();
    // No credential lookup by default: the shipped posture is no issuer wired, so a container
    // carries no commissioned pair. The two cases that do have one set this themselves.
    f.credentials = StubInstance.empty();
    return f;
  }

  private static Instance<RepositoryAddressResolver> nameResolver(
      Optional<RepositoryAddressResolver.ProjectScopedName> scopedName) {
    return StubInstance.of(repoId -> scopedName);
  }

  private static GitIdentity identity(String name, String email) {
    GitIdentity identity = new GitIdentity();
    identity.name = name;
    identity.email = email;
    return identity;
  }

  private static QitsHostResolver resolver(String host) {
    QitsHostResolver r = new QitsHostResolver();
    r.configured = host;
    return r;
  }

  @Test
  void alwaysSeedsTheCredentialVolumeLabelsHostUserAndImage() {
    WorkspaceContainer c = factory().forWorkspace("repo12345678abc", "work", 1L, "main", "0parent");

    // The guarantee: the shared credential volume is mounted on every container, the shared build
    // caches beside it at their fixed paths, and nothing else. Asserted as the whole list because
    // the mounts are the one thing a workspace cannot be quietly given an extra of; the
    // per-workspace /workspace volume is absent because persist-workspace is off on this
    // hand-built factory (a plain field, so it is Java's false rather than the shipped default).
    assertEquals(
        List.of(
            new WorkspaceContainer.Mount("qits_shared_dot_claude", "/claude-home"),
            new WorkspaceContainer.Mount("qits_shared_m2", "/caches/m2"),
            new WorkspaceContainer.Mount("qits_shared_pnpm", "/caches/pnpm")),
        c.volumes());
    // ...and every in-container `claude` is pointed at it regardless of HOME, so a login persists
    // across containers even for ad-hoc runs.
    assertEnv(c, "CLAUDE_CONFIG_DIR", "/claude-home/.claude");
    // ...and Kimi Code's data root likewise (KIMI_CODE_HOME relocates config, credentials and
    // sessions onto the same volume).
    assertEnv(c, "KIMI_CODE_HOME", "/claude-home/.kimi-code");
    // The build-cache tools pointed at their mounts, so downloads are reused across builds.
    assertEnv(c, "MAVEN_OPTS", "-Dmaven.repo.local=/caches/m2");
    assertEnv(c, "npm_config_store_dir", "/caches/pnpm/store");
    // The qits.* reconciliation labels.
    assertLabel(c, "qits.repository", "repo12345678abc");
    assertLabel(c, "qits.workspace", "work");
    assertLabel(c, "qits.branch", "main");
    assertLabel(c, "qits.parent", "0parent");
    // Host alias, host uid, deterministic name, image.
    assertEquals(List.of("host.docker.internal:host-gateway"), c.addHosts());
    // The uid is the host's, so the value is whatever this machine's is — what is asserted is that
    // one was resolved and it is a uid, not a copy of the private lookup that produced it.
    assertTrue(c.user().matches("\\d+"), c.user());
    assertEquals("qits-ws-work-repo1234", c.name());
    assertEquals(IMAGE, c.image());
    // workspace-daemon's dial-home coordinates + identity, injected as env
    // (QITS_WORKSPACE_DAEMON_* -> qits.workspace-daemon.*) — workspace-daemon runs in-container so
    // it can't call QitsHostResolver; the URL is composed here.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_URL", "ws://qits:8080/workspaces/daemon/1");
    assertEnv(c, "QITS_REPOSITORY_MCP_URL", "http://qits-projects:8080/projects/mcp");
    assertEnv(
        c,
        "QITS_OBSERVABILITY_MCP_URL",
        "http://qits-observability:8080/observability/mcp");
    // The self-clone base, told rather than left to the daemon's pre-split derivation
    // (/artifacts/git), which 404s now that the git host is qits-githost under /git.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_GIT_BASE_URL", "http://qits-platform-edge:8080/git");
    // Where ContainerProxyRoute addresses this container. Asserted as a literal rather than through
    // ContainerProxyPath.base: the daemon in the other repo matches this string, so a test that
    // computed it the same way the production code does would rename itself along with the bug.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_API_BASE_PATH", "/workspaces/container/1/");
    // The per-workspace half of QITS_PUBLIC_BASE, which the daemon completes with the declared
    // service id (+ web-view base-path) at every spawn. Literal for the same cross-repo reason as
    // above: ServiceProxyRoute's verbatim proxy answers under exactly this prefix.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_SERVICE_PROXY_BASE", "/workspaces/service/1");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_WORKSPACE_ID", "work");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_REPOSITORY_ID", "repo12345678abc");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_BRANCH", "main");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_PARENT", "0parent");
    // The project-scoped name the daemon self-clones under (/git/<projectId>/<repoName>), so
    // committed relative submodule urls resolve natively (docs/epics/qits-workspace-daemon/ Part
    // 1).
    assertEnv(c, "QITS_WORKSPACE_DAEMON_PROJECT_ID", "proj-1");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_REPO_NAME", "my-repo");
    // The bootstrap kill switch the daemon honours when it self-runs the chain on boot (Part 3).
    assertEnv(c, "QITS_WORKSPACE_DAEMON_BOOTSTRAP_AUTORUN", "true");
    // Part 4: the service (dev-server) auto-start kill switch the daemon honours as its boot tail.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_SERVICES_AUTOSTART", "true");
    // The shared network, so qits reaches the container's ports by DNS name with no host publish.
    assertEquals("qits-net", c.network());
    // The hard memory cap (it is the swap cap as well, so the container can't swap past it) —
    // without it a dev server's JVMs size against the whole host's RAM and can OOM the host
    // (docs/issues/resolved/2026-07-21_workspace-container-unbounded-memory-host-oom.md).
    assertEquals("4g", c.memory());
    // pids/cpus are off by default.
    assertNull(c.pidsLimit());
    assertNull(c.cpus());
    // The blank default timezone inherits qits' own zone, so container wall-clock matches qits'.
    assertEnv(c, "TZ", ZoneId.systemDefault().getId());
    // The commit identity as container-level env, so every git process in the container (qits'
    // verbs, the agent, actions, ad-hoc shells) commits as the configured identity.
    assertEnv(c, "GIT_AUTHOR_NAME", "qits");
    assertEnv(c, "GIT_AUTHOR_EMAIL", "qits@local");
    assertEnv(c, "GIT_COMMITTER_NAME", "qits");
    assertEnv(c, "GIT_COMMITTER_EMAIL", "qits@local");
  }

  @Test
  void aRepoWithoutAProjectScopeInjectsBlankNameEnvSoTheDaemonIdAddresses() {
    WorkspaceContainerFactory f = factory();
    f.nameResolver = nameResolver(Optional.empty());

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    // Blank scope ⇒ the Provisioner clones id-addressed (/git/<repositoryId>), mirroring cloneUrl's
    // fallback. Blank rather than missing: the daemon reads the var either way.
    assertEnv(c, "QITS_WORKSPACE_DAEMON_PROJECT_ID", "");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_REPO_NAME", "");
  }

  @Test
  void withoutANameResolverTheProjectScopedAddressFallsBackToTheRepositoryRegistry() {
    // The deployable has no RepositoryAddressResolver. RepositoryLookup is therefore the production
    // source for both halves of the address relative submodule urls need.
    WorkspaceContainerFactory f = factory();
    f.nameResolver = nameResolver(Optional.empty());
    f.repositories =
        StubInstance.of(
            repoId ->
                Optional.of(
                    new RepositoryLookup.RepositoryView(
                        repoId,
                        "qits-qits",
                        "53c78589-6af3-4221-b3ef-315c867b0863",
                        "main")));

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEnv(c, "QITS_WORKSPACE_DAEMON_PROJECT_ID", "53c78589-6af3-4221-b3ef-315c867b0863");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_REPO_NAME", "qits-qits");
    assertLabel(c, "qits.project", "53c78589-6af3-4221-b3ef-315c867b0863");
  }

  @Test
  void aConfiguredIdentityFlowsIntoTheContainerEnv() {
    WorkspaceContainerFactory f = factory();
    f.gitIdentity = identity("qits-bot", "qits-bot@example.com");

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEnv(c, "GIT_AUTHOR_NAME", "qits-bot");
    assertEnv(c, "GIT_AUTHOR_EMAIL", "qits-bot@example.com");
    assertEnv(c, "GIT_COMMITTER_NAME", "qits-bot");
    assertEnv(c, "GIT_COMMITTER_EMAIL", "qits-bot@example.com");
  }

  @Test
  void anExplicitTimezoneOverridesTheInheritedZone() {
    WorkspaceContainerFactory f = factory();
    f.timezone = Optional.of("Pacific/Auckland");

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEnv(c, "TZ", "Pacific/Auckland");
  }

  @Test
  void aBlankMemoryLimitDisablesTheCap() {
    WorkspaceContainerFactory f = factory();
    f.memoryLimit = Optional.of("  ");

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    // The blank never reaches the container: absent, not "  ", so the spec carries no cap at all.
    assertNull(c.memory());
  }

  @Test
  void configuredPidsAndCpuLimitsFlowIntoTheContainer() {
    WorkspaceContainerFactory f = factory();
    f.pidsLimit = Optional.of("2048");
    f.cpus = Optional.of("2.5");

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertEquals("2048", c.pidsLimit());
    assertEquals("2.5", c.cpus());
  }

  @Test
  void blankingAVolumeOmitsOnlyThatMount() {
    WorkspaceContainerFactory f = factory();
    f.claudeVolume = "";
    f.pnpmVolume = "";

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    // The blanked caches drop their mount — and, for claude/kimi, the credential-dir env too —
    // while the still-configured Maven cache stays.
    assertEquals(
        List.of(new WorkspaceContainer.Mount("qits_shared_m2", "/caches/m2")), c.volumes());
    assertFalse(c.env().containsKey("CLAUDE_CONFIG_DIR"), c.env().toString());
    assertFalse(c.env().containsKey("KIMI_CODE_HOME"), c.env().toString());
    assertEnv(c, "MAVEN_OPTS", "-Dmaven.repo.local=/caches/m2");
    // Everything else still present, incl. an empty parent label for the null parent.
    assertEquals(List.of("host.docker.internal:host-gateway"), c.addHosts());
    assertLabel(c, "qits.repository", "repo12345678abc");
    assertLabel(c, "qits.parent", "");
    assertEquals("qits-ws-work-repo1234", c.name());
  }

  @Test
  void aCommissionedWorkspaceCarriesItsPlatformCredentialAsEnv() {
    WorkspaceContainerFactory f = factory();
    f.credentials =
        StubInstance.of(rowId -> Optional.of(new WorkspaceCredential("ws-1-a", "s3cr3t")));

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    // The pair the workspace authenticates to the platform with — registry pulls and pushes from
    // inside the container, once reads are gated. Two variables, both or neither.
    assertEnv(c, "QITS_COMMISSIONED_CLIENT_ID", "ws-1-a");
    assertEnv(c, "QITS_COMMISSIONED_CLIENT_SECRET", "s3cr3t");
    assertEnv(c, "GIT_CONFIG_GLOBAL", "/etc/qits-gitconfig");
    assertEnv(c, "QITS_GIT_AUTH_HOST", "qits-platform-edge:8080");
    assertEnv(c, "QITS_GIT_AUTH_TOKEN_URL", "http://qits-idp:8080/idp/token");
    assertEnv(c, "QITS_GIT_AUTH_AUDIENCE", "dev-qits-githost");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL", "http://qits-idp:8080/idp/token");
    assertEnv(c, "QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE", "dev-qits-workspaces");
  }

  @Test
  void noCommissionMeansNoCredentialEnvAtAll() {
    // Both spellings of "not wired" — no lookup installed, and one that answers empty — and the
    // half-answer that must never become half a pair.
    for (Instance<WorkspaceCredentials> lookup :
        List.of(
            StubInstance.<WorkspaceCredentials>empty(),
            StubInstance.<WorkspaceCredentials>of(rowId -> Optional.empty()),
            StubInstance.<WorkspaceCredentials>of(
                rowId -> Optional.of(new WorkspaceCredential("ws-1-a", " "))))) {
      WorkspaceContainerFactory f = factory();
      f.credentials = lookup;

      WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

      assertFalse(c.env().containsKey("QITS_COMMISSIONED_CLIENT_ID"), c.env().toString());
      assertFalse(c.env().containsKey("QITS_COMMISSIONED_CLIENT_SECRET"), c.env().toString());
      assertFalse(c.env().containsKey("QITS_WORKSPACE_DAEMON_AUTH_TOKEN_URL"), c.env().toString());
      assertFalse(c.env().containsKey("QITS_WORKSPACE_DAEMON_AUTH_AUDIENCE"), c.env().toString());
    }
  }

  /** Assert the container carries {@code key} with exactly {@code value} in its environment. */
  private static void assertEnv(WorkspaceContainer container, String key, String value) {
    assertTrue(container.env().containsKey(key), () -> "no " + key + " in " + container.env());
    assertEquals(value, container.env().get(key), key);
  }

  /** Assert the container carries {@code key} with exactly {@code value} in its labels. */
  private static void assertLabel(WorkspaceContainer container, String key, String value) {
    assertTrue(container.labels().containsKey(key), () -> "no " + key + " in " + container.labels());
    assertEquals(value, container.labels().get(key), key);
  }

  @Test
  void tellsTheContainerWhereThePlatformRegistriesAreWhenItHasBeenTold() {
    WorkspaceContainerFactory f = factory();
    f.mavenRepositoryUrl = Optional.of("http://dev-qits-artifacts:8080/artifacts/maven/maven");
    f.npmProxyUrl = Optional.of("http://qits-platform-mirror:8080/artifacts/npm/npmjs/");
    f.npmRegistryUrl = Optional.of("http://dev-qits-artifacts:8080/artifacts/npm/npm/");

    WorkspaceContainer c = f.forWorkspace("repo12345678abc", "work", 1L, "main", null);

    // The names are the CONTRACT and not an implementation detail, which is why they are asserted
    // literally: the two npm keys are npm's own environment form (npm_config_*), which is what
    // outranks the .npmrc every SPA commits, and the Maven key is what the image's profile snippet
    // reads before it adds its -s. Rename any of the three here and a workspace goes back to
    // resolving the public internet, silently, with a green build.
    assertEquals(
        "http://dev-qits-artifacts:8080/artifacts/maven/maven", c.env().get("QITS_MAVEN_REPOSITORY_URL"));
    assertEquals(
        "http://qits-platform-mirror:8080/artifacts/npm/npmjs/", c.env().get("npm_config_registry"));
    assertEquals(
        "http://dev-qits-artifacts:8080/artifacts/npm/npm/", c.env().get("npm_config_@qits:registry"));
  }

  @Test
  void tellsTheContainerNothingAboutRegistriesItWasNotToldAbout() {
    // Absent is a supported configuration, not a misconfiguration: a deployment that wires none of
    // the three gets a container identical to the one it got before these keys existed. Asserted
    // because the alternative — injecting a derived or defaulted address — would point builds at a
    // host that does not exist on that deployment, which is worse than leaving them as they were.
    WorkspaceContainer c = factory().forWorkspace("repo12345678abc", "work", 1L, "main", null);

    assertNull(c.env().get("QITS_MAVEN_REPOSITORY_URL"));
    assertNull(c.env().get("npm_config_registry"));
    assertNull(c.env().get("npm_config_@qits:registry"));
  }

}
