package eu.wohlben.qits.workspaces.control;

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
 * <p>The real daemon parses its own checkout; this fake mirrors that with a minimal read of the
 * {@code bootstrap:} block over the fake checkout path (see {@link #readChain}). Per-step timeout is <b>not</b> reproduced (that is
 * the daemon module's {@code BootstrapRunnerTest}); registered as a {@link Mock} so every
 * {@code @QuarkusTest} exercising the runner wiring gets the chain without a real container or
 * daemon. Keep the {@code domain}/{@code service} copies in sync (cli never bootstraps).
 */
@Mock
@ApplicationScoped
public class FakeWorkspaceBootstrapDriver implements WorkspaceBootstrapDriver {

  @Inject ContainerRuntime containers;

  @Inject eu.wohlben.qits.workspaces.persistence.WorkspaceRepository workspaces;

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  /** The persistent outcome recorders, fed on every step outcome like the real registry. */
  private final java.util.concurrent.CopyOnWriteArrayList<OutcomeSink> outcomeSinks =
      new java.util.concurrent.CopyOnWriteArrayList<>();

  @Override
  public void subscribe(OutcomeSink sink) {
    outcomeSinks.add(sink);
  }

  /**
   * Play the daemon reporting a step outcome for a chain the host never awaited (its own HTTP run
   * verb) — delivered only to the persistent {@link OutcomeSink}s, exactly like the registry's
   * fan-out of an unawaited {@code BootstrapOutcome} frame.
   */
  public void reportUnawaitedOutcome(
      String repoId, String workspaceId, Long rowId, String stepName, String outcome, Integer exitCode) {
    for (OutcomeSink sink : outcomeSinks) {
      sink.onOutcome(repoId, workspaceId, rowId, stepName, outcome, exitCode);
    }
  }

  @Override
  public Optional<Result> awaitBootstrap(
      Long workspaceId, StepSink sink, Duration connectTimeout, Duration chainTimeout) {
    return Optional.of(runChain(workspaceId, null, sink));
  }

  @Override
  public Optional<Result> runBootstrap(
      Long workspaceId, String name, StepSink sink, Duration chainTimeout) {
    return Optional.of(runChain(workspaceId, name, sink));
  }

  /**
   * The chain the daemon would run, read from the fake checkout's committed config.
   *
   * <p>The real daemon parses its own checkout with its own {@code ConfigParser}. That parser is
   * daemon-side, and the monorepo's host-side {@code QitsConfigParser} belongs to the repositories
   * context and brings snakeyaml with it — neither is in this jar. So this reads only the {@code
   * bootstrap:} block, and only the fixed shape the tests write (one {@code - name:} per step, with
   * {@code execute:} and optional {@code check:}, single-quoted scalars). Anything richer is the
   * daemon module's {@code ConfigParserTest}, not this fake's business.
   */
  private List<QitsConfig.BootstrapDecl> readChain(String repoId, String workspaceId) {
    Path checkout = Path.of(dataDir, repoId, "workspaces", workspaceId);
    for (String candidate : List.of(".config/qits/repository.yml", ".qits-config.yml")) {
      Path file = checkout.resolve(candidate);
      if (Files.isRegularFile(file)) {
        try {
          return parseBootstrap(Files.readAllLines(file));
        } catch (java.io.IOException e) {
          throw new IllegalStateException("unreadable staged config: " + file, e);
        }
      }
    }
    return List.of();
  }

  /** The {@code bootstrap:} list, in file order. */
  private static List<QitsConfig.BootstrapDecl> parseBootstrap(List<String> lines) {
    List<QitsConfig.BootstrapDecl> steps = new java.util.ArrayList<>();
    boolean inBootstrap = false;
    String name = null;
    String execute = null;
    String check = null;
    for (String raw : lines) {
      String line = raw.stripTrailing();
      if (line.isBlank()) {
        continue;
      }
      if (!line.startsWith(" ")) {
        if (name != null) {
          steps.add(new QitsConfig.BootstrapDecl(null, name, null, execute, check, null));
          name = null;
          execute = null;
          check = null;
        }
        inBootstrap = line.stripTrailing().equals("bootstrap:");
        continue;
      }
      if (!inBootstrap) {
        continue;
      }
      String entry = line.strip();
      if (entry.startsWith("- ")) {
        if (name != null) {
          steps.add(new QitsConfig.BootstrapDecl(null, name, null, execute, check, null));
          execute = null;
          check = null;
        }
        entry = entry.substring(2).strip();
      }
      int colon = entry.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String key = entry.substring(0, colon).strip();
      String value = unquote(entry.substring(colon + 1).strip());
      switch (key) {
        case "name" -> name = value;
        case "execute" -> execute = value;
        case "check" -> check = value;
        default -> {
          // not part of the chain shape these tests write
        }
      }
    }
    if (name != null) {
      steps.add(new QitsConfig.BootstrapDecl(null, name, null, execute, check, null));
    }
    return List.copyOf(steps);
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("'") && value.endsWith("'"))
            || (value.startsWith("\"") && value.endsWith("\"")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  /** Run the chain (or one named step) through the fake container, streaming to {@code sink}. */
  /** The workspace the daemon would be running in, by id — what the real driver is keyed on. */
  private eu.wohlben.qits.workspaces.entity.Workspace requireWorkspace(Long rowId) {
    return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .call(
            () ->
                workspaces
                    .findActiveById(rowId)
                    .orElseThrow(() -> new IllegalStateException("no such workspace " + rowId)));
  }

  private Result runChain(Long rowId, String onlyName, StepSink sink) {
    eu.wohlben.qits.workspaces.entity.Workspace ws = requireWorkspace(rowId);
    String repoId = ws.repositoryId;
    String workspaceId = ws.workspaceId;
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
          reportUnawaitedOutcome(
              repoId, workspaceId, rowId, stepName, "SKIPPED", check.exitCode());
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
      // The real registry fans every outcome frame to the persistent recorders too — mirror that,
      // or the awaited path would stop producing rows now that the awaiting sink writes none.
      reportUnawaitedOutcome(
          repoId, workspaceId, rowId, stepName, stepOk ? "SUCCEEDED" : "FAILED", exec.exitCode());
      if (!stepOk) {
        ok = false;
        break; // fail-fast: abort the rest of the chain
      }
    }
    return new Result(ok);
  }
}
