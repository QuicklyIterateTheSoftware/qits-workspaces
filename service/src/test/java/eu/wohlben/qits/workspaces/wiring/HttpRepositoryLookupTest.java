package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpRepositoryLookup} against a real HTTP server and a real REST client, because what is
 * worth pinning here is not the happy path — it is <b>which failures are an answer and which are an
 * error</b>, and that mapping depends entirely on the exception types the generated client actually
 * throws. A test with a mocked client would assert my assumptions about those types rather than the
 * types themselves, and would keep passing if the extension changed them.
 *
 * <p>So: a {@code com.sun.net.httpserver} stub (already in the JDK, no dependency, no container) on
 * an ephemeral port, and a client built by {@link QuarkusRestClientBuilder} against it. The bean is
 * constructed directly rather than injected, so each case can set the address it is testing —
 * including the unwired one, which no injected configuration could express.
 */
@QuarkusTest
public class HttpRepositoryLookupTest {

  private HttpServer server;
  private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  /** Starts a stub answering every request with the given status and body. */
  private String serve(int status, String body) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requestedPaths.add(exchange.getRequestURI().getPath());
          byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * Starts a stub whose answer depends on the path, because {@code findByName} is <b>two</b> reads
   * in one call — the alias table, then the ordinary by-id view — and a single-answer stub would
   * feed the second read the first one's body. A path the function has no body for is a 404.
   */
  private String serveRouted(java.util.function.Function<String, String> bodyForPath)
      throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          requestedPaths.add(path);
          String body = bodyForPath.apply(path);
          byte[] bytes =
              body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length == 0 ? -1 : bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** The bean wired to {@code baseUrl}, with a real generated client pointed at the same place. */
  private HttpRepositoryLookup lookupAgainst(String baseUrl) {
    HttpRepositoryLookup lookup = new HttpRepositoryLookup();
    lookup.baseUrl = Optional.ofNullable(baseUrl);
    lookup.projectsBearer =
        new IdpProjectsBearer() {
          @Override
          public Optional<String> authorization() {
            return Optional.empty();
          }
        };
    if (baseUrl != null) {
      lookup.repositories =
          QuarkusRestClientBuilder.newBuilder()
              .baseUri(URI.create(baseUrl))
              .build(ProjectsRepositories.class);
      lookup.projectRepositories =
          QuarkusRestClientBuilder.newBuilder()
              .baseUri(URI.create(baseUrl))
              .build(ProjectsProjectRepositories.class);
    }
    return lookup;
  }

  /**
   * The row id and the name are read as two separate answers, and the fixture makes them differ on
   * purpose: a repository the projects self-seed registered carries a UUID id, so a view that
   * quietly reported the id as the name would publish an SCMRelease no committed CI selection can
   * address — which is the defect {@code repositoryName} closed.
   */
  @Test
  public void aKnownRepositoryYieldsItsIdNameProjectAndMainBranch() throws Exception {
    String base =
        serve(
            200,
            """
            {"repository":{"id":"7d45ae57-8cab-49dd-afbd-ac82c720ec6e",\
            "name":"qits-projects-daemon","url":"file:///origin","mainBranch":"main",\
            "archetype":"NONE","projectId":"p-1"}}""");

    Optional<RepositoryLookup.RepositoryView> found =
        lookupAgainst(base).find("7d45ae57-8cab-49dd-afbd-ac82c720ec6e");

    assertTrue(found.isPresent());
    assertEquals("7d45ae57-8cab-49dd-afbd-ac82c720ec6e", found.get().id());
    assertEquals("qits-projects-daemon", found.get().name(), "SCMRelease names the repository");
    assertEquals("main", found.get().mainBranch());
    assertEquals("p-1", found.get().projectId(), "SCMRelease names the project");
  }

  /** A registry answering with no name resolves anyway: the release must not depend on the field. */
  @Test
  public void aRepositoryWithNoNameStillResolves() throws Exception {
    String base = serve(200, "{\"repository\":{\"id\":\"repo-1\",\"mainBranch\":\"main\"}}");

    Optional<RepositoryLookup.RepositoryView> found = lookupAgainst(base).find("repo-1");

    assertTrue(found.isPresent());
    assertEquals(null, found.get().name());
  }

  /**
   * Fields qits-projects sends that this context does not bind are ignored rather than fatal. That
   * is what lets that service evolve its RepositoryDto without breaking this one, so it is asserted
   * rather than assumed — the case above already carries url and archetype; this one adds a field
   * that does not exist yet.
   */
  @Test
  public void anUnknownFieldFromProjectsIsNotFatal() throws Exception {
    String base =
        serve(200, "{\"repository\":{\"id\":\"repo-1\",\"mainBranch\":\"main\",\"futureField\":7}}");

    assertTrue(lookupAgainst(base).find("repo-1").isPresent());
  }

  /**
   * The path is qits-projects' own gateway segment, spelled in {@link ProjectsRepositories} as a
   * cross-repo contract. If that service moves its segment, this is what notices.
   */
  @Test
  public void theRequestGoesToTheProjectsSegment() throws Exception {
    String base = serve(200, "{\"repository\":{\"id\":\"repo-1\",\"mainBranch\":\"main\"}}");

    lookupAgainst(base).find("repo-1");

    assertEquals(List.of("/projects/api/repositories/repo-1"), requestedPaths);
  }

  /**
   * The second cross-repo path, read by aggregate workspace creation: the project's repository
   * listing. Its wrapper field is deliberately not bound, so the fixture carries one.
   */
  @Test
  public void theProjectRepositoryListingGoesToTheProjectsSegment() throws Exception {
    String base =
        serve(
            200,
            """
            {"entries":[{"repository":{"id":"repo-1","name":"qits-qits","mainBranch":"main",\
            "projectId":"p-1"}},{"repository":{"id":"repo-2","name":"qits-workspaces",\
            "mainBranch":"main","projectId":"p-1"}}],"wrapper":{"entries":[]}}""");

    List<RepositoryLookup.RepositoryView> found = lookupAgainst(base).listByProject("p-1");

    assertEquals(List.of("/projects/api/projects/p-1/repositories"), requestedPaths);
    assertEquals(2, found.size());
    assertEquals("qits-qits", found.get(0).name());
    assertEquals("repo-2", found.get(1).id());
  }

  /**
   * An empty list means "this project has no repositories", which for a branch tree means "branch
   * the wrapper alone". An outage must not be able to say that.
   */
  @Test
  public void anUnreachableRegistryThrowsRatherThanReadingAsAnEmptyProject() {
    HttpRepositoryLookup lookup = lookupAgainst("http://127.0.0.1:1");

    assertThrows(IllegalStateException.class, () -> lookup.listByProject("p-1"));
  }

  // --- the public identity: (projectId, repoName) --------------------------------------------

  /**
   * The name form, end to end: the alias table answers an id and the ordinary by-id read answers
   * the view. Both paths are asserted <b>in order</b>, because the two-call shape is the decision —
   * a resolver that built a view out of the caller's own two strings would report a main branch
   * nobody looked up.
   */
  @Test
  public void aProjectScopedNameResolvesThroughTheAliasTableAndThenTheByIdRead() throws Exception {
    String id = "7d45ae57-8cab-49dd-afbd-ac82c720ec6e";
    String base =
        serveRouted(
            path ->
                path.endsWith("/by-name/qits-workspaces")
                    ? "{\"repositoryId\":\"" + id + "\"}"
                    : ("""
                       {"repository":{"id":"%s","name":"qits-workspaces","mainBranch":"main",\
                       "projectId":"qits"}}""")
                        .formatted(id));

    Optional<RepositoryLookup.RepositoryView> found =
        lookupAgainst(base).findByName("qits", "qits-workspaces");

    assertEquals(
        List.of(
            "/projects/api/projects/qits/repositories/by-name/qits-workspaces",
            "/projects/api/repositories/" + id),
        requestedPaths);
    assertTrue(found.isPresent());
    assertEquals(id, found.get().id());
    assertEquals("qits-workspaces", found.get().name());
    assertEquals("qits", found.get().projectId());
    assertEquals("main", found.get().mainBranch());
  }

  /**
   * qits-projects answers 404 for an unknown project and an unknown name alike, and both are the
   * same answer here: this project holds no repository by that name. The release door turns it into
   * a 404 of its own naming the pair.
   */
  @Test
  public void anUnknownNameIsEmptyRatherThanAnError() throws Exception {
    String base = serve(404, "{\"message\":\"No repository named 'nope' in project qits\"}");

    assertTrue(lookupAgainst(base).findByName("qits", "nope").isEmpty());
  }

  /**
   * The distinction again, on the name path — and it matters more here than anywhere: the caller is
   * a pipeline step, and an outage reported as "no such repository" would tell it its repository
   * had been deleted.
   */
  @Test
  public void anUnreachableRegistryThrowsWhileResolvingAName() {
    HttpRepositoryLookup lookup = lookupAgainst("http://127.0.0.1:1");

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> lookup.findByName("qits", "qits-workspaces"));
    assertTrue(
        failure.getMessage().contains("unreachable"),
        "expected the message to name the outage, got: " + failure.getMessage());
  }

  @Test
  public void aServerErrorWhileResolvingANameThrowsInsteadOfReadingAsNotFound() throws Exception {
    String base = serve(500, "boom");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> lookupAgainst(base).findByName("qits", "qits-qits"));
    assertTrue(
        failure.getMessage().contains("500"),
        "expected the message to carry the status, got: " + failure.getMessage());
  }

  @Test
  public void halfAnAddressIsNotWorthACall() throws Exception {
    String base = serve(200, "{\"repositoryId\":\"repo-1\"}");
    HttpRepositoryLookup lookup = lookupAgainst(base);

    assertTrue(lookup.findByName("  ", "qits-qits").isEmpty());
    assertTrue(lookup.findByName("qits", " ").isEmpty());
    assertTrue(lookup.findByName(null, null).isEmpty());
    assertTrue(requestedPaths.isEmpty(), "half an address should not reach the network");
  }

  @Test
  public void anUnknownRepositoryIsEmptyRatherThanAnError() throws Exception {
    String base = serve(404, "{\"message\":\"Repository not found\"}");

    assertTrue(lookupAgainst(base).find("no-such-repo").isEmpty());
  }

  /**
   * The distinction this class exists to get right. {@code require()} turns empty into a 404, so
   * reporting an unreachable registry as empty would make a whole-service outage indistinguishable
   * from a user typing a bad id.
   */
  @Test
  public void anUnreachableRegistryThrowsInsteadOfReadingAsNotFound() {
    // Port 1 on loopback: nothing listens, and connecting fails fast.
    HttpRepositoryLookup lookup = lookupAgainst("http://127.0.0.1:1");

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> lookup.find("repo-1"));
    assertTrue(
        failure.getMessage().contains("unreachable"),
        "expected the message to name the outage, got: " + failure.getMessage());
  }

  @Test
  public void aServerErrorThrowsInsteadOfReadingAsNotFound() throws Exception {
    String base = serve(500, "boom");

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> lookupAgainst(base).find("repo-1"));
    assertTrue(
        failure.getMessage().contains("500"),
        "expected the message to carry the status, got: " + failure.getMessage());
  }

  @Test
  public void anUnreadableAnswerThrowsRatherThanLookingLikeAnEmptyRegistry() throws Exception {
    String base = serve(200, "not json at all");

    assertThrows(RuntimeException.class, () -> lookupAgainst(base).find("repo-1"));
  }

  /** Dev and test tolerate no address; a production build never reaches this (see the observer). */
  @Test
  public void withNoAddressConfiguredEveryLookupIsEmpty() {
    assertTrue(lookupAgainst(null).find("repo-1").isEmpty());
  }

  @Test
  public void aBlankRepositoryIdIsNotWorthACall() throws Exception {
    String base = serve(200, "{\"repository\":{\"id\":\"repo-1\",\"mainBranch\":\"main\"}}");

    assertTrue(lookupAgainst(base).find("  ").isEmpty());
    assertTrue(requestedPaths.isEmpty(), "a blank id should not reach the network");
  }
}
