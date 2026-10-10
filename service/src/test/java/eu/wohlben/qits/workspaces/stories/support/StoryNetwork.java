package eu.wohlben.qits.workspaces.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;

/**
 * <b>Every end of every diagram in this catalogue, wired in one call</b> — so a story class's
 * {@code @BeforeAll} is one line and no class can wire half of it.
 *
 * <p>There are four feeds and they are four different mechanisms:
 *
 * <ul>
 *   <li><b>the near side</b>, {@link NetworkTaps#restAssured}: every request a story makes becomes
 *       {@code <actor> -> qits-workspaces}, labelled {@code METHOD <scrubbed path> -> <status>}. The
 *       framework ships it; this repository's hand-copied {@code StoryNetworkFilter} was deleted
 *       when these stories were written. It is idempotent per service, which is why every class may
 *       call this method;
 *   <li><b>the idp's recording</b>, cumulative and <b>with no floor</b>: the JWKS fetch this service
 *       makes at STARTUP happens before any story exists and is the whole subject of the first one,
 *       so it must be attributable rather than filtered away;
 *   <li><b>the peers' access log</b>, {@link StoryPeers#install()} — qits-projects, qits-containers,
 *       qits-platform-idp's token and commission endpoints, and qits-events. Cumulative and, like
 *       the idp's, with <b>no floor</b>: the recording is wiped when the stub starts, and the
 *       commission reconcile writes to it from a {@code StartupEvent} observer before any story has
 *       run;
 *   <li><b>the git host's access log</b>, {@link StoryGitHost#install()} — the only place a
 *       clone, a fetch, an {@code ls-remote} or a push exists at all, because all four happen on the
 *       far side of a socket from this JVM.
 * </ul>
 *
 * <p>The one plane nothing here taps is the <b>daemon control socket</b>. The framework ships no
 * socket tap and could not: a frame is not a request, and only the party holding the connection
 * knows which direction one went. {@link StoryDaemon} instruments it at the call sites, on the story
 * thread, which is the one place the framework's actor rule allows.
 *
 * <h2>Order is load-bearing, and it is the package names that carry it</h2>
 *
 * <p>A cumulative source is attributed by a cursor, so pre-story traffic lands in whichever story
 * drains FIRST. {@code UserflowClassOrderer} sorts by fully-qualified class name, so {@code
 * …workspaces.api} runs before {@code …workspaces.stories.*} and the boot story owns the startup
 * JWKS fetch; within {@code stories}, {@code creation} runs before {@code
 * operations} and {@code refusals}, which is why the outbound token mints — cached for an hour, so
 * they land once — belong to the first workspace story rather than to whoever needed one next.
 * {@code @UserflowRunsAfter} states the ones that are real dependencies as well as being true of
 * the names.
 */
public final class StoryNetwork {

  /** The id the idp's cumulative recording is registered under. Re-registering keeps its cursor. */
  private static final String IDP_SOURCE = "mock-idp";

  private StoryNetwork() {}

  /**
   * Install the near-side tap and register all three far-side recordings. Idempotent, and safe from
   * any story class's {@code @BeforeAll} — {@link NetworkCapture#source} replaces a supplier while
   * keeping its cursor, so a class that runs second does not re-attribute what the first drained.
   */
  public static void install() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    NetworkCapture.source(
        IDP_SOURCE,
        () ->
            MockIdp.attach().recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            StoryTarget.SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
    StoryPeers.install();
    StoryGitHost.install();
  }
}
