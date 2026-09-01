package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.wiring.ProjectsReleaseRequests;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * The suite's qits-projects release-request door: records what the flipped public doors asked and
 * answers a canned PENDING request, so every door test runs without a qits-projects. State through
 * methods, the package convention.
 */
@Mock
@ApplicationScoped
@RestClient
public class FakeProjectsReleaseRequests implements ProjectsReleaseRequests {

  public record Asked(
      String repoId, String authorization, String user, String roles, CreateBody body) {}

  private final List<Asked> asked = Collections.synchronizedList(new ArrayList<>());

  public List<Asked> asked() {
    return List.copyOf(asked);
  }

  public void reset() {
    asked.clear();
  }

  @Override
  public CreateResponse create(
      String repoId, String authorization, String user, String roles, CreateBody body) {
    asked.add(new Asked(repoId, authorization, user, roles, body));
    return new CreateResponse(
        new RequestView(
            "request-" + asked.size(), "PENDING", body.branch(), body.commitSha(), null));
  }
}
