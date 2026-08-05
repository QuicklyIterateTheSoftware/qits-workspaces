package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The test-side {@link GitHostAddress}: the repository's own bare origin, addressed as a local path.
 *
 * <p>It wins over the shipped {@link ConfiguredGitHostAddress} with no change on your part, because
 * that one is {@code @DefaultBean} and yields to any other bean of the type — the same arrangement
 * {@code FakeRepositoryLookup} has with {@code HttpRepositoryLookup}.
 *
 * <p><b>What this keeps real, and what it does not.</b> The mirror is a real {@code git clone
 * --mirror} of a real bare and every write is a real {@code git push} back into it: a real ref
 * negotiation, a real fast-forward check, a real {@code --atomic} refusal. What it replaces is only
 * the <em>transport</em>. The protection hook is qits-artifacts' and is proven there and live;
 * nothing here can stand in for it.
 *
 * <p>A duplicate of the one in {@code service/src/test}, which is the convention here — the two
 * modules do not share a test classpath. This copy has no {@code beforeNextPush} hook: the staged
 * race belongs to the controller suite, which is where the flow that races is asserted.
 */
@ApplicationScoped
public class FakeGitHostAddress implements GitHostAddress {

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Override
  public String fetchUrl(String repoId) {
    return Path.of(dataDir, repoId, "origin").toAbsolutePath().toString();
  }

  @Override
  public String pushUrl(String repoId) {
    return fetchUrl(repoId);
  }
}
