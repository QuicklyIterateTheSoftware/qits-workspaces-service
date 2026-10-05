package eu.wohlben.qits.workspaces.wiring;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stub qits-idp on an ephemeral loopback port, for the commission tests: the JDK's {@code
 * HttpServer}, as {@code IdpCredentialCommissionerTest} uses it, with answers per {@code "METHOD
 * /path"} and every request recorded. A real server and a real generated client, so what is pinned
 * is the wire and the exception types the client actually throws.
 *
 * <p>A route answers its list of answers in order and repeats the last one; a request no route
 * names answers 404.
 */
public final class IdpStub implements AutoCloseable {

  public record Answer(int status, String body) {}

  /** One request as it arrived. */
  public record Request(String method, String path, String authorization, String body) {
    public String line() {
      return method + " " + path;
    }
  }

  private final HttpServer server;
  private final Map<String, List<Answer>> routes = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> served = new ConcurrentHashMap<>();
  private final List<Request> requests = new CopyOnWriteArrayList<>();

  public IdpStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String line = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
          String authorization = exchange.getRequestHeaders().getFirst("Authorization");
          requests.add(
              new Request(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  authorization == null ? "" : authorization,
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          List<Answer> answers = routes.getOrDefault(line, List.of(new Answer(404, null)));
          int index = served.computeIfAbsent(line, l -> new AtomicInteger()).getAndIncrement();
          Answer answer = answers.get(Math.min(index, answers.size() - 1));
          byte[] out =
              answer.body() == null ? new byte[0] : answer.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(answer.status(), out.length == 0 ? -1 : out.length);
          try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(out);
          }
        });
    server.start();
  }

  /** Answer {@code "METHOD /path"} with these, in order. */
  public IdpStub on(String line, Answer... answers) {
    routes.put(line, List.of(answers));
    return this;
  }

  public List<Request> requests() {
    return List.copyOf(requests);
  }

  /** Every request line, in order. */
  public List<String> lines() {
    return requests.stream().map(Request::line).toList();
  }

  /** The request lines of one method, in order. */
  public List<String> lines(String method) {
    return requests.stream().filter(r -> r.method().equals(method)).map(Request::line).toList();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public <T> T client(Class<T> type) {
    return QuarkusRestClientBuilder.newBuilder().baseUri(URI.create(baseUrl())).build(type);
  }

  /** {@link IdpCredentialCommissioner} wired against this stub, its patience a second. */
  public IdpCredentialCommissioner credentialCommissioner() {
    IdpCredentialCommissioner commissioner = new IdpCredentialCommissioner();
    commissioner.enabled = true;
    commissioner.clientId = Optional.of("dev-qits-workspaces");
    commissioner.clientSecret = Optional.of("service-secret");
    commissioner.patience = Duration.ofSeconds(1);
    commissioner.clients = client(IdpClients.class);
    return commissioner;
  }

  /** {@link IdpRunnerCommissioner} wired against this stub, its patience a second. */
  public IdpRunnerCommissioner runnerCommissioner() {
    IdpRunnerCommissioner commissioner = new IdpRunnerCommissioner();
    commissioner.enabled = true;
    commissioner.clientId = Optional.of("dev-qits-workspaces");
    commissioner.clientSecret = Optional.of("service-secret");
    commissioner.patience = Duration.ofSeconds(1);
    commissioner.clients = client(IdpClients.class);
    commissioner.tokens = client(IdpTokens.class);
    return commissioner;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
