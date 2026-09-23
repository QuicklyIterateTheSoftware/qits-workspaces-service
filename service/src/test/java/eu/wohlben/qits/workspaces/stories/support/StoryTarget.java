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
 *   <li>A <b>generated uuid</b> row id is template-shaped in a label, so {@code GET
 *       /projects/api/repositories/{id}} reads as the route rather than as one run's fixture.
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
 * <scrubbed PATH> -> <status>} and drops the query entirely, so {@code ?repositoryId=…} is invisible
 * to a door's arrow — the scope is a step, not an edge. The corollary is the trap: two routes
 * differing only in their query are ONE edge.
 */
public final class StoryTarget {

  /** How every diagram in this catalogue names the service under test, on both sides of an edge. */
  public static final String SERVICE = "qits-workspaces";

  /** {@code /workspaces/api} — {@code quarkus.rest.path}. A resource's {@code @Path} is relative. */
  public static final String API_PATH = "/workspaces/api";

  /**
   * The branch-keyed merge door — one of the two verbs left under {@code /branches} now that the
   * release door has gone to qits-projects. It is a person's door, and the refusal stories drive
   * it for exactly that reason.
   */
  public static final String BRANCH_MERGE_PATH = API_PATH + "/branches/merge";

  /** The retired release doors, so a story can assert they answer nothing. */
  public static final String BRANCH_RELEASE_PATH = API_PATH + "/branches/release";

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

  /**
   * The repository the refusal stories address. Authored and never registered anywhere: every
   * refusal there happens before anything is looked up, and the one that does look up is armed to
   * fail on the far side.
   */
  public static final String REFUSAL_REPO_ID = "story-refusal-repo";

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

  // --- the one editor -----------------------------------------------------------------------------

  /**
   * The editor workspace's label and its sentinel repository id, which are the same word.
   *
   * <p>There is one editor for the platform, so nothing about it is generated and nothing needs
   * authoring to keep a diagram stable: the row belongs to no repository, and both halves of its
   * container name are constants. What this replaced was a project's <b>wrapper</b> repository —
   * archetype {@code PROJECT}, whose main workspace <em>was</em> that project's editor — whose row
   * id had to be a literal precisely because eight characters of it travelled inside a container
   * name where {@code Labels} could not rewrite them.
   */
  public static final String EDITOR_LABEL = "editor";

  /**
   * The container name the editor gets — {@code qits-ws-<label>-<repoId[0:8]>} like every other
   * workspace's, except that both halves are the same constant, so the whole name is one.
   */
  public static final String EDITOR_CONTAINER_NAME = "qits-ws-" + EDITOR_LABEL + "-" + EDITOR_LABEL;

  // --- branches ---------------------------------------------------------------------------------

  /** The default branch of every fixture origin, and the one branch only a release may write. */
  public static final String MAIN = "main";

  /** The branch a refusal story asks about, which nothing ever reads. */
  public static final String WORK_BRANCH = "story-work";

  /** The label the workspace stories ask for, which is also the branch they claim. */
  public static final String WORKSPACE_LABEL = "story-work";

  /** The container name a workspace of that label in that repository gets. */
  public static final String CONTAINER_NAME =
      "qits-ws-" + WORKSPACE_LABEL + "-" + WORKSPACE_REPO_SHORT;

  private StoryTarget() {}

  /** The merge door's query string: a branch has no id of its own, so the repository narrows. */
  public static String repositoryQuery(String repositoryId) {
    return "?repositoryId=" + repositoryId;
  }

  /** The request body of a branch merge: what to land, and where. */
  public static String mergeBody(String source, String target) {
    return "{\"source\":\"" + source + "\",\"target\":\"" + target + "\"}";
  }

  /** The address of one workspace — what a create hands back and every later verb is keyed by. */
  public static String workspacePath(long id) {
    return WORKSPACES_PATH + "/" + id;
  }
}
