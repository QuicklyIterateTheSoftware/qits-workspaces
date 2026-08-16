package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * A {@link WorkspaceContainerFactory} configured by hand, for the plain-JUnit tests of the
 * orchestrator adapter in {@code containershost}.
 *
 * <p><b>It lives in this package because the factory's {@code @ConfigProperty} fields are
 * package-private</b>, and it lives in the {@code service} test tree because that is where the
 * adapter is. Both facts together are why this is a class rather than a method on the test: the test
 * cannot sit in this package (it is about a class in another one) and cannot reach these fields from
 * its own.
 *
 * <p>The values mirror what the service ships, and every one of them is asserted somewhere by the
 * adapter's test — an image with a port and a calver tag, the three shared volumes at their fixed
 * mounts, a hard memory cap and no pids/cpus cap, a deterministic qits host so the daemon's
 * dial-home URL is a literal rather than this machine's. {@code domain}'s own
 * {@code WorkspaceContainerFactoryTest} builds the same thing for the same reason; the two are
 * duplicated because the modules do not share a test classpath.
 */
public final class TestWorkspaceContainerFactory {

  /** The image every test container runs. Invented, and deliberately not a copy of the shipped pin. */
  public static final String IMAGE = "localhost:8081/qits/workspace:2026.101.1";

  private TestWorkspaceContainerFactory() {}

  /** A factory with the per-workspace {@code /workspace} volume on — the shipped default. */
  public static WorkspaceContainerFactory persistent() {
    return build(true);
  }

  /** A factory with {@code qits.workspace.persist-workspace} off — the kill switch's shape. */
  public static WorkspaceContainerFactory ephemeralWorkspace() {
    return build(false);
  }

  /**
   * A factory whose workspaces hold a commissioned platform credential — the shape a deployment with
   * an issuer wired has. The shipped posture is the other one, which is why the two builders above
   * leave the lookup empty.
   */
  public static WorkspaceContainerFactory commissioned(String clientId, String secret) {
    WorkspaceContainerFactory f = build(true);
    f.credentials = StubInstance.of(rowId -> Optional.of(new WorkspaceCredential(clientId, secret)));
    return f;
  }

  private static WorkspaceContainerFactory build(boolean persistWorkspace) {
    WorkspaceContainerFactory f = new WorkspaceContainerFactory();
    f.image = IMAGE;
    f.projectsUrl = "http://qits-projects:8080";
    f.observabilityUrl = "http://qits-observability:8080";
    f.network = "qits-net";
    f.claudeVolume = "qits_shared_dot_claude";
    f.claudeMount = "/claude-home";
    f.mavenVolume = "qits_shared_m2";
    f.pnpmVolume = "qits_shared_pnpm";
    f.workspaceVolumePrefix = "qits_workspace_";
    f.persistWorkspace = persistWorkspace;
    f.timezone = Optional.of("UTC");
    f.memoryLimit = Optional.of("4g");
    f.pidsLimit = Optional.empty();
    f.cpus = Optional.empty();
    f.gitIdentity = identity();
    f.qitsHostResolver = resolver();
    f.qitsPort = "8080";
    f.containerGitUrl = "http://qits-platform-edge:8080";
    f.gitHostAudience = "qits-githost";
    f.idpUrl = "http://qits-idp:8080/idp";
    f.machineAudience = "qits-workspaces";
    f.daemonApiToken = "qits-workspace-daemon";
    f.bootstrapAutorunEnabled = true;
    f.autoPushEnabled = true;
    f.servicesAutostartEnabled = true;
    f.serviceReadyGraceMs = 10000;
    f.serviceBackoffInitialMs = 1000;
    f.serviceBackoffMaxMs = 30000;
    f.serviceStopGraceMs = 5000;
    f.nameResolver =
        StubInstance.of(
            repoId ->
                Optional.of(new RepositoryAddressResolver.ProjectScopedName("proj-1", "my-repo")));
    f.repositories = StubInstance.empty();
    // No issuer wired — the shipped posture, so no container carries a commissioned pair.
    f.credentials = StubInstance.empty();
    return f;
  }

  private static GitIdentity identity() {
    GitIdentity identity = new GitIdentity();
    identity.name = "qits";
    identity.email = "qits@local";
    return identity;
  }

  /** An explicit host, so the daemon's dial-home URL is deterministic rather than this machine's. */
  private static QitsHostResolver resolver() {
    QitsHostResolver r = new QitsHostResolver();
    r.configured = "qits";
    return r;
  }
}
