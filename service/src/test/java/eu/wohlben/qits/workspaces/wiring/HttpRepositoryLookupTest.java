package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpRepositoryLookup} against a real HTTP server rather than a mocked client, because what
 * is worth pinning here is the wire behaviour: the path it calls, and which failures are an answer
 * versus an error.
 *
 * <p>Plain JUnit, no {@code @QuarkusTest} — the class is constructed directly so the config field
 * can be set per case. The server is {@code com.sun.net.httpserver}, already in the JDK, so this
 * adds no dependency and nothing here needs a container.
 */
public class HttpRepositoryLookupTest {

  private HttpServer server;
  private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  /** Starts a server answering every request with the given status and body. */
  private String serve(int status, String body) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requestedPaths.add(exchange.getRequestURI().getPath());
          byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private HttpRepositoryLookup lookupAgainst(String baseUrl) {
    HttpRepositoryLookup lookup = new HttpRepositoryLookup();
    lookup.baseUrl = Optional.ofNullable(baseUrl);
    lookup.openClient();
    return lookup;
  }

  @Test
  public void aKnownRepositoryYieldsItsIdAndMainBranch() throws Exception {
    String base =
        serve(
            200,
            """
            {"repository":{"id":"repo-1","url":"file:///origin","mainBranch":"main",\
            "archetype":"NONE","projectId":"p-1"}}""");

    Optional<RepositoryLookup.RepositoryView> found = lookupAgainst(base).find("repo-1");

    assertTrue(found.isPresent());
    assertEquals("repo-1", found.get().id());
    assertEquals("main", found.get().mainBranch());
  }

  /**
   * The path is qits-projects' own gateway segment, spelled here as a cross-repo contract. If that
   * service moves its segment this test is the thing that notices.
   */
  @Test
  public void theRequestGoesToTheProjectsSegment() throws Exception {
    String base = serve(200, "{\"repository\":{\"id\":\"repo-1\",\"mainBranch\":\"main\"}}");

    lookupAgainst(base).find("repo-1");

    assertEquals(List.of("/projects/api/repositories/repo-1"), requestedPaths);
  }

  /** A trailing slash on the configured base must not become a double slash in the path. */
  @Test
  public void aTrailingSlashOnTheBaseUrlIsTolerated() throws Exception {
    String base = serve(200, "{\"repository\":{\"id\":\"repo-1\",\"mainBranch\":\"main\"}}");

    lookupAgainst(base + "/").find("repo-1");

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
    assertTrue(failure.getMessage().contains("500"));
  }

  @Test
  public void anUnreadableAnswerThrowsRatherThanLookingLikeAnEmptyRegistry() throws Exception {
    String base = serve(200, "not json at all");

    assertThrows(IllegalStateException.class, () -> lookupAgainst(base).find("repo-1"));
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
