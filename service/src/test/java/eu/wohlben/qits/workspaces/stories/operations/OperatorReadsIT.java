package eu.wohlben.qits.workspaces.stories.operations;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import eu.wohlben.qits.workspaces.stories.creation.WorkspaceProvisionIT;
import eu.wohlben.qits.workspaces.stories.support.StoryGitHost;
import eu.wohlben.qits.workspaces.stories.support.StoryIdentities;
import eu.wohlben.qits.workspaces.stories.support.StoryNetwork;
import eu.wohlben.qits.workspaces.stories.support.StoryPeers;
import eu.wohlben.qits.workspaces.stories.support.StoryProfile;
import eu.wohlben.qits.workspaces.stories.support.StoryTarget;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>What an operator's reads cost</b> — the two shapes this service's read surface has, and the
 * difference between them is the whole point of the pair.
 *
 * <p>A workspace listing is a <b>live</b> read: it answers how far each branch is ahead of and
 * behind its parent, and whether a container is running, and neither of those is a column. So it
 * refreshes the repository's mirror from the git host and asks qits-containers what it holds — one
 * browser page, four arrows. That is worth having drawn, because it is the read a client polls.
 *
 * <p>The history is the opposite: rows this context owns, in its own database, about work that has
 * already flowed through a repository. It reaches nothing. A story whose subject is that absence is
 * the one an assertion can make and a presence check cannot.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OperatorReadsIT {

  static final String CATEGORY = "operations";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String LISTING = "Opening the workspace list costs a fetch and a container listing";

  static final String QUIET = "Reading the history of an untouched repository starts nothing";

  static final String LISTING_SLUG = Slugs.slug(LISTING);

  static final String QUIET_SLUG = Slugs.slug(QUIET);

  @BeforeAll
  static void tapEveryEndOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = LISTING, category = CATEGORY)
  @UserStoryDescription(
      """
      The workspace list is the screen somebody lives on, and every row on it carries two things
      that are not stored anywhere: how far the branch is ahead of and behind its parent, and
      whether its container is up. Both are answers somebody else owns.

      So the read refreshes this repository's mirror from the git host — every time, because a
      freshness window would serve a browser a count taken before the push it is looking at — and
      then computes ahead/behind locally against the objects it just fetched. The container half is
      one listing of everything qits-containers holds for this service, rather than a status call
      per row, because a page with twelve workspaces on it would otherwise be twelve round trips.

      The repository is a FILTER here and never a parent segment: this context does not own
      repositories, it holds a repository id as a string in a different database with no foreign key
      and no join, so `/repositories/{id}/workspaces` would assert a containment the model
      deliberately does not have.
      """)
  @UserflowRunsAfter(WorkspaceProvisionIT.class)
  @Order(1)
  void theWorkspaceListIsALiveRead(Interactions story) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);

    StoryIdentities.person(given())
        .when()
        .get(StoryTarget.WORKSPACES_PATH + "?repositoryId=" + StoryTarget.WORKSPACE_REPO_ID)
        .then()
        .statusCode(200)
        .body("entries", hasSize(1))
        .body("entries.workspace.workspaceId", hasItem(StoryTarget.WORKSPACE_LABEL))
        .body("entries.workspace.status", hasItem("ACTIVE"));
    story
        .note(
            "the operator opens the workspace list for a repository and gets the one workspace that"
                + " was provisioned into it, with its live half filled in")
        .as("list-served");

    story
        .note(
            "answering it cost a mirror refresh from the git host and one container listing — the"
                + " ahead/behind counts and the container's state are somebody else's answers, and"
                + " neither is a column this service could have read instead")
        .as("what-a-listing-costs");
  }

  @UserStory(value = QUIET, category = CATEGORY)
  @UserStoryDescription(
      """
      The history is the narrative record of every workspace that ever flowed through a repository —
      active and resolved alike — and it is the read a person opens to find out what was done and
      why. It survives resolution, which is the whole reason it is a separate surface from the
      workspace itself: a resolved workspace has no container, no daemon and no branch to be ahead
      of anything, and serving one from the live route would answer with a row whose live half is
      uniformly null.

      Being nothing but rows is what makes this story worth telling. Asked about a repository nobody
      has ever worked in, it answers an empty list — and it asks NOBODY anything to do it. Not the
      registry, so a repository id it has never seen is not a lookup; not the git host, so a
      browser polling a quiet screen is not a stream of fetches; not qits-containers.

      That is the claim a presence check cannot make, and it is the one that would notice a
      convenience creeping in — a "resolve the repository's name for the header" that turned every
      history page into a registry call.
      """)
  @UserflowRunsAfter(WorkspaceProvisionIT.class)
  @Order(2)
  void aHistoryReadReachesNobody(Interactions story) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);

    StoryIdentities.person(given())
        .when()
        .get(StoryTarget.HISTORY_PATH + "?repositoryId=" + StoryTarget.UNWORKED_REPO_ID)
        .then()
        .statusCode(200)
        .body("entries", hasSize(0));
    story
        .note(
            "a repository nobody has worked in has an empty history, and answering that is a read"
                + " of this context's own store and nothing else")
        .as("empty-history");

    story
        .note(
            "no registry lookup, no mirror refresh, no container listing — one arrow in and none"
                + " out, which is what a screen somebody leaves open should cost")
        .as("nothing-was-asked");
  }

  @AfterAll
  static void bothReadStoriesAreComplete() {
    // --- the live read ---------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, LISTING_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, LISTING_SLUG, "list-served");
    ReportAssertions.assertStepId(CATEGORY_SLUG, LISTING_SLUG, "what-a-listing-costs");
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        LISTING_SLUG,
        NetworkEdge.HTTP,
        StoryIdentities.OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.WORKSPACES_PATH + " -> 200");
    to(LISTING_SLUG, StoryPeers.PROJECTS, StoryPeers.repositoryRead(StoryTarget.WORKSPACE_REPO_ID));
    to(
        LISTING_SLUG,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.advertisement(StoryTarget.PROJECT, StoryTarget.WORKSPACE_REPO));
    to(
        LISTING_SLUG,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.read(StoryTarget.PROJECT, StoryTarget.WORKSPACE_REPO));
    to(LISTING_SLUG, StoryPeers.CONTAINERS, StoryPeers.read(StoryPeers.listingPath()));
    // FIVE, and no push: a read never moves a ref, which is the negative half of "every ref this
    // service moves is moved by a push".
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, LISTING_SLUG, 5);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, LISTING_SLUG, StoryPeers.EVENTS);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, LISTING_SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));

    // --- the read that reaches nobody -------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, QUIET_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, QUIET_SLUG, "empty-history");
    ReportAssertions.assertStepId(CATEGORY_SLUG, QUIET_SLUG, "nothing-was-asked");
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        QUIET_SLUG,
        NetworkEdge.HTTP,
        StoryIdentities.OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.HISTORY_PATH + " -> 200");
    // The four absences, named one by one, and then the count. Named because an absence has to say
    // WHICH thing stayed untouched; counted because four absences would not notice a fifth peer.
    for (String peer :
        List.of(StoryPeers.PROJECTS, StoryPeers.CONTAINERS, StoryPeers.EVENTS,
            StoryGitHost.SERVICE_NAME)) {
      ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, QUIET_SLUG, peer);
    }
    ReportAssertions.assertNoEdgesFrom(CATEGORY_SLUG, QUIET_SLUG, StoryTarget.SERVICE);
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, QUIET_SLUG, 1);
  }

  private static void to(String slug, String peer, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, slug, NetworkEdge.HTTP, StoryTarget.SERVICE, peer, label);
  }
}
