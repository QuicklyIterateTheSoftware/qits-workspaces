package eu.wohlben.qits.workspaces.stories.support;

/**
 * The one launched qits-workspaces, addressed the way every one of its surfaces is addressed — and
 * named the way a diagram names it.
 *
 * <p>{@code quarkus.rest.path=/workspaces/api} is the JSON API, {@code
 * quarkus.http.non-application-root-path=/workspaces/q} is what Quarkus itself serves, and {@code
 * /workspaces/daemon/{id}} is a raw WebSocket that follows neither — a {@code @WebSocket} path is a
 * literal, which is why the segment is spelled into it by hand. The framework's shipped RestAssured
 * tap skips any path carrying a {@code /q/} <b>segment</b> rather than a leading one, so it is
 * exactly right here: a story's readiness probe draws nothing, and no story class overrides the
 * predicate.
 *
 * <p>The <b>port is random</b> — failsafe launches the artifact with {@code
 * quarkus.http.test-port=0} — so nothing here is a constant except the paths, and RestAssured is
 * handed the port by the Quarkus integration-test extension.
 *
 * <h2>Authored literals and generated ids, on purpose</h2>
 *
 * <p>{@link eu.wohlben.qits.userflows.Labels} rewrites only the path segments it can tell were
 * generated — a uuid, a long hex run, a bare number — and this catalogue uses <b>both</b> kinds
 * deliberately, so the two rules are visible side by side in one diagram set:
 *
 * <ul>
 *   <li>The release fixtures carry a <b>generated uuid</b> row id, so {@code GET
 *       /projects/api/repositories/{id}} is template-shaped, while the <i>public</i> identity beside
 *       it — {@code /projects/api/projects/qits/repositories/by-name/story-service} — is authored
 *       and survives verbatim. One request, both rules, and the label says which half of the
 *       repository identity is addressable outside the registry that minted it.
 *   <li>The workspace fixture carries an <b>authored</b> row id ({@link #WORKSPACE_REPO_ID}),
 *       because it travels into a <i>container name</i> ({@code qits-ws-<label>-<repoId[0:8]>}) and
 *       out again as a path segment of every qits-containers call. A uuid there would put eight run
 *       -local hex characters inside a longer segment, which {@code Labels} correctly refuses to
 *       rewrite (it is not a whole segment) — and a {@code networkHash} that moves every run is the
 *       only symptom. The rule to take away: an id that reaches a label <b>inside</b> a segment has
 *       to be authored.
 * </ul>
 *
 * <p><b>A query string never reaches a label from the shipped tap.</b> It labels {@code METHOD
 * <scrubbed PATH> -> <status>} and drops the query entirely, so {@code ?projectId=…&repositoryName=…}
 * is invisible to the release door's arrow — the addressing form is a step, not an edge. The
 * corollary is the trap: two routes differing only in their query are ONE edge.
 */
public final class StoryTarget {

  /** How every diagram in this catalogue names the service under test, on both sides of an edge. */
  public static final String SERVICE = "qits-workspaces";

  /** {@code /workspaces/api} — {@code quarkus.rest.path}. A resource's {@code @Path} is relative. */
  public static final String API_PATH = "/workspaces/api";

  /** The release door this session drove seven services through. */
  public static final String BRANCH_RELEASE_PATH = API_PATH + "/branches/release";

  /**
   * The door split's execution arm — the landing the public door used to perform, kept whole for
   * the gated executions and the operator's direct hand. The mechanics stories drive this one; the
   * public door creates release requests in qits-projects now.
   */
  public static final String BRANCH_EXECUTE_RELEASE_PATH = API_PATH + "/branches/execute-release";

  /** Workspaces, addressed by their own id — the collection. */
  public static final String WORKSPACES_PATH = API_PATH + "/workspaces";

  /** One workspace, as the diagram carries it: a row id is a bare number, so it scrubs. */
  public static final String WORKSPACE_LABEL_PATH = WORKSPACES_PATH + "/{id}";

  /** …and the on-demand container start, likewise templated. */
  public static final String ENSURE_CONTAINER_LABEL_PATH = WORKSPACE_LABEL_PATH + "/ensure-container";

  /** The narrative record of what flowed through a repository. */
  public static final String HISTORY_PATH = API_PATH + "/history";

  /** The web editor's one door — {@code POST …/editor/ensure?repositoryId=<wrapper>}. */
  public static final String EDITOR_ENSURE_PATH = API_PATH + "/editor/ensure";

  /**
   * The control socket every workspace container dials on boot, as the diagram carries it. The
   * literal path is {@code /workspaces/daemon/<rowId>} and the row id is a bare number, so a
   * hand-written socket edge has to spell the template — {@code NetworkCapture.observe} labels are
   * <b>not</b> scrubbed at drain, unlike a source-supplied one.
   */
  public static final String DAEMON_LABEL_PATH = "/workspaces/daemon/{id}";

  // --- the project and its repositories, all authored ------------------------------------------

  /** The project every fixture repository belongs to. Authored, so it survives a label. */
  public static final String PROJECT = "qits";

  /** A deployable repository: it carries {@code .config/qits/deployments.yml}, so it promotes. */
  public static final String SERVICE_REPO = "story-service";

  /** A library: no deployment spec, so a release lands on the trunk and stops there. */
  public static final String LIBRARY_REPO = "story-library";

  /** A repository whose branch is already in its trunk — the "nothing to release" refusal. */
  public static final String SETTLED_REPO = "story-settled";

  /** The repository the workspace stories create a workspace in. */
  public static final String WORKSPACE_REPO = "story-workspace";

  /**
   * …and its row id, authored rather than generated — see the class javadoc. Twenty characters, so
   * {@code shortRepo} takes {@code story-wo} into every container name.
   */
  public static final String WORKSPACE_REPO_ID = "story-workspace-repo";

  /** The first eight characters of the id above, which is what rides in a container name. */
  public static final String WORKSPACE_REPO_SHORT = "story-wo";

  /** A repository id nothing has ever been worked in. Authored, and never registered anywhere. */
  public static final String UNWORKED_REPO_ID = "story-unworked-repo";

  // --- the project wrapper the web editor rides -------------------------------------------------

  /**
   * A project's <b>wrapper</b> repository — archetype {@code PROJECT}, the superproject whose main
   * workspace <em>is</em> the project editor ({@code WorkspacePostures.isWrapperMain}). The editor
   * story registers it as a wrapper so {@code POST /editor/ensure} resolves a real editor.
   */
  public static final String WRAPPER_REPO = "story-editor";

  /**
   * …and its row id, authored rather than generated for the same reason {@link #WORKSPACE_REPO_ID}
   * is: it travels into the editor container's name ({@code qits-ws-main-<repoId[0:8]>}) as eight
   * characters <i>inside</i> a segment, which {@code Labels} correctly refuses to rewrite — so a
   * uuid there would move the {@code networkHash} every run. The rule: an id that reaches a label
   * inside a segment has to be authored.
   */
  public static final String WRAPPER_REPO_ID = "story-wrapper-repo";

  /** The first eight characters of the id above, which is what rides in the editor container name. */
  public static final String WRAPPER_REPO_SHORT = "story-wr";

  /**
   * The workspace id the editor rides: the wrapper's main branch, slugged. {@code
   * WorkspaceService.toWorkspaceSlug("main")} is {@code "main"}, so this is what the container name
   * and every path segment carry.
   */
  public static final String MAIN_WORKSPACE_LABEL = "main";

  /**
   * The container name the editor's main workspace gets — {@code qits-ws-<label>-<repoId[0:8]>},
   * authored end to end so it survives a label verbatim.
   */
  public static final String EDITOR_CONTAINER_NAME =
      "qits-ws-" + MAIN_WORKSPACE_LABEL + "-" + WRAPPER_REPO_SHORT;

  // --- branches ---------------------------------------------------------------------------------

  /** The default branch of every fixture origin, and the one branch only a release may write. */
  public static final String MAIN = "main";

  /**
   * Where a release is promoted to. The shipped default is {@code environment/prod}; the platform's
   * dev tier names this one, and the profile sets it — a release lands on the trunk and is pushed
   * again onto the ref the environment listens to.
   */
  public static final String ENTRY_BRANCH = "environment/dev";

  /** The branch the release stories release. */
  public static final String WORK_BRANCH = "story-work";

  /** A branch whose commits main already carries — the "already integrated" refusal. */
  public static final String LANDED_BRANCH = "story-landed";

  /** The label the workspace stories ask for, which is also the branch they claim. */
  public static final String WORKSPACE_LABEL = "story-work";

  /** The container name a workspace of that label in that repository gets. */
  public static final String CONTAINER_NAME =
      "qits-ws-" + WORKSPACE_LABEL + "-" + WORKSPACE_REPO_SHORT;

  private StoryTarget() {}

  /** The release door's query string — the public identity pair, which the label never carries. */
  public static String releaseQuery(String projectId, String repositoryName) {
    return "?projectId=" + projectId + "&repositoryName=" + repositoryName;
  }

  /** The request body of a release: the branch to land and the subject after the version scope. */
  public static String releaseBody(String branch, String summary) {
    return "{\"branch\":\"" + branch + "\",\"summary\":\"" + summary + "\"}";
  }

  /** The address of one workspace — what a create hands back and every later verb is keyed by. */
  public static String workspacePath(long id) {
    return WORKSPACES_PATH + "/" + id;
  }
}
