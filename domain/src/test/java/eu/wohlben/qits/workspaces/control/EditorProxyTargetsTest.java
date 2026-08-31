package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The editor origin resolved to a workspace, out of this service's own state.
 *
 * <p>The claims that matter are the two the SSRF posture rests on and the one the cache rests on: a
 * label that names nothing resolves to nothing (and the caller 404s without connecting anywhere), a
 * repository that is not a wrapper is never the answer even when its name would fit, and the
 * workspace half is re-read rather than remembered — a main workspace can be discarded and made
 * again, and a stale row id would point the proxy at nothing.
 */
@QuarkusTest
public class EditorProxyTargetsTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject EditorProxyTargets targets;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /** A wrapper repository for {@code <slug>}, named the way qits-projects names one. */
  private String wrapperFor(String slug) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.registerWrapper(repoId, "master", EditorHost.wrapperRepositoryName(slug));
    return repoId;
  }

  @Test
  void anEditorOriginResolvesToTheWrappersMainWorkspace() throws Exception {
    String slug = "editorproj";
    String repoId = wrapperFor(slug);
    Workspace main = workspaceService.createMainWorkspace(repoId, "master");

    Optional<EditorProxyTargets.EditorTarget> target =
        targets.resolve("editor." + slug + ".dev.example.eu");

    assertTrue(target.isPresent());
    assertEquals(main.id, target.get().workspaceRowId());
    assertEquals(repoId, target.get().repositoryId());
    assertEquals(slug, target.get().projectLabel());
  }

  @Test
  void anUnknownLabelResolvesToNothing() throws Exception {
    // The whole of the 404: no project by that name, so no repository, so no row, so no container is
    // dialled. It is the same answer a malformed host gets, which is what makes the refusal
    // uninformative on purpose.
    wrapperFor("editorproj2");

    assertTrue(targets.resolve("editor.nosuchproject.dev.example.eu").isEmpty());
    assertTrue(targets.resolve("editor.nosuchproject").isEmpty(), "not an editor origin at all");
    assertTrue(targets.resolve(null).isEmpty());
  }

  @Test
  void aRepositoryThatIsNotAWrapperIsNeverTheAnswer() throws Exception {
    // The name alone must not do it. A project whose slug happens to derive the name of an ordinary
    // repository would otherwise hand that repository's main workspace out as an editor.
    String slug = "impostor";
    String repoId = TestOrigin.create(dataDir);
    repositories.registerAs(repoId, "master", "SERVICE");
    // the wrapper's name, on a repository that is not one
    repositories.registerWrapper("some-other-id", "master", "unrelated-unrelated");
    workspaceService.createMainWorkspace(repoId, "master");

    assertTrue(targets.resolve("editor." + slug + ".dev.example.eu").isEmpty());
  }

  @Test
  void theWorkspaceHalfIsReReadAndNotRemembered() throws Exception {
    // The registry half is an immutable fact (a project's slug cannot change, so neither can its
    // wrapper's name) and is cached; the row half moves, so it is not. Resolving before the main
    // workspace exists must not poison the answer for after it does.
    String slug = "laterproj";
    String repoId = wrapperFor(slug);
    String host = "editor." + slug + ".dev.example.eu";

    assertTrue(targets.resolve(host).isEmpty(), "no main workspace yet");

    Workspace main = workspaceService.createMainWorkspace(repoId, "master");
    assertEquals(main.id, targets.resolve(host).orElseThrow().workspaceRowId());
  }
}
