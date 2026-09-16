package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.CommandExit;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceImage;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * <b>THE PIN TEST.</b> The workspace daemon at exactly the version this reactor pins is downloaded,
 * started as a process against localhost, and made to run a command over the real control-socket
 * protocol — before any workspace is started from the image that daemon ships in.
 *
 * <h2>What it is for</h2>
 *
 * <p>qits-workspaces used to learn which workspace image to start from {@code
 * env.QITS_WORKSPACE_IMAGE_VERSION}, a qits-configuration entry a release listener rewrote the
 * moment qits-workspace-daemon pushed an image. So a new daemon reached the next real workspace with
 * nothing having tested the pair, and a daemon that changed the wire would be discovered by a user
 * whose workspace would not come up. The version is a pinned dependency now
 * ({@link WorkspaceImage#VERSION}, off {@code eu.wohlben.qits:qits-workspace-daemon-protocol}), and
 * this is the test that makes the pin mean something: a bump that breaks the protocol fails
 * <em>this repository's</em> release request, which is a red gate on a branch, rather than a live
 * run.
 *
 * <h2>A process, not a container — and a jar, not the native binary</h2>
 *
 * <p>What this service talks to is the daemon inside the image, not the image, so a container buys
 * nothing here and costs everything: a CI step container runs {@code --cap-drop=ALL} with no
 * privilege and has no docker at all, rootless dind needs {@code --privileged}, and the suite has to
 * stay green from a clone with no docker. The image's own toolchain and entrypoint keep being tested
 * by the image's pipeline, which is where they belong.
 *
 * <p>What is downloaded is qits-workspace-daemon's {@code daemon} artifact, which is a <b>runnable
 * uber-jar</b>. That repository's Dockerfile carries the reasoning and it is worth repeating once
 * here, because it looks like a shortcut and is not: the native image is compiled on UBI9 against
 * <b>glibc</b>, and every CI step image on this platform is Alpine — {@code musl} — so a native bare
 * binary is an artifact this gate could never execute. It would skip for ever, which is worse than
 * having no pin test. Same source, same reactor, same version: the wire contract exercised here is
 * exactly the one the image's daemon speaks, and the protocol is what a daemon bump breaks. The
 * native image's own linkage and reflection registration are not covered, and are not this
 * repository's to cover.
 *
 * <h2>Not a {@code @QuarkusIntegrationTest}, deliberately</h2>
 *
 * <p>The ticket asked for one. Nothing in the assertion needs the application: the subject is
 * whether the pinned daemon and this reactor's {@link DaemonCodec} still agree, and both ends of
 * that are on this classpath. A second launched artifact would mean a second {@code @TestProfile} —
 * a second whole qits-workspaces beside the story catalogue's, with its own boot, its own databases
 * and its own port — for no assertion the bare server cannot make. {@code DaemonControlSocketIT}
 * makes the same judgement in the same package and says so in the same words.
 *
 * <h2>It gates, and it skips only where it must</h2>
 *
 * <p>No {@code @Tag("extended")}: this one has to run. Where an origin IS configured, a missing
 * artifact or a failed round trip is a <b>failure</b> and never a skip — in a CI step the origin is
 * always injected, so this cannot quietly pass by not running. Name it in {@code
 * .config/qits/ci-event-release-request.yml}'s {@code -Dit.test} list or it never runs there at all
 * — silently, which is why AGENTS.md says to change both together.
 *
 * <p><b>The one skip is defensive rather than a supported mode.</b> It covers an artifacts origin
 * that is configured to nothing, and it is deliberately not load-bearing: this repository's
 * clone-alone rule already reads "a clone builds against the platform Maven repository" — the
 * reactor resolves qits-eventstream, qits-db-core and now both image pins from it — so a checkout
 * with no platform to ask fails at dependency resolution long before any test runs. The branch
 * exists so that a deployment which blanks the address gets a legible sentence instead of a
 * malformed URL, not so that this test can be opted out of.
 */
public class WorkspaceDaemonPinIT {

  /**
   * Where qits-artifacts is, derived from the Maven repository address the build already carries —
   * the same {@code ${…%%/artifacts/*}} arithmetic every release pipeline does, because the daemon
   * store is a sibling path of the maven one inside one deployment. Derived rather than given its
   * own key, so there is no second address to configure wrongly.
   */
  private static final String ARTIFACTS_BASE = artifactsBase();

  /** The workspace under test — a directory, because the daemon wants somewhere to be. */
  private static final String WORKSPACE_ID = "pin-it";

  private static String artifactsBase() {
    String maven =
        System.getProperty(
            "qits.maven.repository.url", System.getenv().getOrDefault("QITS_MAVEN_REPOSITORY_URL", ""));
    int marker = maven.indexOf("/artifacts/");
    return marker < 0 ? "" : maven.substring(0, marker) + "/artifacts";
  }

  @Test
  public void theDaemonThisReactorPinsStartsAndRoundTripsACommand() throws Exception {
    assumeTrue(
        !ARTIFACTS_BASE.isBlank(),
        "no artifacts origin is configured (qits.maven.repository.url / QITS_MAVEN_REPOSITORY_URL)"
            + " — a clone with no platform to ask cannot run the pin test");

    Path work = Files.createTempDirectory("qits-pin-it");
    Path jar = work.resolve("qits-workspace-daemon.jar");
    Path checkout = Files.createDirectories(work.resolve("workspace"));
    download(jar);

    Vertx vertx = Vertx.vertx();
    String correlationId = "pin-" + UUID.randomUUID();
    CompletableFuture<Hello> hello = new CompletableFuture<>();
    CompletableFuture<Integer> exit = new CompletableFuture<>();
    StringBuilder stdout = new StringBuilder();

    // A stand-in host: on HELLO, ask for an echo and collect the reply. The real
    // WorkspaceDaemonRegistry is covered by DaemonControlSocketTest; what is under test here is the
    // far end, so the near end is deliberately the smallest thing that speaks the protocol.
    HttpServer server = vertx.createHttpServer();
    server.webSocketHandler(
        ws ->
            ws.textMessageHandler(
                text -> {
                  DaemonMessage message = DaemonCodec.decode(new JsonObject(text).getMap());
                  switch (message) {
                    case Hello said -> {
                      hello.complete(said);
                      ws.writeTextMessage(
                          encode(
                              new RunCommand(
                                  correlationId,
                                  List.of("echo", "workspace-daemon-pin-ok"),
                                  checkout.toString(),
                                  Map.of())));
                    }
                    case CommandChunk chunk -> {
                      if (chunk.stream() == Stream.STDOUT) {
                        stdout.append(chunk.text());
                      }
                    }
                    case CommandExit ended -> exit.complete(ended.exitCode());
                    default -> {
                      /* Heartbeat / DaemonLog — nothing for the stand-in to do */
                    }
                  }
                }));
    int port =
        server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture()
            .get(10, TimeUnit.SECONDS)
            .actualPort();

    Path log = work.resolve("daemon.log");
    Process daemon = null;
    try {
      daemon = start(jar, checkout, port, log);

      Hello said = awaitOrReport(hello, log, "the daemon never dialled home");
      assertEquals(WORKSPACE_ID, said.workspaceId(), "the daemon dialled home as who it was told");
      // THE VERSION IS THE ASSERTION, not decoration: it is what makes this "the pinned daemon" and
      // not "a daemon". If this ever disagrees, something downloaded a different build than the pom
      // names and every other assertion below is about the wrong thing.
      assertEquals(
          WorkspaceImage.VERSION,
          said.daemonVersion(),
          "the daemon that answered is not the version this reactor pins");
      // The capability the host branches on. A daemon below TUNNEL_CAPABILITY_VERSION binds its API
      // to 0.0.0.0 and cannot serve an OpenStream at all, which is a live-topology failure no unit
      // test here can see — WorkspaceTunnels reads exactly this number to choose the tunnel or a
      // direct address.
      assertTrue(
          said.capabilityVersion() >= DaemonProtocol.TUNNEL_CAPABILITY_VERSION,
          "the pinned daemon announces capability "
              + said.capabilityVersion()
              + ", below the reverse tunnel's "
              + DaemonProtocol.TUNNEL_CAPABILITY_VERSION);

      assertEquals(
          0,
          awaitOrReport(exit, log, "the daemon never finished the command").intValue(),
          "the echo the host asked for");
      assertTrue(
          stdout.toString().contains("workspace-daemon-pin-ok"),
          "the command's output came back over the socket: " + stdout);
    } finally {
      // Decommission, in the order the ticket asks for and a stuck process demands: the child first,
      // so nothing is still dialling a server that is closing.
      if (daemon != null) {
        daemon.destroy();
        if (!daemon.waitFor(15, TimeUnit.SECONDS)) {
          daemon.destroyForcibly();
        }
      }
      server.close();
      vertx.close();
      deleteTree(work);
    }
  }

  /**
   * Waits for one half of the round trip, and <b>fails with the daemon's own log</b> rather than
   * with a bare timeout.
   *
   * <p>The whole point of this test is that a daemon a release published does not work with this
   * service, and the far end's startup output is the only thing that says which of the many reasons
   * it is — a missing config key, a port it could not bind, a protocol frame it could not decode. A
   * {@code TimeoutException} on its own sends the reader to a log that a deliberately non-inherited
   * stream means they cannot find.
   */
  private static <T> T awaitOrReport(CompletableFuture<T> future, Path log, String what)
      throws Exception {
    try {
      return future.get(60, TimeUnit.SECONDS);
    } catch (Exception e) {
      String output;
      try {
        output = Files.exists(log) ? Files.readString(log) : "(the daemon wrote nothing)";
      } catch (IOException unreadable) {
        output = "(the daemon's log could not be read: " + unreadable + ")";
      }
      throw new AssertionError(
          what
              + " within 60s — "
              + WorkspaceImage.DAEMON_NAME
              + " "
              + WorkspaceImage.VERSION
              + ", the version this reactor pins. Its output was:\n"
              + output,
          e);
    }
  }

  /**
   * Fetches the pinned daemon out of qits-artifacts' {@code daemons} store.
   *
   * <p>A missing artifact is a <b>failure with a sentence</b>, never a skip. It means the version
   * this reactor pins was released without its daemon — or that retention removed it — and either is
   * the exact class of defect this test exists to surface, one release earlier than a workspace that
   * will not start.
   */
  private static void download(Path target) throws Exception {
    String url = ARTIFACTS_BASE + "/daemons/" + WorkspaceImage.DAEMON_NAME + "/" + WorkspaceImage.VERSION;
    try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
      HttpResponse<InputStream> answer =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build(),
              HttpResponse.BodyHandlers.ofInputStream());
      if (answer.statusCode() != 200) {
        fail(
            "the pinned daemon "
                + WorkspaceImage.DAEMON_NAME
                + " "
                + WorkspaceImage.VERSION
                + " is not in qits-artifacts ("
                + answer.statusCode()
                + " from "
                + url
                + "). The pom pins a version whose daemon was never published or no longer exists.");
      }
      try (InputStream body = answer.body()) {
        Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  /**
   * Starts the daemon pointed at the stand-in host.
   *
   * <p><b>Every listening port it binds is given to it, and every one is free.</b> The daemon's
   * three shipped ports are fixed numbers (13337 hooks, 13338 api, 13339 editor) because inside a
   * container they are unshared; this test runs it as an ordinary process on a developer's machine
   * and on a CI host, where 13337 is quite likely to be another workspace's daemon — and a bind
   * failure there is logged rather than fatal, so the symptom would be an unrelated test flaking
   * much later. Asked for and handed over explicitly, so this process owns what it binds.
   */
  private static Process start(Path jar, Path checkout, int hostPort, Path log) throws IOException {
    ProcessBuilder builder =
        new ProcessBuilder(
            javaBinary(),
            "-Dqits.workspace-daemon.hooks-port=" + freePort(),
            "-Dqits.workspace-daemon.api-port=" + freePort(),
            "-Dqits.workspace-daemon.editor-port=" + freePort(),
            "-jar",
            jar.toString());
    Map<String, String> env = builder.environment();
    env.put(
        "QITS_WORKSPACE_DAEMON_URL",
        "ws://127.0.0.1:" + hostPort + "/workspaces/daemon/" + WORKSPACE_ID);
    env.put("QITS_WORKSPACE_DAEMON_WORKSPACE_ID", WORKSPACE_ID);
    env.put("QITS_WORKSPACE_DAEMON_WORKSPACE_DIR", checkout.toString());
    // TO A FILE, AND NOT inheritIO(). A pipe nobody drains would fill and wedge the child, so the
    // output has to go somewhere — and `inheritIO` sends it to this forked JVM's native stdout,
    // which is failsafe's own control channel: surefire answers that with "Corrupted channel by
    // directly writing to native stream in forked JVM", measured here on the first green run. It
    // did not fail the run and it is the shape of thing that fails one later. The file is printed
    // by the caller when anything goes wrong, so a daemon that dies at startup still says why.
    return builder
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
        .start();
  }

  private static String javaBinary() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  /**
   * A port nothing is listening on — asked of the OS and released immediately.
   *
   * <p>Inherently racy and correct enough: the window is microseconds, the alternative is three
   * hard-coded numbers that are wrong on any host running a workspace, and the daemon treats a
   * failed bind on these three as non-fatal anyway.
   */
  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static String encode(DaemonMessage message) {
    return new JsonObject(DaemonCodec.encode(message)).encode();
  }

  private static void deleteTree(Path root) {
    try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException e) {
      // A leftover temp directory is not worth failing a green run over.
    }
  }
}
