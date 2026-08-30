package eu.wohlben.qits.workspaces.stories.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.userflows.Labels;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <b>qits-githost, over real smart HTTP</b> — the far side of every git operation this service
 * performs, and the only place that traffic exists.
 *
 * <h2>Why a real git server and not a local bare</h2>
 *
 * <p>The {@code @QuarkusTest} suites point {@code GitHostAddress} at a bare on disk with a
 * test-scoped CDI bean, which is exactly right for asserting that a fast-forward compare-and-swap
 * happens. A <b>packaged</b> process has no such bean: it carries {@code ConfiguredGitHostAddress},
 * which builds {@code <qits.githost.url>/git/<projectId>/<repoName>} and speaks HTTP — and {@code
 * RepoMirror.platformArgv} refuses to run any http(s) git argv without a machine bearer to hang on
 * {@code -c http.extraHeader}. So a story about the release door needs a git host that answers over
 * HTTP, or it proves nothing about the artifact that ships.
 *
 * <p>This is that host: the JDK's own {@link HttpServer} shelling {@code git http-backend}, the CGI
 * program git ships for precisely this, over a project root of bare repositories. Reproduced: the
 * wire protocol, the url shape, ref advertisement, fast-forward refusal, push options. <b>Not</b>
 * reproduced: qits-githost's authorization or its protected-ref hook — this exports what it serves
 * unconditionally, and the assertions about who may push belong in that repository's own suite. The
 * one thing borrowed from the hook's job is {@link #pushOptionsFor}, a {@code pre-receive} that
 * records the options a push carried, because that is the only way to see a {@code --push-option}
 * from outside the pushing process.
 *
 * <h2>The recording, and the tap</h2>
 *
 * <p>Every answered request is appended to a file as {@code METHOD PATH STATUS} — <b>before</b> the
 * response is written, so a line is on disk by the time its effect is observable, and a story that
 * saw a push land can rely on the line for it already being there. Nothing needs to be awaited: the
 * release door is synchronous, and the workspace provision's git happens on the request thread of a
 * call the story waits for.
 *
 * <p>The label drops the query, exactly as the framework's shipped RestAssured tap does. That folds
 * the two ref advertisements — {@code ?service=git-upload-pack} and {@code ?service=git-receive-pack}
 * — into one {@code GET …/info/refs} arrow, which is a real loss and a deliberate one: the
 * read/write distinction survives on the arrow that carries it, {@code POST …/git-upload-pack}
 * versus {@code POST …/git-receive-pack}, and those are the two a reader is actually asking about.
 *
 * <p>Every edge is {@code qits-workspaces -> qits-githost}: direction is always who dialled, so
 * there is no actor to stamp and no hand-over to get wrong.
 *
 * <h2>A file, and no floor</h2>
 *
 * <p>The server is started by the test profile and read by a story method, and those need not share
 * a classloader — a static list written by one is not the list the other reads. A file is a path
 * both resolve identically, and it is <b>wiped when the server starts</b>, which is what makes a
 * floor unnecessary: everything in the recording belongs to this run. {@link StoryPeers} draws the
 * same conclusion for a sharper reason — some of its traffic really does happen before any story.
 */
public final class StoryGitHost {

  /** How a diagram names the far side. */
  public static final String SERVICE_NAME = "qits-githost";

  private static final String SOURCE_ID = "story-git-host";

  private static final String PORT_PROPERTY = "qits.test.story-githost.port";

  /** The CGI program itself. On the {@code PATH} everywhere git is installed. */
  private static final String HTTP_BACKEND = "http-backend";

  /** Everything this host serves, and {@code GIT_PROJECT_ROOT} for the CGI. */
  private static final Path ROOT = Path.of("target", "story-githost");

  /** One line per answered request, the shape an access log has. */
  private static final Path ACCESS_LOG = ROOT.resolve("access.log");

  /** The route qits-githost serves a repository at, and the segment this fixture mirrors. */
  private static final String GIT_SEGMENT = "/git/";

  private static final Object LOCK = new Object();

  private static boolean registered;

  private static int harvested;

  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  private StoryGitHost() {}

  // --- the server -------------------------------------------------------------------------------

  /**
   * Start the host once per JVM and park its port, wiping whatever an earlier run left behind.
   * Called from the test profile, which is the only place that knows the url in time.
   */
  public static synchronized String ensureStarted() {
    String port = System.getProperty(PORT_PROPERTY);
    if (port != null) {
      return baseUrl(Integer.parseInt(port));
    }
    wipe();
    HttpServer server;
    try {
      Files.createDirectories(ROOT);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException("could not start the story git host", e);
    }
    // A pool rather than the default single caller thread: a `git clone` opens the ref
    // advertisement and the pack request on separate connections, and a CGI child that has to wait
    // for another CGI child to exit is a deadlock waiting for a slow afternoon.
    server.setExecutor(Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "story-githost");
      thread.setDaemon(true);
      return thread;
    }));
    server.createContext("/", StoryGitHost::handle);
    server.start();
    System.setProperty(PORT_PROPERTY, String.valueOf(server.getAddress().getPort()));
    return baseUrl(server.getAddress().getPort());
  }

  /** Scheme, host and port with no path — the shape {@code qits.githost.url} takes. */
  public static String baseUrl() {
    return baseUrl(Integer.parseInt(System.getProperty(PORT_PROPERTY)));
  }

  private static String baseUrl(int port) {
    return "http://127.0.0.1:" + port;
  }

  private static void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String query = exchange.getRequestURI().getRawQuery();
    String method = exchange.getRequestMethod();
    byte[] body = exchange.getRequestBody().readAllBytes();

    CgiResponse answer;
    try {
      answer = gitHttpBackend(method, path, query == null ? "" : query, exchange, body);
    } catch (Exception failure) {
      answer =
          new CgiResponse(
              500, Map.of(), String.valueOf(failure).getBytes(StandardCharsets.UTF_8));
    }

    // Recorded BEFORE the answer leaves — see the class javadoc.
    record(method, path, answer.status());
    answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
    exchange.sendResponseHeaders(answer.status(), answer.body().length == 0 ? -1 : answer.body().length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(answer.body());
    }
  }

  // --- CGI plumbing -----------------------------------------------------------------------------

  /** A CGI program's answer: its {@code Status:} line, the headers it set, and the body. */
  private record CgiResponse(int status, Map<String, String> headers, byte[] body) {}

  /**
   * Run {@code git http-backend} with the CGI environment it expects and split its response.
   *
   * <p>stdin is written on its own thread while stdout is read on this one: a CGI program that
   * starts answering before it has consumed its input deadlocks a write-then-read implementation,
   * and a pack push is exactly the request large enough to find that out.
   */
  private static CgiResponse gitHttpBackend(
      String method, String pathInfo, String query, HttpExchange exchange, byte[] body)
      throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder("git", HTTP_BACKEND);
    Map<String, String> env = pb.environment();
    env.put("GIT_PROJECT_ROOT", ROOT.toAbsolutePath().toString());
    env.put("GIT_HTTP_EXPORT_ALL", "1");
    env.put("REQUEST_METHOD", method);
    env.put("PATH_INFO", pathInfo);
    env.put("QUERY_STRING", query);
    env.put("REMOTE_ADDR", "127.0.0.1");
    // http-backend allows receive-pack for an AUTHENTICATED caller; the real host authenticates the
    // bearer this service sends, and the bares below also carry `http.receivepack=true`, so this is
    // the belt to that braces. The name is a fixture's, not a credential.
    env.put("REMOTE_USER", "story");
    env.put("CONTENT_LENGTH", String.valueOf(body.length));
    header(exchange, "Content-Type").ifPresent(value -> env.put("CONTENT_TYPE", value));
    // git gzips an upload-pack request when it is large enough; http-backend inflates it when told.
    header(exchange, "Content-Encoding")
        .ifPresent(value -> env.put("HTTP_CONTENT_ENCODING", value));
    header(exchange, "Git-Protocol").ifPresent(value -> env.put("HTTP_GIT_PROTOCOL", value));

    Process process = pb.start();
    Thread.startVirtualThread(
        () -> {
          try (var stdin = process.getOutputStream()) {
            stdin.write(body);
          } catch (Exception ignored) {
            // the child closed its input; whatever it already read is what it answers on
          }
        });
    Thread stderr =
        Thread.startVirtualThread(
            () -> {
              try (var err = process.getErrorStream()) {
                err.readAllBytes(); // drained, so a chatty failure cannot fill the pipe and wedge us
              } catch (Exception ignored) {
                // nothing to report beyond the exit code
              }
            });
    byte[] raw = process.getInputStream().readAllBytes();
    process.waitFor();
    stderr.join(TimeUnit.SECONDS.toMillis(5));
    return parseCgi(raw);
  }

  private static Optional<String> header(HttpExchange exchange, String name) {
    return Optional.ofNullable(exchange.getRequestHeaders().getFirst(name));
  }

  /** Split a CGI response into headers and body at the first blank line, honouring {@code Status}. */
  private static CgiResponse parseCgi(byte[] raw) {
    int split = -1;
    int bodyAt = -1;
    for (int i = 0; i + 1 < raw.length; i++) {
      if (raw[i] == '\n' && raw[i + 1] == '\n') {
        split = i;
        bodyAt = i + 2;
        break;
      }
      if (i + 3 < raw.length
          && raw[i] == '\r'
          && raw[i + 1] == '\n'
          && raw[i + 2] == '\r'
          && raw[i + 3] == '\n') {
        split = i;
        bodyAt = i + 4;
        break;
      }
    }
    if (split < 0) {
      return new CgiResponse(500, Map.of(), raw);
    }
    int status = 200;
    Map<String, String> headers = new LinkedHashMap<>();
    String head = new String(raw, 0, split, StandardCharsets.ISO_8859_1);
    for (String line : head.split("\\R")) {
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String name = line.substring(0, colon).trim();
      String value = line.substring(colon + 1).trim();
      if (name.equalsIgnoreCase("Status")) {
        status = Integer.parseInt(value.split("\\s+")[0]);
      } else if (!name.equalsIgnoreCase("Content-Length")
          && !name.equalsIgnoreCase("Transfer-Encoding")) {
        // Both are ours to decide: the body is written whole, so the framing is this server's.
        headers.put(name, value);
      }
    }
    return new CgiResponse(status, headers, Arrays.copyOfRange(raw, bodyAt, raw.length));
  }

  // --- the repositories this host serves ---------------------------------------------------------

  /** Where one repository's bare lives — the path {@code PATH_INFO} resolves to under the root. */
  public static Path origin(String projectId, String name) {
    return ROOT.resolve("git").resolve(projectId).resolve(name).toAbsolutePath();
  }

  /**
   * Build one repository the way the platform holds it: {@code main} with two commits, a maven
   * reactor root for the release flow's version bump to render into, and a {@code pre-receive} hook
   * that records the options every later push carries.
   *
   * @param deployable whether the tree carries {@code .config/qits/deployments.yml} — the file that
   *     is the whole of a repository's answer to "do you deploy at all", and therefore the whole of
   *     whether a release promotes
   */
  public static void createRepository(String projectId, String name, boolean deployable) {
    Path origin = origin(projectId, name);
    Path work = null;
    try {
      Files.createDirectories(origin.getParent());
      run(origin.getParent().toFile(), "git", "init", "--bare", "-b", StoryTarget.MAIN,
          origin.toString());
      // Push options are advertised by the production host (JGit's ReceivePack) and default to OFF
      // for a local receive-pack; without this the fixture would refuse the exact argv that ships,
      // with "the receiving end does not support push options".
      run(origin.toFile(), "git", "config", "receive.advertisePushOptions", "true");
      run(origin.toFile(), "git", "config", "http.receivepack", "true");
      recordPushOptions(origin);

      work = Files.createTempDirectory("story-origin-");
      run(work.toFile(), "git", "init", "-b", StoryTarget.MAIN);
      run(work.toFile(), "git", "remote", "add", "origin", origin.toString());
      commit(work, "README.md", "# " + name + "\n", "initial commit");
      commit(work, "pom.xml", pom(name), "the reactor root");
      if (deployable) {
        commit(
            work,
            ".config/qits/deployments.yml",
            "applications:\n  - name: " + name + "\n",
            "declare that this repository deploys");
      }
      run(work.toFile(), "git", "push", "-q", "origin", StoryTarget.MAIN);
    } catch (Exception e) {
      throw new IllegalStateException("could not build the story repository " + name, e);
    } finally {
      deleteQuietly(work);
    }
  }

  /** Fork {@code branch} off {@code main} and put one commit on it that main does not have. */
  public static void branchWithWork(String projectId, String name, String branch, String file) {
    inAClone(
        projectId,
        name,
        work -> {
          run(work.toFile(), "git", "switch", "-q", "-c", branch);
          commit(work, file, "work on " + branch + "\n", "work on " + branch);
          run(work.toFile(), "git", "push", "-q", "origin", branch);
        });
  }

  /** Create a branch at main's tip and nothing more — a ref whose work main already carries. */
  public static void branchAtMain(String projectId, String name, String branch) {
    inAClone(
        projectId,
        name,
        work -> run(work.toFile(), "git", "push", "-q", "origin", StoryTarget.MAIN + ":" + branch));
  }

  /** The sha a ref points at, or null when the repository has no such ref. */
  public static String shaOf(String projectId, String name, String ref) {
    String output = gitRead(projectId, name, "git", "rev-parse", "--verify", "--quiet", ref);
    return output == null || output.isBlank() ? null : output.strip();
  }

  /** Every branch this repository holds, in ref order. */
  public static List<String> branches(String projectId, String name) {
    String output =
        gitRead(projectId, name, "git", "for-each-ref", "--format=%(refname:short)", "refs/heads");
    return output == null ? List.of() : output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
  }

  /** Every tag this repository holds. */
  public static List<String> tags(String projectId, String name) {
    String output = gitRead(projectId, name, "git", "tag", "--list");
    return output == null ? List.of() : output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
  }

  /** One file's content at a ref — how a story reads what the release actually stamped. */
  public static String fileAt(String projectId, String name, String ref, String file) {
    return gitRead(projectId, name, "git", "show", ref + ":" + file);
  }

  /** The subject line of a ref's tip commit. */
  public static String subjectAt(String projectId, String name, String ref) {
    String output = gitRead(projectId, name, "git", "log", "-1", "--format=%s", ref);
    return output == null ? null : output.strip();
  }

  /**
   * The push options the <b>last</b> recorded push moved one ref with, or null when none did; empty
   * when it was pushed with none. The only way to see a {@code --push-option} from outside the
   * pusher.
   *
   * <p><b>The last, not the first</b>, and that is not a detail: the fixtures themselves push — the
   * initial commit onto {@code main}, the deploy ref into place — so the first line for a ref is
   * always the fixture's own, carrying no options at all. What a story asks about is the push the
   * release just made.
   */
  public static List<String> pushOptionsFor(String projectId, String name, String ref) {
    Path log = origin(projectId, name).resolve("push-options.log");
    if (!Files.isRegularFile(log)) {
      return null;
    }
    List<String> last = null;
    try {
      for (String line : Files.readAllLines(log)) {
        String[] words = line.strip().split("\\s+");
        if (words.length > 0 && words[0].equals(ref)) {
          last = List.of(words).subList(1, words.length);
        }
      }
    } catch (IOException unreadable) {
      return null;
    }
    return last;
  }

  // --- what a story class calls -------------------------------------------------------------------

  /** Register the tap once per JVM. Called from every story class's {@code @BeforeAll}. */
  public static void install() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      harvested = 0;
      NetworkCapture.source(SOURCE_ID, StoryGitHost::edges);
      registered = true;
    }
  }

  /** The label an answered git request renders as — what an assertion has to spell. */
  public static String label(String method, String path, int status) {
    return Labels.scrub(method + " " + path + " -> " + status);
  }

  /** {@code GET /git/<project>/<repo>/info/refs -> 200} — the ref advertisement, either direction. */
  public static String advertisement(String projectId, String name) {
    return label("GET", GIT_SEGMENT + projectId + "/" + name + "/info/refs", 200);
  }

  /** {@code POST …/git-upload-pack -> 200} — this service reading the repository. */
  public static String read(String projectId, String name) {
    return label("POST", GIT_SEGMENT + projectId + "/" + name + "/git-upload-pack", 200);
  }

  /** {@code POST …/git-receive-pack -> 200} — this service moving a ref on the host. */
  public static String written(String projectId, String name) {
    return label("POST", GIT_SEGMENT + projectId + "/" + name + "/git-receive-pack", 200);
  }

  // --- the source ---------------------------------------------------------------------------------

  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      harvest();
      return List.copyOf(EDGES);
    }
  }

  private static void harvest() {
    List<String> lines = allLines();
    if (harvested > lines.size()) {
      // The file was truncated under us (a `clean` mid-run). Start over rather than mis-slice.
      harvested = 0;
      lines = allLines();
    }
    for (String line : lines.subList(harvested, lines.size())) {
      edge(line).ifPresent(EDGES::add);
    }
    harvested = lines.size();
  }

  private static Optional<NetworkEdge> edge(String line) {
    // "METHOD PATH STATUS" — three fields, no quoting, and a path carries no raw space.
    String[] fields = line.strip().split(" ");
    if (fields.length != 3 || !fields[1].startsWith(GIT_SEGMENT)) {
      return Optional.empty();
    }
    return Optional.of(
        NetworkEdge.http(
            StoryTarget.SERVICE,
            SERVICE_NAME,
            Labels.scrub(fields[0] + " " + fields[1] + " -> " + fields[2])));
  }

  /**
   * The recording's complete lines. A missing file is an empty recording rather than a failure, and
   * an <b>unterminated tail is dropped</b>: the server appends while this reads, and half a line
   * would shape half an edge. The next harvest sees it whole.
   */
  private static List<String> allLines() {
    if (!Files.isRegularFile(ACCESS_LOG)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(ACCESS_LOG, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    return List.of(text.substring(0, lastComplete).split("\n"));
  }

  private static synchronized void record(String method, String path, int status) {
    try {
      Files.createDirectories(ROOT);
      Files.writeString(
          ACCESS_LOG,
          method + " " + path + " " + status + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // A recording that cannot be written costs the diagram an arrow; it must not cost the launched
      // process its answer, which is what a release is actually waiting for.
    }
  }

  // --- git fixtures ---------------------------------------------------------------------------------

  private interface Work {
    void run(Path work) throws Exception;
  }

  private static void inAClone(String projectId, String name, Work work) {
    Path clone = null;
    try {
      Path origin = origin(projectId, name);
      clone = Files.createTempDirectory("story-clone-");
      run(clone.getParent().toFile(), "git", "clone", "-q", origin.toString(), clone.toString());
      work.run(clone);
    } catch (Exception e) {
      throw new IllegalStateException("could not shape the story repository " + name, e);
    } finally {
      deleteQuietly(clone);
    }
  }

  private static String gitRead(String projectId, String name, String... argv) {
    List<String> command = new ArrayList<>(List.of(argv[0], "--git-dir=" + origin(projectId, name)));
    command.addAll(List.of(argv).subList(1, argv.length));
    try {
      ProcessBuilder pb =
          new ProcessBuilder(command).redirectErrorStream(true).directory(ROOT.toFile());
      Process process = pb.start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return process.waitFor() == 0 ? output : null;
    } catch (Exception e) {
      throw new IllegalStateException("could not read " + name + ": " + e, e);
    }
  }

  private static void commit(Path work, String file, String content, String message)
      throws Exception {
    Path target = work.resolve(file);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content);
    run(work.toFile(), "git", "add", file);
    run(work.toFile(), "git", "commit", "-q", "-m", message);
  }

  /** A minimal but real maven reactor root, so the release flow's bump has somewhere to render. */
  private static String pom(String name) {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>eu.wohlben.qits</groupId>
            <artifactId>NAME</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <packaging>pom</packaging>
        </project>
        """
        .replace("NAME", name);
  }

  /**
   * A {@code pre-receive} that appends {@code <ref> <options…>} for every ref a push moves. Real
   * receive-pack, real hook, the same environment variables the git host's own hook reads.
   */
  private static void recordPushOptions(Path origin) throws Exception {
    Path log = origin.resolve("push-options.log");
    Path hook = origin.resolve("hooks").resolve("pre-receive");
    Files.createDirectories(hook.getParent());
    Files.writeString(
        hook,
        """
        #!/bin/sh
        opts=""
        i=0
        while [ "$i" -lt "${GIT_PUSH_OPTION_COUNT:-0}" ]; do
          eval "value=\\$GIT_PUSH_OPTION_$i"
          opts="$opts $value"
          i=$((i + 1))
        done
        while read -r old new ref; do
          echo "$ref$opts" >> LOG
        done
        exit 0
        """
            .replace("LOG", log.toString()));
    Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  private static void run(File cwd, String... argv) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd).redirectErrorStream(true);
    // Deterministic identity and no user/system config, so a developer's ~/.gitconfig cannot change
    // what these fixtures look like.
    pb.environment().put("GIT_AUTHOR_NAME", "qits-story");
    pb.environment().put("GIT_AUTHOR_EMAIL", "qits-story@local");
    pb.environment().put("GIT_COMMITTER_NAME", "qits-story");
    pb.environment().put("GIT_COMMITTER_EMAIL", "qits-story@local");
    pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    pb.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) {
      throw new IllegalStateException("git " + String.join(" ", argv) + " failed:\n" + output);
    }
  }

  private static void wipe() {
    deleteQuietly(ROOT);
  }

  private static void deleteQuietly(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException ignored) {
      // best effort — it is under target/
    }
  }
}
