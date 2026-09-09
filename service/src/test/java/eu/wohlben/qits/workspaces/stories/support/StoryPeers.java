package eu.wohlben.qits.workspaces.stories.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Labels;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * <b>The three services qits-workspaces calls that are not the git host, plus the one it publishes
 * to</b> — one in-JVM stub impersonating all four, and the <b>outgoing</b> tap that draws what the
 * launched process asked each one.
 *
 * <pre>
 * the repository registry     qits-projects         GET  /projects/api/repositories/{id}
 *                                                   GET  /projects/api/projects/{p}/repositories
 * the agent configuration     qits-projects         GET  /projects/api/agent-configuration
 * the container orchestrator  qits-containers       GET/PUT/DELETE /containers/api/containers/{owner}/workspace…
 * the identity provider       qits-platform-idp     POST /idp/token          (this service's own credentials)
 *                                                   POST /idp/api/clients    (a workspace's commissioned one)
 * the event log               qits-events           PUT  /events/api/events/{id}   (SCMRelease)
 * </pre>
 *
 * <p><b>One process impersonates all four, and the diagram is drawn from the PATH.</b> They are four
 * urls in this service's configuration and would be four hosts on a platform; here they are four
 * contexts on one stub, and {@link #peer} maps a path prefix onto the name a reader knows the peer
 * by. Nothing about the evidence changes — direction, method, path and status are what an edge is.
 *
 * <p><b>qits-platform-idp appears here as its OUTBOUND half only.</b> {@link MockIdp} serves the
 * inbound half — the JWKS this service validates its callers against, fetched at startup over a
 * real listener — and it draws as the same node, because it is the same component. The token
 * endpoint is served here rather than stubbed on the mock because a {@code MockIdp.attach()} handle
 * has no stubbing of its own, and because a token this service <i>fetches</i> is outbound traffic
 * whose evidence belongs with the other outbound recordings.
 *
 * <h2>Stateless, with two deliberate exceptions</h2>
 *
 * <p>Almost every answer is a pure function of the path: the registry is a file the stories write
 * before they act, the token is a constant, and the idp commissions the same pair every time.
 *
 * <p>The first exception is the container table. A workspace provision <b>asks whether a container
 * is there, puts one, and later lists them</b>, and a stub that answered "absent" to all three would
 * make the provision unreadable — so an ensured ref is remembered, in a file, and the reads after it
 * answer accordingly. The state is a consequence of the story's own write, which is what the real
 * orchestrator's would be.
 *
 * <p>The second is {@link #refuse}, and it exists because <b>no story-controlled value reaches these
 * paths in a way a refusal could key on</b>: a door's registry read is {@code
 * /projects/api/repositories/<id>} for whatever repository the caller named, and "the registry is
 * down tonight" is a property of the registry rather than of the id.
 * It is spelled as a file — written by the one story about an outage, in a {@code try}/{@code
 * finally} that always clears it, wiped again when the stub starts, and read fresh on every request.
 * A file rather than a static field because the stub is started by the <b>test profile</b>, which a
 * launched-artifact run instantiates in a different classloader from the one a story method lives
 * in: two copies of this class, one file.
 *
 * <h2>The credential is minted ONCE, and that is why one diagram carries it</h2>
 *
 * <p>quarkus-oidc-client caches the token it acquires and re-mints only when it expires, and this
 * service has <b>three</b> named clients ({@code default} for qits-containers, {@code githost},
 * {@code projects}). The token here says {@code expires_in: 3600}, so all three mints land in the
 * first story that needs any of them and never again — and they draw as ONE arrow, because an edge
 * is {@code (kind, from, to, label)} and the three agree in all four.
 *
 * <p>The corollary to know when running one class alone: the first workspace story claims that
 * arrow, and any other story class run on its own inherits it and fails its own edge
 * count — loudly, which is the right way for that assumption to break. Measured elsewhere the other
 * way: at {@code expires_in: 1} the credential outlives some stories and not others, and the arrow
 * appears in whichever diagram happened to be more than a second after the last, which is a {@code
 * networkHash} that never settles.
 */
public final class StoryPeers {

  // --- how a diagram names each far side ---------------------------------------------------------

  /** The repository registry: the one context that owns what a repository is called. */
  public static final String PROJECTS = "qits-projects";

  /** The component that holds the platform's docker socket, and every workspace container. */
  public static final String CONTAINERS = "qits-containers";

  /** The identity provider — here as the token and commission endpoints. Same node MockIdp is. */
  public static final String IDP = MockIdp.SERVICE_NAME;

  /** The platform event log, where a release announces itself. */
  public static final String EVENTS = "qits-events";

  // --- the paths, exactly as the shipped clients spell them --------------------------------------

  /** {@code ProjectsRepositories}' one route, keyed by the internal row id. */
  public static final String REPOSITORY_PATH = "/projects/api/repositories/";

  /** {@code ProjectsProjectRepositories}' alias route — the public identity pair. */
  public static final String PROJECT_PATH = "/projects/api/projects/";

  /**
   * {@code ProjectsAgentConfiguration}'s one route: the resolved agent configuration a workspace
   * container is born with. Read once per provision, and the document it answers with travels into
   * the container's own environment — which is what {@code WorkspaceProvisionIT} reads back off the
   * workload spec.
   */
  public static final String AGENT_CONFIGURATION_PATH = "/projects/api/agent-configuration";

  /** {@code quarkus.oidc-client.*.token-path} joined onto the auth-server url. */
  public static final String TOKEN_PATH = "/idp/token";

  /** {@code IdpClients}' commission API, under the idp's own segment. */
  public static final String CLIENTS_PATH = "/idp/api/clients";

  /** {@code ContainersClient.CONTAINERS_PATH} plus the workspace workload segment. */
  public static final String CONTAINERS_PATH = "/containers/api/containers/";

  /** {@code ContainersClient.VOLUMES_PATH}. */
  public static final String VOLUMES_PATH = "/containers/api/volumes/";

  /** {@code EventsPublisher.EVENTS_PATH} — one PUT per published event, keyed by its id. */
  public static final String EVENTS_PATH = "/events/api/events/";

  /** The owner every container call is scoped by: {@code quarkus.oidc-client.client-id}. */
  public static final String OWNER = "qits-workspaces";

  /** The workload segment a workspace container lives under. */
  public static final String WORKLOAD = "workspace";

  // --- the constants a story reads back ----------------------------------------------------------

  /** The opaque machine token this service's three oidc clients receive. Never a real JWT. */
  public static final String MACHINE_TOKEN = "story-workspaces-machine-token";

  /**
   * The agent configuration every container in this catalogue is born with. Authored rather than
   * generated so a story can assert the bytes reached the container's environment; {@code
   * epic.chat} is the surface it looks for.
   */
  public static final String AGENT_CONFIGURATION_DOCUMENT =
      "{\"version\":1,\"generatedAt\":\"2026-09-09T00:00:00Z\",\"surfaces\":["
          + "{\"surface\":\"epic.chat\",\"harness\":\"CLAUDE\"},"
          + "{\"surface\":\"epic.agent\",\"harness\":\"CLAUDE\"},"
          + "{\"surface\":\"workspace.chat\",\"harness\":\"CLAUDE\"},"
          + "{\"surface\":\"workspace.agent\",\"harness\":\"CLAUDE\"},"
          + "{\"surface\":\"ticket.dispatch\",\"harness\":\"CLAUDE\"}]}";

  /** The client id qits-platform-idp commissions for a workspace. */
  public static final String COMMISSIONED_CLIENT_ID = "story-workspace-client";

  /** …and its secret, which travels into the container and must never reach a report. */
  public static final String COMMISSIONED_SECRET = "story-workspace-commissioned-secret";

  /** The archetype qits-projects gives a project's wrapper — what makes a repo the editor's. */
  public static final String WRAPPER_ARCHETYPE = "PROJECT";

  /** What a refused peer answers. */
  public static final int REFUSED_STATUS = 503;

  /**
   * How long qits-containers takes to put a container.
   *
   * <p><b>It is not decoration.</b> A real ensure is a docker pull and a container start, which is
   * why the provision that follows it waits for the daemon to dial home rather than assuming the
   * work is done. A stub answering in microseconds would make the workspace story a race: the story
   * plays the container, and it learns that the container was asked for by watching this recording —
   * so a half-second here is what lets it dial, shake hands and be waiting before the host is.
   */
  private static final long ENSURE_LATENCY_MILLIS = 500;

  private static final String PORT_PROPERTY = "qits.test.story-peers.port";

  private static final String SOURCE_ID = "story-peers";

  private static final Path ROOT = Path.of("target", "story-peers");

  /** The recording: one line per answered request, the shape an access log has. */
  private static final Path ACCESS_LOG = ROOT.resolve("access.log");

  /** The repository registry, written by a story before it acts on a repository. */
  private static final Path REGISTRY = ROOT.resolve("registry");

  /** Which path prefixes answer {@link #REFUSED_STATUS} right now. */
  private static final Path REFUSALS = ROOT.resolve("refusals");

  /** Which container refs this stub has been asked to put. */
  private static final Path ENSURED = ROOT.resolve("ensured");

  /** The last workload spec qits-containers was handed — how a story reads what was launched. */
  private static final Path LAST_ENSURE = ROOT.resolve("last-ensure.json");

  /** Every event published to qits-events, one compact document per line. */
  private static final Path EVENTS_LOG = ROOT.resolve("events.log");

  private static final Object LOCK = new Object();

  private static boolean registered;

  private static int harvested;

  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  private StoryPeers() {}

  // --- the server -------------------------------------------------------------------------------

  /**
   * Start the stub once per JVM and park its port, wiping whatever an earlier run left behind.
   * Called from the test profile, which is the only place that knows the urls in time.
   */
  public static synchronized String ensureStarted() {
    String port = System.getProperty(PORT_PROPERTY);
    if (port != null) {
      return baseUrl(Integer.parseInt(port));
    }
    wipe();
    HttpServer server;
    try {
      Files.createDirectories(ROOT);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException("could not start the story peers stub", e);
    }
    server.setExecutor(
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, "story-peers");
              thread.setDaemon(true);
              return thread;
            }));
    server.createContext("/", StoryPeers::handle);
    server.start();
    System.setProperty(PORT_PROPERTY, String.valueOf(server.getAddress().getPort()));
    return baseUrl(server.getAddress().getPort());
  }

  /** Scheme, host and port with no path — the shape every one of these addresses takes. */
  public static String baseUrl() {
    return baseUrl(Integer.parseInt(System.getProperty(PORT_PROPERTY)));
  }

  private static String baseUrl(int port) {
    return "http://127.0.0.1:" + port;
  }

  private static void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

    int status;
    String body;
    if (isRefused(path)) {
      status = REFUSED_STATUS;
      body = "{\"message\":\"" + path + " is unavailable\"}";
    } else {
      Answer answer = answer(method, path, request);
      status = answer.status();
      body = answer.body();
    }

    if (isEnsure(method, path)) {
      sleep();
    }

    // Recorded BEFORE the answer leaves, so a story that observed an effect can rely on the line for
    // it already being on disk. There is nothing to await.
    record(method, path, status);
    byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** One answer: the status a peer gave and the document it gave with it. */
  private record Answer(int status, String body) {}

  /**
   * What each peer answers — the smallest documents that make the shipped client bind, and no
   * larger. Every field here is one a client really reads.
   */
  private static Answer answer(String method, String path, String request) {
    if (path.startsWith(REPOSITORY_PATH)) {
      String repoId = path.substring(REPOSITORY_PATH.length());
      return registered(repoId)
          .map(row -> new Answer(200, "{\"repository\":" + row.json() + "}"))
          .orElseGet(() -> new Answer(404, notFound("no repository " + repoId)));
    }
    if (AGENT_CONFIGURATION_PATH.equals(path)) {
      // The smallest document that IS one: a version, a generatedAt and the five surfaces a
      // workspace container may serve. The shape is qits-projects'
      // (AgentConfigurationDocumentDto); what this service checks is only that it is a document
      // with surfaces in it, so a fixture that carried more would be documenting the wrong repo.
      return "GET".equals(method)
          ? new Answer(200, AGENT_CONFIGURATION_DOCUMENT)
          : new Answer(405, notFound("the agent configuration is a GET"));
    }
    if (path.startsWith(PROJECT_PATH)) {
      return projectRoute(path);
    }
    if (TOKEN_PATH.equals(path)) {
      // An hour, so the three named clients' mints land in exactly one story — see the class javadoc.
      return "POST".equals(method)
          ? new Answer(
              200,
              "{\"access_token\":\""
                  + MACHINE_TOKEN
                  + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}")
          : new Answer(405, notFound("the token endpoint is a POST"));
    }
    if (CLIENTS_PATH.equals(path)) {
      return switch (method) {
        case "POST" ->
            new Answer(
                201,
                "{\"clientId\":\""
                    + COMMISSIONED_CLIENT_ID
                    + "\",\"secret\":\""
                    + COMMISSIONED_SECRET
                    + "\"}");
        // The reconcile's read. Empty rather than a row: nothing in this catalogue reconciles, and
        // an answer that claimed a live commission would invite a sweep nobody asked for.
        case "GET" -> new Answer(200, "[]");
        default -> new Answer(405, notFound("only POST and GET live here"));
      };
    }
    if (path.startsWith(CLIENTS_PATH + "/") && "DELETE".equals(method)) {
      // Giving a credential back. 204, and 404 would mean the same thing.
      return new Answer(204, null);
    }
    if (path.startsWith(CONTAINERS_PATH)) {
      return containerRoute(method, path, request);
    }
    if (path.startsWith(VOLUMES_PATH)) {
      String name = path.substring(path.lastIndexOf('/') + 1);
      return new Answer(
          200,
          "{\"id\":null,\"owner\":\""
              + OWNER
              + "\",\"name\":\""
              + name
              + "\",\"desired\":\""
              + ("DELETE".equals(method) ? "ABSENT" : "PRESENT")
              + "\",\"existed\":true,\"detail\":null}");
    }
    if (path.startsWith(EVENTS_PATH) && "PUT".equals(method)) {
      append(EVENTS_LOG, request.replace("\n", " ") + "\n");
      return new Answer(201, "{\"accepted\":true}");
    }
    return new Answer(404, notFound("no such route"));
  }

  private static Answer projectRoute(String path) {
    // /projects/api/projects/{projectId}/repositories — the alias route the release door resolved
    // its public (project, name) pair through went with the door.
    String rest = path.substring(PROJECT_PATH.length());
    String[] segments = rest.split("/");
    if (segments.length == 2 && "repositories".equals(segments[1])) {
      String entries =
          rows().stream()
              .filter(row -> row.projectId().equals(segments[0]))
              .map(row -> "{\"repository\":" + row.json() + "}")
              .reduce((a, b) -> a + "," + b)
              .orElse("");
      return new Answer(200, "{\"entries\":[" + entries + "]}");
    }
    return new Answer(404, notFound("no such route"));
  }

  private static Answer containerRoute(String method, String path, String request) {
    // /containers/api/containers/{owner}/{workload}[/{ref}]
    String rest = path.substring(CONTAINERS_PATH.length());
    String[] segments = rest.split("/");
    if (segments.length == 2) {
      // The listing every workspace read costs — see OperatorReadsIT.
      String containers =
          ensuredRefs().stream()
              .map(ref -> envelope(ref, "RUNNING"))
              .reduce((a, b) -> a + "," + b)
              .orElse("");
      return new Answer(200, "{\"containers\":[" + containers + "]}");
    }
    if (segments.length != 3) {
      return new Answer(404, notFound("no such route"));
    }
    String ref = segments[2];
    return switch (method) {
      case "PUT" -> {
        write(LAST_ENSURE, request);
        markEnsured(ref);
        yield new Answer(200, envelope(ref, "RUNNING"));
      }
      case "GET" ->
          ensuredRefs().contains(ref)
              ? new Answer(200, envelope(ref, "RUNNING"))
              : new Answer(404, "{\"code\":\"NOT_FOUND\",\"message\":\"no container " + ref + "\"}");
      case "DELETE" -> {
        boolean existed = ensuredRefs().contains(ref);
        unmarkEnsured(ref);
        yield new Answer(
            200,
            "{\"id\":null,\"containerName\":\""
                + ref
                + "\",\"existed\":"
                + existed
                + ",\"logTail\":null,\"detail\":null}");
      }
      default -> new Answer(405, notFound("no such verb"));
    };
  }

  /** One container as the orchestrator answers it. The id is a fixture's, and rides in no label. */
  private static String envelope(String ref, String observed) {
    return "{\"id\":\"1f0d9c8b-7a65-4433-9210-fedcba987654\",\"containerName\":\""
        + ref
        + "\",\"state\":{\"desired\":\"RUNNING\",\"observed\":\""
        + observed
        + "\"},\"endpoint\":null,\"specHash\":\"story\",\"created\":true,\"detail\":null}";
  }

  private static String notFound(String message) {
    return "{\"code\":\"NOT_FOUND\",\"message\":\"" + message + "\"}";
  }

  private static boolean isEnsure(String method, String path) {
    return "PUT".equals(method) && path.startsWith(CONTAINERS_PATH) && path.split("/").length > 6;
  }

  private static void sleep() {
    try {
      Thread.sleep(ENSURE_LATENCY_MILLIS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  // --- the repository registry a story writes -----------------------------------------------------

  /**
   * One row of the registry, as both project routes answer it.
   *
   * <p>{@code archetype} is the fifth field, and it is here for one reader: the web editor's door
   * asks whether a repository is a project's wrapper ({@code PROJECT}), which is the whole of {@code
   * WorkspacePostures.isWrapperMain}. It is nullable, and the four-argument constructor leaves it so
   * — a repository whose archetype no story cares about is a plain, un-wrapper row exactly as every
   * caller predating the editor spelled it.
   */
  public record Repository(
      String id, String projectId, String name, String mainBranch, String archetype) {

    /** The four-field form every caller predating the editor spells — no archetype. */
    public Repository(String id, String projectId, String name, String mainBranch) {
      this(id, projectId, name, mainBranch, null);
    }

    String json() {
      String archetypeField =
          archetype == null ? "" : ",\"archetype\":\"" + archetype + "\"";
      return "{\"id\":\""
          + id
          + "\",\"name\":\""
          + name
          + "\",\"projectId\":\""
          + projectId
          + "\",\"mainBranch\":\""
          + mainBranch
          + "\""
          + archetypeField
          + "}";
    }
  }

  /**
   * Put one repository in the registry. Called by the story that builds the origin for it, so the
   * two halves of a repository's existence — the refs and the row — are created together.
   */
  public static synchronized void register(Repository row) {
    append(
        REGISTRY,
        String.join(
                "\t",
                row.id(),
                row.projectId(),
                row.name(),
                row.mainBranch(),
                row.archetype() == null ? "" : row.archetype())
            + "\n");
  }

  private static Optional<Repository> registered(String repoId) {
    return rows().stream().filter(row -> row.id().equals(repoId)).findFirst();
  }

  private static List<Repository> rows() {
    List<Repository> rows = new ArrayList<>();
    for (String line : lines(REGISTRY)) {
      String[] fields = line.split("\t");
      if (fields.length == 4) {
        rows.add(new Repository(fields[0], fields[1], fields[2], fields[3]));
      } else if (fields.length == 5) {
        rows.add(new Repository(fields[0], fields[1], fields[2], fields[3], fields[4]));
      }
    }
    return rows;
  }

  // --- what a story reads back --------------------------------------------------------------------

  /** The last workload spec qits-containers was handed, or null when nothing was ever ensured. */
  public static String lastEnsureRequest() {
    return Files.isRegularFile(LAST_ENSURE) ? readString(LAST_ENSURE) : null;
  }

  /** Every event published to qits-events since the run started, newest last. */
  public static List<String> publishedEvents() {
    return lines(EVENTS_LOG);
  }

  /**
   * Wait, briefly, for a recorded line containing {@code fragment} — the story's way of learning
   * that the launched process reached a peer, without polling the process itself.
   *
   * @return whether it arrived; the caller decides whether that is an assertion
   */
  public static boolean awaitCall(String fragment, java.time.Duration patience) {
    long deadline = System.nanoTime() + patience.toNanos();
    while (true) {
      for (String line : lines(ACCESS_LOG)) {
        if (line.contains(fragment)) {
          return true;
        }
      }
      if (System.nanoTime() >= deadline) {
        return false;
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  // --- the one piece of armed state ---------------------------------------------------------------

  /**
   * Make every path starting with {@code prefix} answer {@link #REFUSED_STATUS} until {@link
   * #answerNormally()} is called. One prefix at a time — a story about a broken peer is about ONE
   * broken peer and the rest of the platform answering.
   *
   * <p><b>Always in a {@code try}/{@code finally}.</b> A refusal that outlived its story would be a
   * broken peer in somebody else's diagram, and the two would look exactly alike.
   */
  public static void refuse(String prefix) {
    write(REFUSALS, prefix + "\n");
  }

  /** Clear every armed refusal. Idempotent, and safe to call when nothing was armed. */
  public static void answerNormally() {
    try {
      Files.deleteIfExists(REFUSALS);
    } catch (IOException e) {
      throw new UncheckedIOException("could not clear " + REFUSALS, e);
    }
  }

  private static boolean isRefused(String path) {
    for (String prefix : lines(REFUSALS)) {
      if (!prefix.isBlank() && path.startsWith(prefix.strip())) {
        return true;
      }
    }
    return false;
  }

  private static synchronized Set<String> ensuredRefs() {
    return new LinkedHashSet<>(lines(ENSURED));
  }

  private static synchronized void markEnsured(String ref) {
    Set<String> refs = ensuredRefs();
    if (refs.add(ref)) {
      write(ENSURED, String.join("\n", refs) + "\n");
    }
  }

  private static synchronized void unmarkEnsured(String ref) {
    Set<String> refs = ensuredRefs();
    if (refs.remove(ref)) {
      write(ENSURED, refs.isEmpty() ? "" : String.join("\n", refs) + "\n");
    }
  }

  // --- what a story class calls ---------------------------------------------------------------------

  /**
   * Register the tap once per JVM. Called from every story class's {@code @BeforeAll}.
   *
   * <p><b>There is no floor here, deliberately</b>, unlike the naive shape of this pattern. The
   * recording is wiped when the stub starts and the stub starts inside this run, so everything in it
   * belongs to this run — and some of it happens <b>before any story does</b>: the commission
   * reconcile runs from a {@code StartupEvent} observer, on its own thread, and its listing is
   * genuinely the boot story's subject. A floor taken at the first {@code @BeforeAll} would swallow
   * it exactly when the reconcile happened to be fast, which is a story that passes on a slow
   * machine and fails on a quick one. Measured, both ways round.
   */
  public static void install() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      harvested = 0;
      NetworkCapture.source(SOURCE_ID, StoryPeers::edges);
      registered = true;
    }
  }

  /** The label an answered peer call renders as — what an assertion has to spell. */
  public static String label(String method, String path, int status) {
    return Labels.scrub(method + " " + path + " -> " + status);
  }

  /** {@code GET <path> -> 200}. */
  public static String read(String path) {
    return label("GET", path, 200);
  }

  /** {@code POST <path> -> <status>}. */
  public static String posted(String path, int status) {
    return label("POST", path, status);
  }

  /** The repository read by row id — a uuid scrubs, an authored id survives. Deliberately both. */
  public static String repositoryRead(String repoId) {
    return read(REPOSITORY_PATH + repoId);
  }

  /** The container this workspace's provision addressed. */
  public static String containerPath(String ref) {
    return CONTAINERS_PATH + OWNER + "/" + WORKLOAD + "/" + ref;
  }

  /** The listing every workspace read costs. */
  public static String listingPath() {
    return CONTAINERS_PATH + OWNER + "/" + WORKLOAD;
  }

  // --- the source -------------------------------------------------------------------------------

  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      harvest();
      return List.copyOf(EDGES);
    }
  }

  private static void harvest() {
    List<String> lines = allLines();
    if (harvested > lines.size()) {
      // The file was truncated under us (a `clean` mid-run). Start over rather than mis-slice.
      harvested = 0;
      lines = allLines();
    }
    for (String line : lines.subList(harvested, lines.size())) {
      edge(line).ifPresent(EDGES::add);
    }
    harvested = lines.size();
  }

  private static Optional<NetworkEdge> edge(String line) {
    String[] fields = line.strip().split(" ");
    if (fields.length != 3 || !fields[1].startsWith("/")) {
      return Optional.empty();
    }
    String peer = peer(fields[1]);
    if (peer == null) {
      return Optional.empty();
    }
    return Optional.of(
        NetworkEdge.http(
            StoryTarget.SERVICE,
            peer,
            Labels.scrub(fields[0] + " " + fields[1] + " -> " + fields[2])));
  }

  /** Which peer a path belongs to — the whole of how one stub draws as four. */
  private static String peer(String path) {
    if (path.startsWith("/projects/")) {
      return PROJECTS;
    }
    if (path.startsWith("/containers/")) {
      return CONTAINERS;
    }
    if (path.startsWith("/idp/")) {
      return IDP;
    }
    if (path.startsWith("/events/")) {
      return EVENTS;
    }
    return null;
  }

  private static List<String> allLines() {
    return lines(ACCESS_LOG);
  }

  // --- files ------------------------------------------------------------------------------------

  /**
   * A file's complete lines. A missing file is empty rather than a failure, and an <b>unterminated
   * tail is dropped</b>: the server appends while this reads, and half a line would shape half an
   * edge. The next read sees it whole.
   */
  private static List<String> lines(Path file) {
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    String text = readString(file);
    if (text == null) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    return List.of(text.substring(0, lastComplete).split("\n"));
  }

  private static String readString(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return null;
    }
  }

  private static synchronized void record(String method, String path, int status) {
    append(ACCESS_LOG, method + " " + path + " " + status + "\n");
  }

  private static void append(Path file, String content) {
    try {
      Files.createDirectories(ROOT);
      Files.writeString(
          file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // A recording that cannot be written costs the diagram an arrow; it must not cost the launched
      // process its answer.
    }
  }

  private static void write(Path file, String content) {
    try {
      Files.createDirectories(ROOT);
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + file, e);
    }
  }

  private static void wipe() {
    if (!Files.exists(ROOT)) {
      return;
    }
    try (var paths = Files.walk(ROOT)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not clear " + ROOT, e);
    }
  }
}
