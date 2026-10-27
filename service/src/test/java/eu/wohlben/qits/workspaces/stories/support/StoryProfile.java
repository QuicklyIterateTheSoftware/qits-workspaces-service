package eu.wohlben.qits.workspaces.stories.support;

import eu.wohlben.qits.archrules.NecessaryTestProfileDuplication;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.workspaces.wiring.testdb.EmbeddedPg;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>One launched qits-workspaces for the whole story catalogue</b>, and every seam a story moves,
 * declared once.
 *
 * <p>A {@code @TestProfile} is what failsafe launches a process for, so two profiles would be two
 * qits-workspaces — two boots, two JWKS fetches, two database sets, two mirror trees, and a diagram
 * whose startup traffic landed in whichever process happened to be running. Every story class
 * therefore names this one, {@code api.TokenValidationBootstrapIT} included: it is a story class
 * like the others and it owns the boot.
 *
 * <h2>Its own databases</h2>
 *
 * <p>The catalogue <b>writes</b>: a workspace, its provision, an agent and their events. Sharing
 * the surefire suite's databases would make either suite's assertions depend on whether the other
 * had run, so the names here are this profile's own, and both are cleaned at start so a re-run does
 * not read the last run's rows.
 *
 * <h2>Every key below is a RUNTIME key</h2>
 *
 * <p>A packaged process takes its configuration as {@code -D} arguments on a jar that was already
 * built, so a build-time key here would be silently ignored and the stories would prove the opposite
 * of what they say. That is this repository's own worst bug class and it has its own paragraph in
 * AGENTS.md; the two that only look like environment ({@code QITS_RESOURCE_DB_*}, {@code
 * QITS_RESOURCE_EVENTSTREAM_*}) are spelled the way a deployer spells them, so the shipped {@code
 * ${…}} expressions in the domain jar's own defaults stay under test rather than being bypassed by a
 * second spelling.
 *
 * <h2>The seams, and why each one is moved</h2>
 *
 * <ul>
 *   <li><b>{@code qits.auth.machine.required=true}</b> — the gate. The shipped tenant is {@code
 *       quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}}, so this one key is the
 *       difference between a service that validates machine bearers and one that does not. Every
 *       refusal in this catalogue is a claim only a gate-on packaged run can make, and <b>no other
 *       suite in this repository turns it on against the shipped {@code auth-server-url} +
 *       {@code jwks-path} pair</b>.
 *   <li><b>{@code quarkus.oidc.auth-server-url}</b> — where the idp is. Discovery stays off and
 *       {@code jwks-path} stays {@code jwks}, joined onto this url, so the shipped boot-time fetch
 *       is exercised rather than replaced.
 *   <li><b>{@code qits.githost.url}</b> — {@link StoryGitHost}, a real smart-HTTP git server. The
 *       packaged artifact carries {@code ConfiguredGitHostAddress} and no test double can win over
 *       it, so a story whose subject is a clone or a push needs a git host that answers over HTTP
 *       or it is not about this service's git at all.
 *   <li><b>{@code qits.projects.url} / {@code qits.containers.url} / {@code qits.events.url}</b> —
 *       {@link StoryPeers}, one stub answering as all three.
 *   <li><b>the one named oidc client, {@code qits}, ENABLED</b> — shipped off, because a platform
 *       running its peers open on qits-net behind forward-auth is a supported posture. Turning it on
 *       is what puts this service's own machine credential in the diagram — the bearer sent to
 *       qits-containers, to qits-githost and to qits-projects, and the Basic pair the commission call
 *       presents to qits-idp, all one client now (epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4). For git it is
 *       not optional at all: {@code RepoMirror.platformArgv} <b>refuses to run any http(s) git
 *       argv</b> without a bearer to hang on {@code -c http.extraHeader}.
 *   <li><b>{@code qits.eventstream.enabled=true}</b> — the bus, which {@code %dev} and {@code %test}
 *       both keep dark. Nothing here publishes any more (the {@code SCMRelease} the release door
 *       announced left with the door), but the service listens: {@code AgentWaitListener}
 *       (qits-1153) is a durable listener for {@code "*"}, so at boot the subscriber dials {@code
 *       /events/stream} and the catch-up sweep reads {@code /events/api/events}. The boot story
 *       owns both calls.
 *   <li><b>the redial backoff and the catch-up interval, both an hour</b> — the shipped values
 *       (1 s to 30 s, and 30 s) would repeat those two calls through the whole run, and each repeat
 *       is an edge in whichever story is running at that moment. With an hour, each call happens at
 *       boot and not again during the run. The stand-in qits-events has no websocket, so the dial
 *       is refused; the stream itself is the eventstream jar's own suite's question.
 *   <li><b>{@code qits.workspace.git.mirror-freshness-ms=0}</b> — the shipped 5 s window would make
 *       a story's fetch depend on how long the story before it took, which is an edge that comes and
 *       goes with the clock and a {@code networkHash} that never settles. Zero means every read
 *       fetches: more traffic, and the same traffic every run.
 * </ul>
 *
 * <h2>One thing is OFF, and it is a stated coverage gap</h2>
 *
 * <p><b>{@code qits.bootstrap.autorun-enabled=false}.</b> When a container start succeeds the host
 * fires {@code WorkspaceContainerStarted}, and an asynchronous CDI observer follows it and waits for
 * the daemon's autonomous bootstrap chain. It runs on a schedule the story does not control, and the
 * bootstrap await holds the technical process open for its whole chain timeout, which is what the
 * workspace story polls to learn that the provision is over. <b>No story here covers the bootstrap
 * chain</b>; it is covered by the {@code @QuarkusTest} suites and by the docker-backed {@code
 * Daemon*IT}, where a real daemon really runs it.
 *
 * <p><b>The scheduler is left ON</b> and that is deliberate rather than an oversight. This service's
 * {@code @Scheduled} methods are the agent-activity sweep (in-memory, no network), the commission
 * reconcile, whose interval is an hour, and the lease reconcile, switched off here (below): an
 * interval job's first run is at boot, measured on the lease reconcile, so whatever an interval job
 * asks of a peer is boot traffic in the boot story. The commission reconcile also runs from a {@code
 * StartupEvent} observer, and that one does: its listing of the credentials this service holds is a
 * real arrow, on its own thread, racing whichever story is open. It is pinned to the boot story,
 * which awaits it on the peer stub's recording before returning — the standard treatment for
 * asynchronous far-side traffic, and it turns a flake into coverage. What is still uncovered is the
 * reconcile's <b>sweep</b>: the listing here is empty, so no credential is ever given back.
 *
 * <h2>Why it is a second profile at all</h2>
 *
 * <p>It is the only profile here that configures a <b>launched packaged process</b>, and that is not
 * a matter of degree. Every other profile in this module overlays an in-JVM {@code @QuarkusTest};
 * this one names the databases a separate process creates and cleans at start, points a real
 * boot-time JWKS fetch at {@link MockIdp}, and puts a git server and a peer stub on the far end of
 * calls that leave the process. Folding any of it into a surefire profile would change what thirty
 * classes boot against — different stores, a gate they do not want, otel on — and folding a surefire
 * profile into this one would put keys a packaged jar silently ignores into the one place this
 * repository's worst bug class lives. The budget rule counts it because it counts every
 * implementation in the module; it costs the surefire fork nothing, because failsafe launches it.
 */
public class StoryProfile implements QuarkusTestProfile, NecessaryTestProfileDuplication {

  /**
   * The secret the one named client presents with its {@code client_credentials} grant. It is a
   * fixture rather than a credential — {@link StoryPeers} mints for anybody — and it is here because
   * the extension refuses to start a client that has no way to authenticate. Every story asserts it
   * never reached a report.
   */
  public static final String CLIENT_SECRET = "story-workspaces-client-secret";

  /**
   * The public domain the catalogue runs under: an admin workspace's container holds a workspace
   * token, and a token only works through an edge plane (qits-1084). Never a host anything resolves.
   */
  public static final String STORY_DOMAIN = "story.example";

  /** This catalogue's own workspace store. */
  private static final String DATABASE = "workspaces_stories_it";

  /** …and its own outbox, which the bus needs whether or not anything is published. */
  private static final String EVENTSTREAM_DATABASE = "workspaces_stories_eventstream_it";

  @Override
  public Map<String, String> getConfigOverrides() {
    // All three stand-ins start HERE, before the application, and park their coordinates in system
    // properties: a test profile is instantiated in more than one classloader, and the property
    // table is the one thing every copy (and a story method's own reads) shares.
    MockIdp idp = MockIdp.ensureStarted();
    String peers = StoryPeers.ensureStarted();
    String gitHost = StoryGitHost.ensureStarted();

    Map<String, String> config = new LinkedHashMap<>();

    // --- the two databases a deployment creates before the container starts ---------------------
    // `resources: postgresql:db, postgresql:eventstream:…` in .config/qits/deployments.yml, and the
    // variable names follow the resource NAME — which is why they are spelled here exactly as the
    // deployer would spell them, rather than as the datasource keys they end up filling.
    config.put("QITS_RESOURCE_DB_URL", EmbeddedPg.url(DATABASE));
    config.put("QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER);
    config.put("QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);
    config.put("QITS_RESOURCE_EVENTSTREAM_URL", EmbeddedPg.url(EVENTSTREAM_DATABASE));
    config.put("QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER);
    config.put("QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD);
    // A re-run must not read the last run's rows. Both stores, because the catalogue writes to both.
    config.put("quarkus.flyway.workspaces.clean-at-start", "true");
    config.put("quarkus.flyway.eventstream.clean-at-start", "true");

    // --- the gate, turned on ---------------------------------------------------------------------
    config.put("qits.auth.machine.required", "true");
    // The audience is NOT set here. It is the one platform audience and the jar ships it
    // (StoryIdentities.AUDIENCE is that same value), so what these stories present a token for is
    // what a deployment really enforces rather than a second spelling this profile invented.
    config.put("quarkus.oidc.auth-server-url", idp.baseUrl());
    // The dev identity is LaunchMode-guarded and a launched artifact is NORMAL, so this changes
    // nothing — it is here so the refusal stories cannot be read as "the dev user happened to be
    // absent". An anonymous request really is anonymous.
    config.put("qits.auth.forward.dev-user", "");

    // --- the peers ---------------------------------------------------------------------------------
    config.put("qits.projects.url", peers);
    config.put("qits.containers.url", peers);
    config.put("qits.events.url", peers);
    config.put("qits.githost.url", gitHost);

    // --- this service's own credential, the one named client `qits` -----------------------------
    config.put("quarkus.oidc-client.qits.client-enabled", "true");
    config.put("quarkus.oidc-client.qits.auth-server-url", peers + "/idp");
    config.put("quarkus.oidc-client.qits.credentials.secret", CLIENT_SECRET);

    // --- the bus, on, against a stub that accepts ----------------------------------------------------
    config.put("qits.eventstream.enabled", "true");
    // Once at boot and not again during the run — see the class javadoc.
    config.put("qits.eventstream.redial-initial-backoff", "PT1H");
    config.put("qits.eventstream.redial-max-backoff", "PT1H");
    config.put("qits.eventstream.catchup-interval", "PT1H");
    // The lease reconcile (epic qits-1481) is OFF: an interval job's first run is at boot, not one
    // interval out (measured: at PT1H it still ran during the boot story), and each run mints this
    // service's bearer (POST /idp/token) to ask qits-system, which no stand-in here serves, for its
    // leases — an arrow in whichever story is open, and half of one. No story covers leases; the
    // @QuarkusTest suites drive TargetLeaseEvents.reconcile() against a stub.
    config.put("qits.workspaces.target-leases.reconcile-interval", "off");

    // --- this service's own tree, under target/ -------------------------------------------------------
    // Never the shipped ${user.home}/.qits: the CI step container's home is not this service's to
    // write in. Deliberately NOT the git host's directory either — the tree it fetches from must not
    // be the tree it keeps, or the catalogue would prove nothing about the separation.
    config.put("qits.workspaces.data-dir", "target/story-workspaces-data");
    config.put("qits.workspace.git.mirror-freshness-ms", "0");

    // --- an edge plane, whatever the step container's QITS_DOMAIN says -----------------------------
    // With a public domain an admin workspace is minted a workspace token and addressed through
    // the edge (qits-1084); with none, and an issuer wired, its provision is refused. The provision
    // story tells the token's arc — a mint at POST /idp/api/tokens, QITS_TOKEN in the spec — so the
    // domain is pinned to a public one here, and an ambient QITS_DOMAIN in the launching environment
    // cannot turn the story into a different one.
    config.put("qits.workspace.domain", STORY_DOMAIN);

    // --- what does not start -------------------------------------------------------------------------
    config.put("qits.bootstrap.autorun-enabled", "false");

    // --- dark outside a deployment, like %dev/%test ---------------------------------------------------
    config.put("quarkus.otel.sdk.disabled", "true");
    return config;
  }
}
