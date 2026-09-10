package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for {@link AgentCapabilitySink}: qits-projects' capability catalogue, in memory.
 *
 * <p>It records what it was handed, byte for byte, because "the daemon's answer passed through
 * unchanged" is the port's whole contract and the only way to assert it is to keep the bytes.
 *
 * <p>Unlike {@link FakeAgentConfigurationSource} it starts <b>wired and recording</b>, and it can
 * afford to: nothing writes to this sink except {@code WorkspaceCapabilityRelay}, which is dark
 * under test ({@code %test.qits.workspace.agent-capabilities.relay-enabled=false}) and driven
 * explicitly by the one suite that is about it. So an eager double changes no other test, while
 * keeping {@code wiring/HttpAgentCapabilitySink} — a {@link io.quarkus.arc.DefaultBean} — out of the
 * suite's bean graph and away from the unreachable {@code qits.projects.url} the test properties
 * carry.
 */
@ApplicationScoped
public class FakeAgentCapabilitySink implements AgentCapabilitySink {

  private final List<String> recorded = new CopyOnWriteArrayList<>();

  private volatile Result answer = Result.RECORDED;

  /** Every body the relay handed over, in order and unchanged. */
  public List<String> recorded() {
    return List.copyOf(recorded);
  }

  /** Answer this instead of {@link Result#RECORDED} — a door that refuses, or a peer that is down. */
  public void answering(Result result) {
    this.answer = result;
  }

  public void reset() {
    recorded.clear();
    answer = Result.RECORDED;
  }

  @Override
  public Ingest ingest(String reportBody) {
    recorded.add(reportBody);
    return new Ingest(answer, "the fake catalogue answered " + answer);
  }
}
