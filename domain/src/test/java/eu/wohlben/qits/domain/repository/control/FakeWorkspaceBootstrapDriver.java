package eu.wohlben.qits.domain.repository.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Test double for {@link WorkspaceBootstrapDriver}: stands in for the in-container
 * workspace-daemon's bootstrap chain (docs/epics/qits-workspace-daemon/ Part 3). It plays the
 * daemon — resolving the chain from the fake checkout's committed {@code .qits-config.yml} (the
 * {@link FakeContainerRuntime} host clone at {@code <data-dir>/<repoId>/workspaces/<workspaceId>})
 * and running each step through the injected {@link ContainerRuntime} (the fake runs real host
 * processes, so ordering, {@code check}-skip and fail-fast are exercised end-to-end), streaming
 * each step's phase/output/outcome to the {@link StepSink} exactly as the daemon's {@code
 * BootstrapRunner} does over the socket.
 *
 * <p>The real daemon parses its own checkout; this fake mirrors that with the kept {@link
 * QitsConfigParser} over the fake checkout path. Per-step timeout is <b>not</b> reproduced (that is
 * the daemon module's {@code BootstrapRunnerTest}); registered as a {@link Mock} so every
 * {@code @QuarkusTest} exercising the runner wiring gets the chain without a real container or
 * daemon. Keep the {@code domain}/{@code service} copies in sync (cli never bootstraps).
 */
@Mock
@ApplicationScoped
public class FakeWorkspaceBootstrapDriver implements WorkspaceBootstrapDriver {

  @Inject ContainerRuntime containers;

  @Inject QitsConfigParser configParser;

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  @Override
  public Optional<Result> awaitBootstrap(
      String repoId,
      String workspaceId,
      StepSink sink,
      Duration connectTimeout,
      Duration chainTimeout) {
    return Optional.of(runChain(repoId, workspaceId, null, sink));
  }

  @Override
  public Optional<Result> runBootstrap(
      String repoId, String workspaceId, String name, StepSink sink, Duration chainTimeout) {
    return Optional.of(runChain(repoId, workspaceId, name, sink));
  }

  /**
   * The chain the daemon would see: the fake checkout's committed config bootstrap list, from the
   * default {@code .config/qits/repository.yml} or the legacy {@code .qits-config.yml}.
   */
  private List<QitsConfig.BootstrapDecl> readChain(String repoId, String workspaceId) {
    Path checkout = Path.of(dataDir, repoId, "workspaces", workspaceId);
    Path config = checkout.resolve(QitsConfigParser.CONFIG_PATH);
    if (!Files.exists(config)) {
      config = checkout.resolve(QitsConfigParser.LEGACY_CONFIG_PATH);
    }
    if (!Files.exists(config)) {
      return List.of();
    }
    try {
      return configParser.parse(Files.readString(config)).bootstrap();
    } catch (Exception e) {
      return List.of(); // an unreadable/invalid file means no chain, like the daemon's empty config
    }
  }

  /** Run the chain (or one named step) through the fake container, streaming to {@code sink}. */
  private Result runChain(String repoId, String workspaceId, String onlyName, StepSink sink) {
    String container = containers.containerName(workspaceId, repoId);
    boolean ok = true;
    for (QitsConfig.BootstrapDecl step : readChain(repoId, workspaceId)) {
      String stepName = step.name();
      if (onlyName != null && !onlyName.isBlank() && !onlyName.equals(stepName)) {
        continue;
      }
      if (step.check() != null && !step.check().isBlank()) {
        sink.onStep(stepName, "CHECK");
        ContainerRuntime.ExecResult check =
            containers.exec(
                container,
                "/workspace",
                step.environment(),
                line -> sink.onLine(stepName, line),
                "bash",
                "-lc",
                step.check());
        if (check.exitCode() != 0) {
          sink.onStep(stepName, "SKIP");
          sink.onOutcome(stepName, "SKIPPED", check.exitCode());
          continue;
        }
      }
      sink.onStep(stepName, "EXECUTE");
      ContainerRuntime.ExecResult exec =
          containers.exec(
              container,
              "/workspace",
              step.environment(),
              line -> sink.onLine(stepName, line),
              "bash",
              "-lc",
              step.execute());
      boolean stepOk = exec.exitCode() == 0;
      sink.onOutcome(stepName, stepOk ? "SUCCEEDED" : "FAILED", exec.exitCode());
      if (!stepOk) {
        ok = false;
        break; // fail-fast: abort the rest of the chain
      }
    }
    return new Result(ok);
  }
}
