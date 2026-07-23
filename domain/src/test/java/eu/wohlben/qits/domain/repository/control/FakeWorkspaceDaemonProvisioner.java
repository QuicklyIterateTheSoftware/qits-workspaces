package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.repository.entity.Workspace;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Test double for {@link WorkspaceDaemonProvisioner}: stands in for the in-container
 * workspace-daemon now that qits has <b>no host-driven clone fallback</b> — the daemon is the sole
 * provisioner (docs/epics/qits-workspace-daemon/ Part 2). On {@link #awaitProvision} it plays the
 * daemon: it clones the workspace's branch into the (fake) container's {@code /workspace} through
 * the injected {@link ContainerRuntime}, materializes submodules the way the daemon's {@code
 * Provisioner} does — a bounded, depth-capped walk over the checkout's own {@code .gitmodules} with
 * native relative-url resolution and per-submodule skip-on-failure (so there is <b>no DB
 * import-scoping</b>) — and reports the checked-out HEAD.
 *
 * <p>Registered as a {@link Mock} so every {@code @QuarkusTest} that provisions a workspace gets a
 * real checkout without a real container or daemon (the same way {@link FakeContainerRuntime}
 * stands in for docker). Keep the {@code domain}/{@code service} copies in sync (cli never
 * provisions).
 */
@Mock
@ApplicationScoped
public class FakeWorkspaceDaemonProvisioner implements WorkspaceDaemonProvisioner {

  // Any authority works: FakeContainerRuntime rewrites /git/<projectId>/<name> to its on-disk name
  // farm regardless of host/port, exactly like the real name-addressed git host does over HTTP.
  private static final String GIT_BASE = "http://qits:8080/git/";
  private static final int MAX_SUBMODULE_DEPTH = 10;

  @Inject WorkspaceRepository workspaces;
  @Inject ContainerRuntime containers;
  @Inject RepositoryNameResolver nameResolver;

  @Override
  public Optional<ProvisionResult> awaitProvision(
      String repoId,
      String workspaceId,
      Duration connectTimeout,
      Duration provisionTimeout,
      Consumer<String> onLine) {
    // (repoId, workspaceId) is the unique key — workspaceId alone repeats across repos (each repo
    // has
    // a "master" workspace), so resolving by it alone would target the wrong repo's container.
    Target target =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace ws =
                      workspaces
                          .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "no such workspace " + workspaceId + " in repo " + repoId));
                  String url =
                      nameResolver
                          .resolve(repoId)
                          .map(psn -> GIT_BASE + psn.projectId() + "/" + psn.name())
                          .orElse(GIT_BASE + repoId);
                  return new Target(ws.branch, url);
                });

    String container = containers.containerName(workspaceId, repoId);
    ContainerRuntime.ExecResult clone =
        containers.exec(
            container,
            null,
            Map.of(),
            onLine,
            "git",
            "clone",
            "--branch",
            target.branch(),
            target.url(),
            "/workspace");
    if (clone.exitCode() != 0) {
      return Optional.of(ProvisionResult.failed("fake self-clone failed: " + clone.output()));
    }
    materializeSubmodules(container, ".", 0, onLine);
    ContainerRuntime.ExecResult head =
        containers.exec(container, "/workspace", Map.of(), "git", "rev-parse", "HEAD");
    return Optional.of(ProvisionResult.ok(head.exitCode() == 0 ? head.output().trim() : ""));
  }

  /**
   * Materialize one level of the checkout's submodules from its own {@code .gitmodules}, then
   * descend into each that checked out — the host-side twin of the daemon's {@code
   * Provisioner.materializeSubmodules}. Relative urls resolve natively against the name-addressed
   * origin; a submodule whose gitlink isn't on this branch is skipped, and one whose update fails
   * (never imported → no served sibling) is skipped rather than failing the provision.
   */
  private void materializeSubmodules(
      String container, String rel, int depth, Consumer<String> onLine) {
    if (depth >= MAX_SUBMODULE_DEPTH) {
      return;
    }
    String gitmodules = ".".equals(rel) ? ".gitmodules" : rel + "/.gitmodules";
    ContainerRuntime.ExecResult listed =
        containers.exec(
            container,
            "/workspace",
            Map.of(),
            "git",
            "config",
            "--file",
            gitmodules,
            "--get-regexp",
            "^submodule\\..*\\.path$");
    if (listed.exitCode() != 0) {
      return;
    }
    List<String> present = new ArrayList<>();
    for (String path : parsePaths(listed.output())) {
      if (containers
              .exec(
                  container,
                  "/workspace",
                  Map.of(),
                  "git",
                  "-C",
                  rel,
                  "ls-files",
                  "--error-unmatch",
                  "--",
                  path)
              .exitCode()
          != 0) {
        continue; // gitlink absent on this branch
      }
      ContainerRuntime.ExecResult update =
          containers.exec(
              container,
              "/workspace",
              Map.of(),
              onLine,
              "git",
              "-C",
              rel,
              "submodule",
              "update",
              "--init",
              "--",
              path);
      if (update.exitCode() == 0) {
        present.add(path);
      }
    }
    for (String path : present) {
      materializeSubmodules(
          container, ".".equals(rel) ? path : rel + "/" + path, depth + 1, onLine);
    }
  }

  /** Extract the submodule paths from {@code git config --get-regexp ...path} output. */
  private static List<String> parsePaths(String output) {
    List<String> paths = new ArrayList<>();
    for (String raw : output.split("\n")) {
      String line = raw.trim();
      int space = line.indexOf(' ');
      if (space > 0
          && line.startsWith("submodule.")
          && line.substring(0, space).endsWith(".path")) {
        String path = line.substring(space + 1).trim();
        if (!path.isEmpty()) {
          paths.add(path);
        }
      }
    }
    return paths;
  }

  private record Target(String branch, String url) {}
}
