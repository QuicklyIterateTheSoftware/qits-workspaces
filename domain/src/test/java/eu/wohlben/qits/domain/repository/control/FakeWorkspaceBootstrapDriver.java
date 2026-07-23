package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.bootstrap.control.BootstrapCommandService;
import eu.wohlben.qits.domain.bootstrap.dto.BootstrapCommandDto;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;

/**
 * Test double for {@link WorkspaceBootstrapDriver}: stands in for the in-container
 * workspace-daemon's bootstrap chain (docs/epics/qits-workspace-daemon/ Part 3). It plays the
 * daemon — resolving the chain from the DB and running each step through the injected {@link
 * ContainerRuntime} ({@link FakeContainerRuntime} runs real host processes, so ordering, {@code
 * check}-skip and fail-fast are exercised end-to-end), streaming each step's phase/output/outcome
 * to the {@link StepSink} exactly as the daemon's {@code BootstrapRunner} does over the socket.
 *
 * <p>The daemon reads its own {@code .qits-config.yml}; here the chain comes from the DB (the
 * config is reconciled into it) — the same rows the host records outcomes against, so the runner's
 * name↔row mapping resolves. Per-step timeout is <b>not</b> reproduced (that is the daemon module's
 * {@code BootstrapRunnerTest}); registered as a {@link Mock} so every {@code @QuarkusTest}
 * exercising the runner wiring gets the chain without a real container or daemon. Keep the {@code
 * domain}/{@code service} copies in sync (cli never bootstraps).
 */
@Mock
@ApplicationScoped
public class FakeWorkspaceBootstrapDriver implements WorkspaceBootstrapDriver {

  @Inject ContainerRuntime containers;
  @Inject BootstrapCommandService bootstrapCommandService;

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

  /** Run the chain (or one named step) through the fake container, streaming to {@code sink}. */
  private Result runChain(String repoId, String workspaceId, String onlyName, StepSink sink) {
    String container = containers.containerName(workspaceId, repoId);
    boolean ok = true;
    for (BootstrapCommandDto command : bootstrapCommandService.resolveAll(repoId)) {
      String stepName = QitsConfig.baseName(command.name());
      if (onlyName != null && !onlyName.isBlank() && !onlyName.equals(stepName)) {
        continue;
      }
      if (command.checkScript() != null && !command.checkScript().isBlank()) {
        sink.onStep(stepName, "CHECK");
        ContainerRuntime.ExecResult check =
            containers.exec(
                container,
                "/workspace",
                command.environment(),
                line -> sink.onLine(stepName, line),
                "bash",
                "-lc",
                command.checkScript());
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
              command.environment(),
              line -> sink.onLine(stepName, line),
              "bash",
              "-lc",
              command.executeScript());
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
