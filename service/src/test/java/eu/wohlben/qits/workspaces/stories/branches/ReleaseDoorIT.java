package eu.wohlben.qits.workspaces.stories.branches;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import eu.wohlben.qits.workspaces.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.workspaces.stories.support.StoryGitHost;
import eu.wohlben.qits.workspaces.stories.support.StoryIdentities;
import eu.wohlben.qits.workspaces.stories.support.StoryNetwork;
import eu.wohlben.qits.workspaces.stories.support.StoryPeers;
import eu.wohlben.qits.workspaces.stories.support.StoryProfile;
import eu.wohlben.qits.workspaces.stories.support.StoryTarget;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The release execution arm</b> — {@code POST /workspaces/api/branches/execute-release}, the one
 * door into a repository's default branch, driven end to end against a git host that answers over
 * HTTP. It is what the public {@code /branches/release} door's release requests execute through
 * once their quality gates pass (and the operator's direct lever); the landing it performs is
 * byte-for-byte the release the public door performed before the door split.
 *
 * <p>This is the catalogue's centre, because it is the only place the whole design is visible at
 * once. Every ref this service moves is moved by a <b>push</b>, over the ordinary git host, so
 * receive-pack is the sole writer and the ordinary post-receive → qits-ci → build chain happens for
 * the ordinary reason. Nothing downstream learns a new trick. What a diagram of a release shows is
 * therefore the complete account of it: who asked, which registry said what the repository is
 * called, which refs moved on the host, and what the platform was told afterwards.
 *
 * <p><b>Three stories, three repositories, and each one is a different sentence about the same
 * flow:</b>
 *
 * <ol>
 *   <li>a deployable repository — the trunk moves, the tag lands, the same commit is pushed again
 *       onto the ref the environment listens to, and the trunk's build is suppressed because the
 *       promotion's is the release's signal;
 *   <li>a library — the same release, promoting nowhere, with its trunk push CI-<b>hot</b>, because
 *       there that build is the only proof the release is sound;
 *   <li>a branch whose work the trunk already carries — a 409 that pushes nothing, read from the
 *       git host's own recording rather than from the status code.
 * </ol>
 *
 * <p><b>This class owns the outbound credential arrow.</b> quarkus-oidc-client caches its mint and
 * this service has three named clients ({@code default} for qits-containers, {@code githost},
 * {@code projects}); all three mint on the first release of the catalogue and never again — see
 * {@link StoryPeers}. They land in story 1 and nowhere else, which is why stories 2 and 3 have one
 * fewer edge for a reason that has nothing to do with what they do.
 *
 * <p><b>Each story builds its own repository</b>, origin and registry row together, and never reuses
 * another's. A release deletes the branch it landed and moves the trunk, so a shared fixture would
 * make story 3 depend on whether story 1 ran — which is the property {@code @Order} exists to avoid
 * needing.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ReleaseDoorIT {

  static final String CATEGORY = "release";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String RELEASED = "A pipeline releases a branch and the platform deploys it";

  static final String LIBRARY = "A library release lands on the trunk and promotes nowhere";

  static final String SETTLED = "A branch the trunk already carries releases nothing";

  static final String RELEASED_SLUG = Slugs.slug(RELEASED);

  static final String LIBRARY_SLUG = Slugs.slug(LIBRARY);

  static final String SETTLED_SLUG = Slugs.slug(SETTLED);

  /** {@code YYYY.MMDD.HHMMSS}, and no identifier may carry a leading zero. */
  private static final Pattern VERSION =
      Pattern.compile("(?:[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)");

  /** The push option the git host's protection hook accepts a fast-forward release under. */
  private static final String RELEASE_OPTION = "qits.release";

  /** …and the one that says "move this ref without firing a build". */
  private static final String NO_CI_OPTION = "qits.no-ci";

  /** Every credential a story here minted, so the reports can be searched for all of them. */
  private static final List<String> MINTED = new ArrayList<>();

  @BeforeAll
  static void tapEveryEndOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = RELEASED, category = CATEGORY)
  @UserStoryDescription(
      """
      A branch is green and somebody wants it in production. The caller is usually not a person: a
      pipeline step holds a commissioned credential of its own and drives this door with the
      repository's PUBLIC identity — the (project, name) pair a clone url and a committed pipeline
      both spell — because the internal row id is minted per platform instance and is addressable
      only through the registry that minted it. The door resolves the pair through qits-projects and
      then works entirely in terms of the row id, which is why the diagram carries both reads.

      What the release does is one merge and two pushes. The branch is merged into the repository's
      default branch in a DETACHED worktree on a private mirror, a fresh YYYY.MMDD.HHMMSS version is
      stamped into every manifest in the same index, and the two are committed as ONE commit —
      `release(<version>): <summary>`. That commit and an annotated tag named the version are pushed
      together, atomically: one receive-pack, one pre-receive, one post-receive, and either both land
      or neither does — which is what turns the tag into the version-uniqueness guarantee this
      platform never had.

      Then the same commit is pushed AGAIN, onto the branch the environment listens to. That second
      push is what deploys: the deployer registers and deploys an application from a green build on
      that ref, so the trunk is the integration branch and the entry branch is what ships. And
      because one sha reaching two refs would be two builds of which only one signals anything, the
      trunk push goes quiet — `-o qits.no-ci` beside `-o qits.release` — and the promotion's build is
      the release's signal.

      Finally the platform is told. `SCMRelease` says SOURCE CONTROL has this release and nothing
      more; the event that says an artifact exists is qits-ci's own, a whole build later.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void aPipelineReleasesABranchAndTheEntryBranchMoves(Interactions story) {
    repository(StoryTarget.SERVICE_REPO, true);
    StoryGitHost.branchWithWork(
        StoryTarget.PROJECT, StoryTarget.SERVICE_REPO, StoryTarget.WORK_BRANCH, "feature.txt");
    StoryGitHost.branchAtMain(
        StoryTarget.PROJECT, StoryTarget.SERVICE_REPO, StoryTarget.ENTRY_BRANCH);
    String trunkBefore = StoryGitHost.shaOf(StoryTarget.PROJECT, StoryTarget.SERVICE_REPO, "main");

    NetworkCapture.actor(StoryIdentities.PIPELINE);
    String bearer = pipelineBearer();
    JsonPath released =
        StoryIdentities.bearer(given(), bearer)
            .contentType(ContentType.JSON)
            .body(StoryTarget.releaseBody(StoryTarget.WORK_BRANCH, "the story branch"))
            .when()
            .post(
                StoryTarget.BRANCH_EXECUTE_RELEASE_PATH
                    + StoryTarget.releaseQuery(StoryTarget.PROJECT, StoryTarget.SERVICE_REPO))
            .then()
            .statusCode(200)
            // `branch` is the branch that WAS RELEASED, not the one it landed on. There is no
            // targetBranch field here and that is deliberate: a release's target is always the
            // repository's default branch, so a field for it would be a constant. Its sibling
            // /integrate does carry one, because an integrate's target is whichever parent the
            // branch forked from.
            .body("branch", equalTo(StoryTarget.WORK_BRANCH))
            .body("promotions", hasSize(1))
            .body("promotions[0].branch", equalTo(StoryTarget.ENTRY_BRANCH))
            .body("promotions[0].error", nullValue())
            .extract()
            .jsonPath();
    String version = released.getString("version");
    String commitSha = released.getString("commitSha");
    assertTrue(
        VERSION.matcher(version).matches(), "the release did not stamp a calver: " + version);
    story
        .note(
            "the pipeline step names the repository the public way — projectId + repositoryName —"
                + " and the door answers with the version it stamped, the merge commit, the branch"
                + " it released, and one promotion entry per ref this release was pushed to again")
        .as("release-accepted");

    // What the git host holds now. This is the assertion that matters: the response is this
    // service's account of what it did, and the origin is the account of what happened.
    assertEquals(
        commitSha,
        StoryGitHost.shaOf(StoryTarget.PROJECT, StoryTarget.SERVICE_REPO, StoryTarget.MAIN),
        "the default branch is not at the released commit");
    assertFalse(
        commitSha.equals(trunkBefore), "the default branch did not move");
    assertTrue(
        StoryGitHost.tags(StoryTarget.PROJECT, StoryTarget.SERVICE_REPO).contains(version),
        "the release tag is not on the git host");
    assertEquals(
        "release(" + version + "): the story branch",
        StoryGitHost.subjectAt(StoryTarget.PROJECT, StoryTarget.SERVICE_REPO, StoryTarget.MAIN),
        "the merge and the bump are not one commit with the release subject");
    assertTrue(
        StoryGitHost.fileAt(StoryTarget.PROJECT, StoryTarget.SERVICE_REPO, StoryTarget.MAIN,
                "pom.xml")
            .contains("<version>" + version + "</version>"),
        "the version was not stamped into the reactor root");
    story
        .note(
            "the trunk is at the released commit, the tag named for the version is on the host, and"
                + " the merge and the manifest bump are ONE commit — a version that exists in the"
                + " manifests but not in a tag, or the other way round, is a release nobody can"
                + " address")
        .as("trunk-and-tag-landed");

    assertEquals(
        commitSha,
        StoryGitHost.shaOf(
            StoryTarget.PROJECT, StoryTarget.SERVICE_REPO, StoryTarget.ENTRY_BRANCH),
        "the entry branch was not promoted to the released commit");
    assertFalse(
        StoryGitHost.branches(StoryTarget.PROJECT, StoryTarget.SERVICE_REPO)
            .contains(StoryTarget.WORK_BRANCH),
        "the released branch survived; the next force-push of it would not be a create");
    story
        .note(
            "the same commit is on environment/dev, and the source branch is gone — the work is in"
                + " the trunk, so a ref still claiming it is pending would be a lie")
        .as("promoted-and-swept");

    // The push options, read off a real pre-receive hook. This is the only way to see a
    // --push-option from outside the pushing process, and it is what makes "the trunk goes quiet
    // exactly when there is somewhere to promote to" an assertion about the argv that ships.
    List<String> trunkOptions =
        StoryGitHost.pushOptionsFor(
            StoryTarget.PROJECT, StoryTarget.SERVICE_REPO, "refs/heads/" + StoryTarget.MAIN);
    List<String> promotionOptions =
        StoryGitHost.pushOptionsFor(
            StoryTarget.PROJECT, StoryTarget.SERVICE_REPO, "refs/heads/" + StoryTarget.ENTRY_BRANCH);
    assertNotNull(trunkOptions, "no push moved the default branch");
    assertTrue(trunkOptions.contains(RELEASE_OPTION), "the trunk push was not a sanctioned release");
    assertTrue(trunkOptions.contains(NO_CI_OPTION), "the trunk push was not quiet");
    assertNotNull(promotionOptions, "no push moved the entry branch");
    assertTrue(promotionOptions.contains(RELEASE_OPTION), "the promotion was not a release push");
    assertFalse(promotionOptions.contains(NO_CI_OPTION), "the promotion was quiet; nothing deploys");
    story
        .note(
            "the trunk push carries qits.release AND qits.no-ci; the promotion carries qits.release"
                + " alone — one sha on two refs is two builds, and only the deploy branch's means"
                + " anything")
        .as("one-sha-one-build");

    List<String> events = StoryPeers.publishedEvents();
    assertTrue(
        events.stream()
            .anyMatch(
                event ->
                    event.contains("SCMRelease")
                        && event.contains(version)
                        && event.contains(StoryTarget.SERVICE_REPO)),
        "no SCMRelease naming this release reached qits-events: " + events);
    story
        .note(
            "qits-events is told SCMRelease — source control has this release, carrying the"
                + " repository's NAME as well as its row id, because a committed CI selection can"
                + " address a name and cannot address an id a platform instance minted")
        .as("scm-release-published");
  }

  @UserStory(value = LIBRARY, category = CATEGORY)
  @UserStoryDescription(
      """
      A shared library, an SPA published to the npm registry, a documentation repo — none of them is
      deployed by qits-platform-deployments, and none of them carries `.config/qits/deployments.yml`.
      That file's PRESENCE is the whole of a repository's answer to "do you deploy at all", and this
      service opens none of it: everything inside belongs to the deployer.

      So the release is the same release — merge, stamp, one commit, one atomic push of the commit
      and its tag — and it stops there. `promotions` comes back empty, which is what a caller sees,
      and the trunk push stays CI-HOT: with no promotion to carry the signal, that build is the only
      proof the release is sound.

      The caller here is a person rather than a pipeline. Every door in this API is
      `@RolesAllowed("qits:admin")` and the two identity tracks open it alike — a bearer from the
      idp, or the X-Qits-User / X-Qits-Roles pair the platform edge asserts for a logged-in session
      — because an operator pressing Release in the branch list is exactly as legitimate a caller as
      the build container.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(2)
  void aLibraryReleaseLandsOnTheTrunkAndStopsThere(Interactions story) {
    repository(StoryTarget.LIBRARY_REPO, false);
    StoryGitHost.branchWithWork(
        StoryTarget.PROJECT, StoryTarget.LIBRARY_REPO, StoryTarget.WORK_BRANCH, "library.txt");

    NetworkCapture.actor(StoryIdentities.OPERATOR);
    JsonPath released =
        StoryIdentities.person(given())
            .contentType(ContentType.JSON)
            .body(StoryTarget.releaseBody(StoryTarget.WORK_BRANCH, "the library branch"))
            .when()
            .post(
                StoryTarget.BRANCH_EXECUTE_RELEASE_PATH
                    + StoryTarget.releaseQuery(StoryTarget.PROJECT, StoryTarget.LIBRARY_REPO))
            .then()
            .statusCode(200)
            .body("branch", equalTo(StoryTarget.WORK_BRANCH))
            .body("promotions", hasSize(0))
            .extract()
            .jsonPath();
    String version = released.getString("version");
    story
        .note(
            "an operator releases from the branch list, with the session pair the edge asserts"
                + " rather than a bearer, and the answer carries no promotions at all")
        .as("library-released");

    assertEquals(
        released.getString("commitSha"),
        StoryGitHost.shaOf(StoryTarget.PROJECT, StoryTarget.LIBRARY_REPO, StoryTarget.MAIN),
        "the trunk is not at the released commit");
    assertTrue(
        StoryGitHost.tags(StoryTarget.PROJECT, StoryTarget.LIBRARY_REPO).contains(version),
        "the release tag is not on the git host");
    assertFalse(
        StoryGitHost.branches(StoryTarget.PROJECT, StoryTarget.LIBRARY_REPO)
            .contains(StoryTarget.ENTRY_BRANCH),
        "a deploy branch was created for a repository that declares no deployment");
    story
        .note(
            "the trunk moved and the tag landed, and environment/dev does not exist in this"
                + " repository — pushing one would buy a CI build and a branch nobody reads")
        .as("nothing-was-promoted");

    List<String> trunkOptions =
        StoryGitHost.pushOptionsFor(
            StoryTarget.PROJECT, StoryTarget.LIBRARY_REPO, "refs/heads/" + StoryTarget.MAIN);
    assertNotNull(trunkOptions, "no push moved the default branch");
    assertTrue(trunkOptions.contains(RELEASE_OPTION), "the trunk push was not a sanctioned release");
    assertFalse(
        trunkOptions.contains(NO_CI_OPTION),
        "the trunk push went quiet with nothing to carry the signal instead");
    story
        .note(
            "and the trunk push is CI-hot: qits.release without qits.no-ci, because here that build"
                + " is the only proof the release is sound")
        .as("trunk-build-is-the-signal");
  }

  @UserStory(value = SETTLED, category = CATEGORY)
  @UserStoryDescription(
      """
      Releasing twice is the ordinary shape of a retry: a caller that lost its 200 to a dropped
      connection asks again, and a person who is not sure asks again too. The second attempt must
      not push anything, and it must say why in a way a client can branch on.

      It is refused 409 with `reason: ALREADY_INTEGRATED` — the additive field on the ordinary
      `{message}` envelope — and that value means the work is IN rather than that something went
      wrong. The story's subject is what did NOT happen: the git host was read, on a mirror
      refreshed for exactly this preflight, and never written. No receive-pack, no tag, no
      SCMRelease, and the trunk is byte-identical afterwards.

      That is a property of where the merge happens rather than of an early return. The worktree is
      DETACHED, on a mirror nobody serves, so the merge, the bump, the commit and the tag all happen
      against a HEAD that is not a branch — a conflict, a bump failure or a crash leaves the default
      branch untouched, and the only cleanup a failure needs is removing a directory.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(3)
  void aBranchAlreadyInTheTrunkPushesNothing(Interactions story) {
    repository(StoryTarget.SETTLED_REPO, true);
    StoryGitHost.branchAtMain(
        StoryTarget.PROJECT, StoryTarget.SETTLED_REPO, StoryTarget.LANDED_BRANCH);
    String trunkBefore =
        StoryGitHost.shaOf(StoryTarget.PROJECT, StoryTarget.SETTLED_REPO, StoryTarget.MAIN);

    NetworkCapture.actor(StoryIdentities.PIPELINE);
    String bearer = pipelineBearer();
    StoryIdentities.bearer(given(), bearer)
        .contentType(ContentType.JSON)
        .body(StoryTarget.releaseBody(StoryTarget.LANDED_BRANCH, "already in"))
        .when()
        .post(
            StoryTarget.BRANCH_EXECUTE_RELEASE_PATH
                + StoryTarget.releaseQuery(StoryTarget.PROJECT, StoryTarget.SETTLED_REPO))
        .then()
        .statusCode(409)
        .body("reason", equalTo("ALREADY_INTEGRATED"));
    story
        .note(
            "the door answers 409 with reason ALREADY_INTEGRATED — a structural value, beside the"
                + " ordinary message, so a client can tell `the work is in` from `something is"
                + " broken` without reading English")
        .as("already-integrated");

    assertEquals(
        trunkBefore,
        StoryGitHost.shaOf(StoryTarget.PROJECT, StoryTarget.SETTLED_REPO, StoryTarget.MAIN),
        "the default branch moved on a refused release");
    assertTrue(
        StoryGitHost.tags(StoryTarget.PROJECT, StoryTarget.SETTLED_REPO).isEmpty(),
        "a refused release left a tag behind");
    assertTrue(
        StoryGitHost.branches(StoryTarget.PROJECT, StoryTarget.SETTLED_REPO)
            .contains(StoryTarget.LANDED_BRANCH),
        "a refused release deleted the branch it did not land");
    story
        .note(
            "nothing moved: the trunk is byte-identical, no tag exists and the source branch is"
                + " still there — the merge happened in a detached worktree on a mirror nobody"
                + " serves, so a refusal has nothing to unwind")
        .as("nothing-moved");
  }

  @AfterAll
  static void everyReleaseStoryIsComplete() {
    // --- the deployable release ------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, RELEASED_SLUG, UserflowReport.PASSED);
    for (String step :
        List.of(
            "release-accepted",
            "trunk-and-tag-landed",
            "promoted-and-swept",
            "one-sha-one-build",
            "scm-release-published")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, RELEASED_SLUG, step);
    }
    from(RELEASED_SLUG, StoryIdentities.PIPELINE, "POST " + StoryTarget.BRANCH_EXECUTE_RELEASE_PATH + " -> 200");
    registryReads(RELEASED_SLUG, StoryTarget.SERVICE_REPO);
    gitHostReads(RELEASED_SLUG, StoryTarget.SERVICE_REPO);
    to(
        RELEASED_SLUG,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.written(StoryTarget.PROJECT, StoryTarget.SERVICE_REPO));
    // The credential this service presents to all three peers. Three clients minted three tokens on
    // this release and they are ONE arrow — an edge is (kind, from, to, label) and the three agree
    // in all four — and the mint is cached for an hour, so no later story carries it.
    to(RELEASED_SLUG, StoryPeers.IDP, StoryPeers.posted(StoryPeers.TOKEN_PATH, 200));
    to(RELEASED_SLUG, StoryPeers.EVENTS, StoryPeers.label("PUT", StoryPeers.EVENTS_PATH + "{id}", 201));
    // EIGHT: one in, and seven out to four peers. The count is what would notice a second registry
    // route appearing, or a promotion that quietly became a third push to a fifth place.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, RELEASED_SLUG, 8);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, RELEASED_SLUG, List.of(StoryIdentities.PIPELINE, StoryTarget.SERVICE));

    // --- the library release ----------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, LIBRARY_SLUG, UserflowReport.PASSED);
    for (String step :
        List.of("library-released", "nothing-was-promoted", "trunk-build-is-the-signal")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, LIBRARY_SLUG, step);
    }
    from(LIBRARY_SLUG, StoryIdentities.OPERATOR, "POST " + StoryTarget.BRANCH_EXECUTE_RELEASE_PATH + " -> 200");
    registryReads(LIBRARY_SLUG, StoryTarget.LIBRARY_REPO);
    gitHostReads(LIBRARY_SLUG, StoryTarget.LIBRARY_REPO);
    to(
        LIBRARY_SLUG,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.written(StoryTarget.PROJECT, StoryTarget.LIBRARY_REPO));
    to(LIBRARY_SLUG, StoryPeers.EVENTS, StoryPeers.label("PUT", StoryPeers.EVENTS_PATH + "{id}", 201));
    // SEVEN, one fewer than the release above, and the missing one is the token mint rather than
    // anything about promotion: a promotion is a push to a ref, and a push to a ref is the SAME
    // receive-pack arrow. That is the trap this count documents — the diagram cannot count pushes,
    // so "did it promote" is a step assertion and never an edge one.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, LIBRARY_SLUG, 7);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, LIBRARY_SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));

    // --- the refusal ------------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, SETTLED_SLUG, UserflowReport.PASSED);
    for (String step : List.of("already-integrated", "nothing-moved")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, SETTLED_SLUG, step);
    }
    from(SETTLED_SLUG, StoryIdentities.PIPELINE, "POST " + StoryTarget.BRANCH_EXECUTE_RELEASE_PATH + " -> 409");
    registryReads(SETTLED_SLUG, StoryTarget.SETTLED_REPO);
    gitHostReads(SETTLED_SLUG, StoryTarget.SETTLED_REPO);
    // FIVE, and the two that are NOT here are the story: no POST …/git-receive-pack, so no ref moved
    // on the host, and no PUT to qits-events, so nothing downstream was told a release happened. A
    // count is how an absence is asserted when the peer itself was legitimately reached.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SETTLED_SLUG, 5);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SETTLED_SLUG, StoryPeers.EVENTS);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, SETTLED_SLUG, List.of(StoryIdentities.PIPELINE, StoryTarget.SERVICE));

    for (String slug : List.of(RELEASED_SLUG, LIBRARY_SLUG, SETTLED_SLUG)) {
      ReportAssertions.assertNotLeaked(CATEGORY_SLUG, slug, StoryProfile.CLIENT_SECRET);
      ReportAssertions.assertNotLeaked(CATEGORY_SLUG, slug, StoryPeers.MACHINE_TOKEN);
      for (String bearer : MINTED) {
        ReportAssertions.assertNotLeaked(CATEGORY_SLUG, slug, bearer);
      }
    }
  }

  // --- fixtures -----------------------------------------------------------------------------------

  /**
   * One repository, both halves at once: a bare on the git host and a row in the registry.
   *
   * <p>The row id is a <b>generated uuid</b>, deliberately: it is what {@code
   * /projects/api/repositories/{id}} is keyed by, so the label scrubs to a template while the public
   * pair beside it — {@code qits} and the repository's name, both authored — survives verbatim. One
   * flow, both of {@code Labels}' rules, visible in one diagram.
   */
  private static void repository(String name, boolean deployable) {
    // Never handed back, and that is the point: a story here addresses its repository ONLY by the
    // public pair, exactly as a pipeline does, and could not use the row id if it wanted to.
    StoryGitHost.createRepository(StoryTarget.PROJECT, name, deployable);
    StoryPeers.register(
        new StoryPeers.Repository(
            UUID.randomUUID().toString(), StoryTarget.PROJECT, name, StoryTarget.MAIN));
  }

  /** A build container's credential: this service's audience, the admin role, a fresh mint. */
  private static String pipelineBearer() {
    String bearer =
        StoryIdentities.machineToken("qits-ci-step", StoryIdentities.ADMIN_ROLE);
    MINTED.add(bearer);
    return bearer;
  }

  // --- edge helpers --------------------------------------------------------------------------------

  /**
   * The two registry reads every name-addressed call makes. Two rather than one, deliberately: the
   * alias route answers an id alone, and a view built from the caller's own two strings would report
   * a name and a main branch nobody verified.
   */
  private static void registryReads(String slug, String name) {
    to(slug, StoryPeers.PROJECTS, StoryPeers.byNameRead(StoryTarget.PROJECT, name));
    to(slug, StoryPeers.PROJECTS, StoryPeers.repositoryRead("{id}"));
  }

  /**
   * The two arrows a mirror refresh draws, whatever it was for. The ref advertisement folds both
   * directions into one edge because the label drops the query; the pack read is what says this was
   * a read.
   */
  private static void gitHostReads(String slug, String name) {
    to(slug, StoryGitHost.SERVICE_NAME, StoryGitHost.advertisement(StoryTarget.PROJECT, name));
    to(slug, StoryGitHost.SERVICE_NAME, StoryGitHost.read(StoryTarget.PROJECT, name));
  }

  private static void from(String slug, String actor, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, slug, NetworkEdge.HTTP, actor, StoryTarget.SERVICE, label);
  }

  private static void to(String slug, String peer, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, slug, NetworkEdge.HTTP, StoryTarget.SERVICE, peer, label);
  }
}
