package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for {@link AgentConfigurationSource} that answers a document in memory and records
 * every call.
 *
 * <p><b>Duplicated from {@code domain}'s copy</b>, like every other {@code Fake*} here: the two
 * modules do not share a test classpath.
 *
 * <p><b>It starts UNWIRED</b>, for {@link FakeCredentialCommissioner}'s reason: a bean in {@code
 * src/test} is a bean for every {@code @QuarkusTest} in the module, so a double that answered by
 * default would put a document into every container the suite launches and quietly change what
 * dozens of unrelated tests are about. Unwired it behaves as no implementation does — nothing
 * fetched, nothing recorded on the row, no environment — which is also the shipped posture on a
 * deployment with no source wired.
 *
 * <p>It also keeps {@code wiring/HttpAgentConfigurationSource} out of the suite's bean graph: that
 * one is a {@link io.quarkus.arc.DefaultBean} and yields to this, so no test provision dials the
 * unreachable {@code qits.projects.url} the test properties carry.
 */
@ApplicationScoped
public class FakeAgentConfigurationSource implements AgentConfigurationSource {

  /** A minimal document of the shape qits-projects answers — enough to be one, and no more. */
  public static final String DOCUMENT =
      """
      {"version":1,"generatedAt":"2026-09-09T00:00:00Z","surfaces":[\
      {"surface":"epic.chat","harness":"CLAUDE"},\
      {"surface":"epic.agent","harness":"CLAUDE"},\
      {"surface":"workspace.chat","harness":"CLAUDE"},\
      {"surface":"workspace.agent","harness":"CLAUDE"},\
      {"surface":"ticket.dispatch","harness":"CLAUDE"}]}""";

  private volatile boolean wired;
  private volatile RuntimeException failure;
  private volatile String body = DOCUMENT;
  private final AtomicInteger fetches = new AtomicInteger();
  private final List<String> answered = new CopyOnWriteArrayList<>();

  /** Behave as a deployment with qits-projects reachable and answering. */
  public void wire() {
    wired = true;
  }

  /** Back to the unwired posture, and forget everything. */
  public void reset() {
    wired = false;
    failure = null;
    body = DOCUMENT;
    fetches.set(0);
    answered.clear();
  }

  /** Answer this body instead of {@link #DOCUMENT} — including a body that is not a document. */
  public void answering(String document) {
    this.body = document;
  }

  /** Fail every fetch the way an unreachable qits-projects does. */
  public void failWith(String why) {
    this.failure = new IllegalStateException(why);
  }

  /** How many times a provision asked. */
  public int fetches() {
    return fetches.get();
  }

  /** Every document handed out, in order. */
  public List<String> answered() {
    return List.copyOf(answered);
  }

  @Override
  public Optional<AgentConfigurationDocument> fetch() {
    fetches.incrementAndGet();
    if (!wired) {
      // Empty, not a throw: unwired stands in for a deployment with nowhere to ask, which the port
      // says behaves exactly as no implementation at all — nothing fetched, nothing recorded on the
      // row, no environment. A throw would put a failure on every container the module's suite
      // launches, which is the noise this double exists to avoid.
      return Optional.empty();
    }
    if (failure != null) {
      throw failure;
    }
    AgentConfigurationDocument document = AgentConfigurationDocument.of(body);
    answered.add(document.json());
    return Optional.of(document);
  }
}
