package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The always-on cross-cutting config the factory guarantees on every workspace container. Plain
 * JUnit (same package) sets the {@code @ConfigProperty} fields directly, so no docker or Quarkus
 * boot is needed — this is the coverage {@link FakeContainerRuntime} (which models neither the
 * volume nor the labels) cannot give.
 */
class WorkspaceContainerFactoryTest {

  private WorkspaceContainerFactory factory() {
    WorkspaceContainerFactory f = new WorkspaceContainerFactory();
    f.image = "qits/workspace:latest";
    f.network = "qits-net";
    f.claudeVolume = "qits_shared_dot_claude";
    f.claudeMount = "/claude-home";
    f.mavenVolume = "qits_shared_m2";
    f.pnpmVolume = "qits_shared_pnpm";
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
    // A live project scope, so the daemon self-clones name-addressed. Stubbed (the real resolver
    // needs a tx + DB); the no-scope fallback has its own test.
    f.nameResolver =
        nameResolver(
            Optional.of(new RepositoryNameResolver.ProjectScopedName("proj-1", "my-repo")));
    return f;
  }

  private static RepositoryNameResolver nameResolver(
      Optional<RepositoryNameResolver.ProjectScopedName> scopedName) {
    return new RepositoryNameResolver() {
      @Override
      public Optional<ProjectScopedName> resolve(String repoId) {
        return scopedName;
      }
    };
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
  void alwaysSeedsTheCredentialVolumeLabelsHostUserImageAndCommand() {
    List<String> argv =
        factory().forWorkspace("repo12345678abc", "work", "main", "0parent").toRunArgv();

    // The guarantee: the shared credential volume is mounted on every container.
    assertSequence(argv, "-v", "qits_shared_dot_claude:/claude-home");
    // ...and every in-container `claude` is pointed at it regardless of HOME, so a login persists
    // across containers even for ad-hoc runs.
    assertSequence(argv, "-e", "CLAUDE_CONFIG_DIR=/claude-home/.claude");
    // ...and Kimi Code's data root likewise (KIMI_CODE_HOME relocates config, credentials and
    // sessions onto the same volume).
    assertSequence(argv, "-e", "KIMI_CODE_HOME=/claude-home/.kimi-code");
    // Shared build caches mounted + tools pointed at them, so downloads are reused across builds.
    assertSequence(argv, "-v", "qits_shared_m2:/caches/m2");
    assertSequence(argv, "-e", "MAVEN_OPTS=-Dmaven.repo.local=/caches/m2");
    assertSequence(argv, "-v", "qits_shared_pnpm:/caches/pnpm");
    assertSequence(argv, "-e", "npm_config_store_dir=/caches/pnpm/store");
    // The qits.* reconciliation labels.
    assertSequence(argv, "--label", "qits.repository=repo12345678abc");
    assertSequence(argv, "--label", "qits.workspace=work");
    assertSequence(argv, "--label", "qits.branch=main");
    assertSequence(argv, "--label", "qits.parent=0parent");
    // Host alias, host uid, deterministic name, image, entrypoint.
    assertTrue(argv.contains("--add-host=host.docker.internal:host-gateway"), argv.toString());
    assertTrue(argv.contains("--user"), argv.toString());
    assertSequence(argv, "--name", "qits-ws-work-repo1234");
    assertTrue(argv.contains("qits/workspace:latest"), argv.toString());
    // The container runs qits-workspace-daemon via the image ENTRYPOINT (docker/qits/Dockerfile),
    // with no `docker run` command and — deliberately — no `sleep infinity` fallback: the image is
    // the LAST token (no trailing command), the run argv carries no daemon path, and a container
    // that can't run the daemon fails to start rather than lingering
    // (docs/epics/qits-workspace-daemon/).
    assertEquals("qits/workspace:latest", argv.get(argv.size() - 1), argv.toString());
    assertFalse(argv.contains("sleep"), argv.toString());
    assertFalse(argv.contains("infinity"), argv.toString());
    assertFalse(argv.contains("/usr/local/bin/qits-workspace-daemon"), argv.toString());
    // workspace-daemon's dial-home coordinates + identity, injected as env (QITS_WORKSPACE_DAEMON_*
    // ->
    // qits.workspace-daemon.*)
    // — workspace-daemon runs in-container so it can't call QitsHostResolver; the URL is composed
    // here.
    assertSequence(
        argv, "-e", "QITS_WORKSPACE_DAEMON_URL=ws://qits:8080/api/workspace-daemon/work");
    assertSequence(argv, "-e", "QITS_WORKSPACE_DAEMON_WORKSPACE_ID=work");
    assertSequence(argv, "-e", "QITS_WORKSPACE_DAEMON_REPOSITORY_ID=repo12345678abc");
    assertSequence(argv, "-e", "QITS_WORKSPACE_DAEMON_BRANCH=main");
    assertSequence(argv, "-e", "QITS_WORKSPACE_DAEMON_PARENT=0parent");
    // The project-scoped name the daemon self-clones under (/git/<projectId>/<repoName>), so
    // committed relative submodule urls resolve natively (docs/epics/qits-workspace-daemon/ Part
    // 1).
    assertSequence(argv, "-e", "QITS_WORKSPACE_DAEMON_PROJECT_ID=proj-1");
    assertSequence(argv, "-e", "QITS_WORKSPACE_DAEMON_REPO_NAME=my-repo");
    // The shared network, so qits reaches the container's ports by DNS name with no host publish.
    assertSequence(argv, "--network", "qits-net");
    assertFalse(argv.contains("-p"), argv.toString());
    // The hard memory cap (--memory-swap equal, so the container can't swap past it either) —
    // without it a dev daemon's JVMs size against the whole host's RAM and can OOM the host
    // (docs/issues/resolved/2026-07-21_workspace-container-unbounded-memory-host-oom.md).
    assertSequence(argv, "--memory", "4g");
    assertSequence(argv, "--memory-swap", "4g");
    // pids/cpus are off by default.
    assertFalse(argv.contains("--pids-limit"), argv.toString());
    assertFalse(argv.contains("--cpus"), argv.toString());
    // The blank default timezone inherits qits' own zone, so container wall-clock matches qits'.
    assertSequence(argv, "-e", "TZ=" + ZoneId.systemDefault().getId());
    // The commit identity as container-level env, so every git process in the container (qits'
    // verbs, the agent, actions, ad-hoc shells) commits as the configured identity.
    assertSequence(argv, "-e", "GIT_AUTHOR_NAME=qits");
    assertSequence(argv, "-e", "GIT_AUTHOR_EMAIL=qits@local");
    assertSequence(argv, "-e", "GIT_COMMITTER_NAME=qits");
    assertSequence(argv, "-e", "GIT_COMMITTER_EMAIL=qits@local");
  }

  @Test
  void aRepoWithoutAProjectScopeInjectsBlankNameEnvSoTheDaemonIdAddresses() {
    WorkspaceContainerFactory f = factory();
    f.nameResolver = nameResolver(Optional.empty());

    List<String> argv = f.forWorkspace("repo12345678abc", "work", "main", null).toRunArgv();

    // Blank scope ⇒ the Provisioner clones id-addressed (/git/<repositoryId>), mirroring cloneUrl's
    // fallback.
    assertSequence(argv, "-e", "QITS_WORKSPACE_DAEMON_PROJECT_ID=");
    assertSequence(argv, "-e", "QITS_WORKSPACE_DAEMON_REPO_NAME=");
  }

  @Test
  void aConfiguredIdentityFlowsIntoTheContainerEnv() {
    WorkspaceContainerFactory f = factory();
    f.gitIdentity = identity("qits-bot", "qits-bot@example.com");

    List<String> argv = f.forWorkspace("repo12345678abc", "work", "main", null).toRunArgv();

    assertSequence(argv, "-e", "GIT_AUTHOR_NAME=qits-bot");
    assertSequence(argv, "-e", "GIT_AUTHOR_EMAIL=qits-bot@example.com");
    assertSequence(argv, "-e", "GIT_COMMITTER_NAME=qits-bot");
    assertSequence(argv, "-e", "GIT_COMMITTER_EMAIL=qits-bot@example.com");
  }

  @Test
  void anExplicitTimezoneOverridesTheInheritedZone() {
    WorkspaceContainerFactory f = factory();
    f.timezone = Optional.of("Pacific/Auckland");

    List<String> argv = f.forWorkspace("repo12345678abc", "work", "main", null).toRunArgv();

    assertSequence(argv, "-e", "TZ=Pacific/Auckland");
  }

  @Test
  void aBlankMemoryLimitDisablesTheCap() {
    WorkspaceContainerFactory f = factory();
    f.memoryLimit = Optional.of("  ");

    List<String> argv = f.forWorkspace("repo12345678abc", "work", "main", null).toRunArgv();

    assertFalse(argv.contains("--memory"), argv.toString());
    assertFalse(argv.contains("--memory-swap"), argv.toString());
  }

  @Test
  void configuredPidsAndCpuLimitsFlowIntoTheArgv() {
    WorkspaceContainerFactory f = factory();
    f.pidsLimit = Optional.of("2048");
    f.cpus = Optional.of("2.5");

    List<String> argv = f.forWorkspace("repo12345678abc", "work", "main", null).toRunArgv();

    assertSequence(argv, "--pids-limit", "2048");
    assertSequence(argv, "--cpus", "2.5");
  }

  @Test
  void blankingAVolumeOmitsOnlyThatMount() {
    WorkspaceContainerFactory f = factory();
    f.claudeVolume = "";
    f.pnpmVolume = "";

    List<String> argv = f.forWorkspace("repo12345678abc", "work", "main", null).toRunArgv();

    // The blanked caches drop their mount (and, for claude/kimi, the credential-dir env too)...
    assertFalse(argv.contains("qits_shared_dot_claude:/claude-home"), argv.toString());
    assertFalse(argv.contains("CLAUDE_CONFIG_DIR=/claude-home/.claude"), argv.toString());
    assertFalse(argv.contains("KIMI_CODE_HOME=/claude-home/.kimi-code"), argv.toString());
    assertFalse(argv.contains("qits_shared_pnpm:/caches/pnpm"), argv.toString());
    // ...while the still-configured Maven cache stays.
    assertSequence(argv, "-v", "qits_shared_m2:/caches/m2");
    // Everything else still present, incl. an empty parent label for the null parent.
    assertTrue(argv.contains("--add-host=host.docker.internal:host-gateway"), argv.toString());
    assertSequence(argv, "--label", "qits.repository=repo12345678abc");
    assertSequence(argv, "--label", "qits.parent=");
    assertSequence(argv, "--name", "qits-ws-work-repo1234");
  }

  /** Assert {@code first} appears immediately followed by {@code second}. */
  private static void assertSequence(List<String> argv, String first, String second) {
    for (int i = 0; i < argv.size() - 1; i++) {
      if (first.equals(argv.get(i)) && second.equals(argv.get(i + 1))) {
        return;
      }
    }
    throw new AssertionError("expected [" + first + ", " + second + "] in " + argv);
  }
}
