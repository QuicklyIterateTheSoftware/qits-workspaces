package eu.wohlben.qits.domain.repository.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Full end-to-end integration test of the <strong>disposable workspace container</strong> lifecycle
 * against the <em>real</em> stack — a launched app (real REST endpoints, the in-process JGit smart-
 * HTTP {@code /git} server) plus a real docker engine and the {@code qits/workspace} image. Unlike
 * the {@code FakeContainerRuntime}-backed unit tests, this exercises the actual transport: a
 * container clones its branch over HTTP from the running app and pushes back the same way, and a
 * lost container is re-provisioned from the durable branch.
 *
 * <p>{@link QuarkusIntegrationTest} launches the built artifact as a separate process, so the
 * {@code FakeContainerRuntime @Mock} that shadows {@code DockerExecutor} in unit tests does not
 * apply — the real {@code DockerExecutor} runs.
 *
 * <p>Part of the <strong>extended</strong> suite (opt in with {@code ./mvnw verify -Pextended}). It
 * self-skips when docker, the image, or container→host reachability is absent, so it is safe to run
 * anywhere. Build the image first: {@code docker build -t qits/workspace docker/workspace}. On a
 * host where a container cannot reach {@code host.docker.internal:8080} (e.g. some WSL2 setups)
 * this test skips; that reachability is the same prerequisite the feature needs to work at all.
 */
@QuarkusIntegrationTest
@TestProfile(WorkspaceRecreateIT.Profile.class)
@Tag("extended")
public class WorkspaceRecreateIT {

  private static final String IMAGE =
      System.getProperty("qits.workspace.image", "qits/workspace:latest");
  private static final String GIT_HOST =
      System.getProperty("qits.workspace.git-host", "host.docker.internal");
  private static final int PORT = 8080;

  /**
   * Runs against a throwaway data dir + in-memory DB, on the fixed port the containers clone from.
   */
  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path dataDir = Files.createTempDirectory("qits-recreate-it");
        Map<String, String> overrides = new java.util.HashMap<>();
        overrides.put("quarkus.http.port", String.valueOf(PORT));
        overrides.put("quarkus.http.test-port", String.valueOf(PORT));
        overrides.put("qits.workspace.qits-port", String.valueOf(PORT));
        overrides.put("qits.repositories.data-dir", dataDir.toString());
        overrides.put("quarkus.datasource.jdbc.url", "jdbc:h2:mem:recreate-it;DB_CLOSE_DELAY=-1");
        overrides.put("qits.speech.warmup-on-start", "false");
        // The address a container uses to reach this app for clone/push. Defaults to
        // host.docker.internal (so the test self-skips where that isn't container-reachable); on
        // WSL2 + Docker Desktop pass the distro's eth0 IP: -Dqits.workspace.git-host=<ip>.
        overrides.put("qits.workspace.git-host", GIT_HOST);
        return overrides;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private String repoId;

  @AfterEach
  public void cleanupContainers() {
    if (repoId == null) {
      return;
    }
    for (String name : dockerLines("ps", "-aq", "--filter", "label=qits.repository=" + repoId)) {
      if (!name.isBlank()) {
        docker("rm", "-f", name);
      }
    }
  }

  @Test
  public void aLostContainerIsRecreatedFromOriginAndUnpushedWorkIsGone() throws Exception {
    assumeTrue(dockerAndImageAvailable(), "docker + " + IMAGE + " required for this IT");
    assumeTrue(
        containerCanReachGitHost(),
        "a container must be able to reach "
            + GIT_HOST
            + ":"
            + PORT
            + " (see -Dqits.workspace.git-host)");

    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();

    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRequest(
                    "Recreate IT", null))
            .when()
            .post("/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    // Creating the repository clones the fixture and stands up the main worktree's container by
    // cloning over the /git server — the first real container clone-over-HTTP round trip.
    repoId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.domain.project.api.ProjectController
                    .CreateProjectRepositoryRequest(fixtureUrl, null))
            .when()
            .post("/api/projects/" + projectId + "/repositories")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("repository.id");

    // A worktree forked off master, materialized as a real container cloned from origin.
    given()
        .contentType(ContentType.JSON)
        .body(
            new WorktreeController.CreateWorktreeRequest(
                "recreate-wt", "master", "recreate-branch", null))
        .when()
        .post("/api/repositories/" + repoId + "/worktrees")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("worktree.runtimeStatus", equalTo("RUNNING"));

    String container = "qits-wt-recreate-wt-" + shortRepo(repoId);

    // Commit and PUSH work through the container (over the real /git receive-pack)...
    execOk(
        container,
        "cd /workspace && echo pushed > pushed.txt && git add . && git commit -q -m pushed"
            + " && git push -q origin recreate-branch");
    String pushed = execOk(container, "cd /workspace && git rev-parse HEAD").trim();

    // ...then make a commit that is NEVER pushed — it lives only in the container.
    execOk(
        container,
        "cd /workspace && echo local > local.txt && git add . && git commit -q -m local");
    String unpushed = execOk(container, "cd /workspace && git rev-parse HEAD").trim();
    assertNotEquals(pushed, unpushed, "the local commit advanced HEAD past the pushed one");

    // The container vanishes out-of-band. The branch — the pushed work — is safe in origin.
    docker("rm", "-f", container);

    // Hitting the ensure-container endpoint re-provisions from the durable branch.
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/worktrees/recreate-wt/ensure-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("runtimeStatus", equalTo("RUNNING"));

    // The recreated container is a fresh clone of origin: it has the pushed commit...
    assertEquals(
        pushed,
        execOk(container, "cd /workspace && git rev-parse HEAD").trim(),
        "the recreated container is at the pushed commit");
    // ...and the unpushed commit died with the old container (documents the §D loss window).
    assertNotEquals(
        0,
        execExit(container, "cd /workspace && git cat-file -e " + unpushed),
        "an unpushed commit does not survive the container's death");
  }

  private static String shortRepo(String repoId) {
    return repoId.length() > 8 ? repoId.substring(0, 8) : repoId;
  }

  private boolean dockerAndImageAvailable() {
    try {
      return new ProcessBuilder("docker", "image", "inspect", IMAGE).start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Whether a throwaway container can reach the launched app over HTTP — any HTTP status counts
   * (the connection is what matters); only a failure to connect (curl code {@code 000}) means the
   * host is unreachable and the test should skip.
   */
  private boolean containerCanReachGitHost() {
    ExecResult r =
        runCapture(
            List.of(
                "docker",
                "run",
                "--rm",
                "--add-host=host.docker.internal:host-gateway",
                IMAGE,
                "curl",
                "-s",
                "-o",
                "/dev/null",
                "-w",
                "%{http_code}",
                "--max-time",
                "8",
                "http://" + GIT_HOST + ":" + PORT + "/api/projects"));
    System.out.println("[recreate-it] reachability probe http_code=" + r.output().trim());
    return !r.output().trim().equals("000") && !r.output().isBlank();
  }

  /** Runs a shell command inside the container, asserting exit 0, returning stdout. */
  private String execOk(String container, String script) {
    ExecResult r = execCapture(container, script);
    assertEquals(
        0, r.exitCode(), "exec failed [" + r.exitCode() + "]: " + script + "\n" + r.output());
    return r.output();
  }

  /** Runs a shell command inside the container, returning only its exit code. */
  private int execExit(String container, String script) {
    return execCapture(container, script).exitCode();
  }

  private ExecResult execCapture(String container, String script) {
    return runCapture(List.of("docker", "exec", container, "bash", "-lc", script));
  }

  private void docker(String... args) {
    List<String> cmd = new ArrayList<>();
    cmd.add("docker");
    cmd.addAll(List.of(args));
    runCapture(cmd);
  }

  private int dockerExit(List<String> cmd) {
    return runCapture(cmd).exitCode();
  }

  private List<String> dockerLines(String... args) {
    List<String> cmd = new ArrayList<>();
    cmd.add("docker");
    cmd.addAll(List.of(args));
    return List.of(runCapture(cmd).output().split("\\R"));
  }

  private record ExecResult(int exitCode, String output) {}

  private ExecResult runCapture(List<String> command) {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    try {
      Process p = pb.start();
      String output;
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.lines().collect(Collectors.joining("\n"));
      }
      int exit = p.waitFor();
      return new ExecResult(exit, output);
    } catch (Exception e) {
      return new ExecResult(-1, e.getMessage());
    }
  }
}
