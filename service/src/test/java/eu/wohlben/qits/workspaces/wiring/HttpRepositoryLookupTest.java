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

  /** The bean wired to {@code baseUrl}, with a real generated client pointed at the same place. */
  private HttpRepositoryLookup lookupAgainst(String baseUrl) {
    HttpRepositoryLookup lookup = new HttpRepositoryLookup();
    lookup.baseUrl = Optional.ofNullable(baseUrl);
    if (baseUrl != null) {
      lookup.repositories =
          QuarkusRestClientBuilder.newBuilder()
              .baseUri(URI.create(baseUrl))
              .build(ProjectsRepositories.class);
    }
    return lookup;
  }

  @Test
  public void aKnownRepositoryYieldsItsIdNameProjectAndMainBranch() throws Exception {
    String base =
        serve(
            200,
            """
            {"repository":{"id":"repo-1","name":"qits-qits","backupUrl":"file:///origin","mainBranch":"main",\
            "archetype":"NONE","projectId":"p-1"}}""");

    Optional<RepositoryLookup.RepositoryView> found = lookupAgainst(base).find("repo-1");

    assertTrue(found.isPresent());
    assertEquals("repo-1", found.get().id());
    assertEquals("qits-qits", found.get().name(), "the daemon needs the addressable name");
    assertEquals("main", found.get().mainBranch());
    assertEquals("p-1", found.get().projectId(), "SCMRelease names the project");
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
