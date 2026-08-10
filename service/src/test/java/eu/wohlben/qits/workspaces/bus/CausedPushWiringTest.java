package eu.wohlben.qits.workspaces.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.workspaces.control.GitMirrorRegistry;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.gitmirror.PushOutcome;
import eu.wohlben.qits.workspaces.gitmirror.RepoMirror;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The production wiring of the causation stamp, end to end through the beans that ship.
 *
 * <p>Everything either side of this is covered elsewhere and neither reaches the assembly:
 * {@code gitmirror}'s own {@code PushCausationHeaderTest} drives the header string with a supplier
 * it hands in by hand, and {@link EventstreamPushCausationTest} drives the port with no mirror in
 * sight. What is only true if {@code GitMirrorRegistry} really injected the port and really passed
 * it down is that a push made <b>inside a scope</b> — through the injected registry, against a real
 * bare, with the real argv — still lands.
 *
 * <p>That it lands is the whole assertion, and it is worth saying why rather than looking thin.
 * Every ref this service moves is moved by a push, so an argv this wiring built wrongly would be
 * every release, integrate, branch create and cleanup failing at once — and the failure would be a
 * git usage error, which no amount of unit-testing the two halves separately can rule out. The
 * header's <em>arrival</em> cannot be asserted here at all: these fixtures are local bares, and
 * {@code http.extraHeader} is inert over a file transport. qits-githost owns that claim.
 */
@QuarkusTest
public class CausedPushWiringTest {

  @Inject GitMirrorRegistry mirrors;

  @ConfigProperty(name = "qits.test.origins-dir")
  String originsDir;

  @Test
  public void aPushMadeUnderACauseStillLandsThroughTheInjectedRegistry() throws Exception {
    String repoId = TestOrigin.create(originsDir);
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    AtomicReference<PushOutcome> outcome = new AtomicReference<>();

    CausationScope.with(
        UUID.randomUUID(), () -> outcome.set(mirror.createBranch("caused-branch", "master")));

    assertTrue(outcome.get().accepted(), outcome.get().output());
    assertEquals(
        mirror.remoteBranchSha("master").orElseThrow(),
        mirror.remoteBranchSha("caused-branch").orElseThrow(),
        "the branch really arrived at the host, at the tip it was created from");
  }
}
