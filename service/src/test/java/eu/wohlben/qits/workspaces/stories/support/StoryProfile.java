package eu.wohlben.qits.workspaces.stories.support;

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
 * <p>The catalogue <b>writes</b>: a workspace, its provision, an editor and their events. Sharing
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
 *   <li><b>the three named oidc clients, ENABLED</b> — shipped off, because a platform running its
 *       peers open on qits-net behind forward-auth is a supported posture. Turning them on is what
 *       puts this service's own machine credential in the diagram, and for {@code githost} it is
 *       not optional at all: {@code RepoMirror.platformArgv} <b>refuses to run any http(s) git
 *       argv</b> without a bearer to hang on {@code -c http.extraHeader}. It is also what enables
 *       the commission call, which is gated on the default client's switch.
 *   <li><b>{@code qits.eventstream.enabled=true}</b> — the bus, which {@code %dev} and {@code %test}
 *       both keep dark. Nothing here publishes any more (the {@code SCMRelease} the release door
 *       announced left with the door), so what this proves is the boot: the jar's datasource, its
 *       Flyway lineage and its sweepers all start in a packaged process. It costs nothing in stray
 *       traffic — the stream subscriber logs "no listener wants a signature: not dialling the event
 *       stream" and never dials, and the outbox sweeper finds nothing due.
 *   <li><b>{@code qits.workspace.git.mirror-freshness-ms=0}</b> — the shipped 5 s window would make
 *       a story's fetch depend on how long the story before it took, which is an edge that comes and
 *       goes with the clock and a {@code networkHash} that never settles. Zero means every read
 *       fetches: more traffic, and the same traffic every run.
 * </ul>
 *
 * <h2>Two things are OFF, and both are stated coverage gaps</h2>
 *
 * <p><b>{@code qits.bootstrap.autorun-enabled=false} and {@code qits.services.autostart-enabled=
 * false}.</b> When a container start succeeds the host fires {@code WorkspaceContainerStarted}, and
 * two asynchronous CDI observers follow it: one waits for the daemon's autonomous bootstrap chain,
 * the other asks the freshly connected daemon for its config over the control socket and starts the
 * dev servers it declares. Both run on a schedule the story does not control, so the config read
 * would draw an arrow in whichever diagram happened to be open — and the bootstrap await holds the
 * technical process open for its whole chain timeout, which is what the workspace story polls to
 * learn that the provision is over. <b>No story here covers the bootstrap chain or the dev-server
 * autostart</b>; both are covered by the {@code @QuarkusTest} suites and by the docker-backed
 * {@code Daemon*IT}, where a real daemon really runs them.
 *
 * <p><b>The scheduler is left ON</b> and that is deliberate rather than an oversight. This service's
 * two {@code @Scheduled} methods are the agent-activity sweep (in-memory, no network) and the
 * commission reconcile, whose interval is an hour — Quarkus schedules the first execution one
 * interval out, so neither <i>tick</i> fires inside a run. The reconcile also runs from a {@code
 * StartupEvent} observer, and that one does: its listing of the credentials this service holds is a
 * real arrow, on its own thread, racing whichever story is open. It is pinned to the boot story,
 * which awaits it on the peer stub's recording before returning — the standard treatment for
 * asynchronous far-side traffic, and it turns a flake into coverage. What is still uncovered is the
 * reconcile's <b>sweep</b>: the listing here is empty, so no credential is ever given back.
 */
public class StoryProfile implements QuarkusTestProfile {

  /**
   * The secret each of the three named clients presents with its {@code client_credentials} grant.
   * It is a fixture rather than a credential — {@link StoryPeers} mints for anybody — and it is here
   * because the extension refuses to start a client that has no way to authenticate. Every story
   * asserts it never reached a report.
   */
  public static final String CLIENT_SECRET = "story-workspaces-client-secret";

  /** This catalogue's own workspace store. */
  private static final String DATABASE = "workspaces_stories_it";

  /** …and its own outbox, which the bus needs whether or not anything is published. */
  private static final String EVENTSTREAM_DATABASE = "workspaces_stories_eventstream_it";

  /** The three named oidc clients, which are also the three peers this service holds one for. */
  private static final String[] CLIENTS = {"", "githost.", "projects."};

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
    config.put("qits.auth.machine.audience", StoryIdentities.AUDIENCE);
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

    // --- this service's own credentials, all three ---------------------------------------------------
    for (String client : CLIENTS) {
      config.put("quarkus.oidc-client." + client + "client-enabled", "true");
      config.put("quarkus.oidc-client." + client + "auth-server-url", peers + "/idp");
      config.put("quarkus.oidc-client." + client + "credentials.secret", CLIENT_SECRET);
    }

    // --- the bus, on, against a stub that accepts ----------------------------------------------------
    config.put("qits.eventstream.enabled", "true");

    // --- this service's own tree, under target/ -------------------------------------------------------
    // Never the shipped ${user.home}/.qits: the CI step container's home is not this service's to
    // write in. Deliberately NOT the git host's directory either — the tree it fetches from must not
    // be the tree it keeps, or the catalogue would prove nothing about the separation.
    config.put("qits.workspaces.data-dir", "target/story-workspaces-data");
    config.put("qits.workspace.git.mirror-freshness-ms", "0");

    // --- the harness capability relay, awake and impatient ------------------------------------------
    // It is ON here — the packaged run takes no %test profile, so the shipped `true` applies — and
    // the story catalogue is where its outbound half is proved end to end: a real daemon frame, a
    // real tunnel, a real PUT at a real qits-projects. What is compressed is only the WAIT: the
    // shipped backoff is 2s doubling to 30s across twelve attempts, and a story that watched a
    // container's probe land would otherwise spend minutes doing it. The retry itself is the
    // subject, so the attempt count is left alone.
    config.put("qits.workspace.agent-capabilities.relay-retry-initial-ms", "100");
    config.put("qits.workspace.agent-capabilities.relay-retry-max-ms", "300");

    // --- what does not start -------------------------------------------------------------------------
    config.put("qits.services.autostart-enabled", "false");
    config.put("qits.bootstrap.autorun-enabled", "false");

    // --- dark outside a deployment, like %dev/%test ---------------------------------------------------
    config.put("quarkus.otel.sdk.disabled", "true");
    return config;
  }
}
