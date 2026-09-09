package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.AgentCapabilitySink;
import eu.wohlben.qits.workspaces.control.FakeAgentCapabilitySink;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The classification, without a tunnel: what one {@code /agents/available} body is read as. {@code
 * DaemonStreamRouteTest} proves the same rules through a real tunnel and a real retry window; this
 * pins the arms that a fixture would have to misbehave to reach.
 *
 * <p>The distinction it exists for is <b>absent vs. broken vs. not yet</b>. All three are quiet
 * about a container that is merely starting, and exactly one of them — a daemon and a host
 * disagreeing about a contract — is loud.
 */
@QuarkusTest
class WorkspaceCapabilityRelayTest {

  private static final Long WORKSPACE = 42L;

  /** A report with one harness in it: the only body that may reach the catalogue. */
  private static final String REPORT =
      "{\"agents\":[\"CLAUDE\"],\"defaultAgent\":\"CLAUDE\",\"imageVersion\":\"2026.909.1\","
          + "\"capabilities\":[{\"harness\":\"CLAUDE\",\"harnessVersion\":\"2.1.226\"}]}";

  @Inject WorkspaceCapabilityRelay relay;

  @Inject FakeAgentCapabilitySink catalogue;

  @AfterEach
  void forgetTheCatalogue() {
    catalogue.reset();
  }

  @Test
  void aReportIsRecordedUnchanged() {
    assertEquals(
        WorkspaceCapabilityRelay.Outcome.Kind.RECORDED, relay.ingest(WORKSPACE, REPORT).kind());
    assertEquals(REPORT, catalogue.recorded().getFirst());
  }

  /**
   * <b>The rule this relay does not share with qits-projects' own.</b> The workspace daemon serves
   * this route from a volatile list that is empty until its boot probe lands, so an empty array is
   * "not yet" and must be asked again — treating it as an older daemon (which is what it means over
   * there, where the probe runs before the bind) would record nothing on nearly every container.
   */
  @Test
  void anEmptyCapabilityListIsNotYetRatherThanAbsent() {
    String notProbedYet =
        "{\"agents\":[\"CLAUDE\"],\"defaultAgent\":\"CLAUDE\",\"capabilities\":[]}";

    assertEquals(
        WorkspaceCapabilityRelay.Outcome.Kind.NOT_READY,
        relay.ingest(WORKSPACE, notProbedYet).kind());
    assertTrue(catalogue.recorded().isEmpty(), "nothing was reported, so nothing is written");
  }

  /**
   * <b>An empty body is not a malformed one, and conflating them cost qits-projects a release.</b>
   * The not-ready window presents as a successful hop carrying nothing — the tunnel connects before
   * the daemon has anything behind it — and handed to Jackson that reads as a contract violation,
   * which is terminal. It says nothing about capabilities and can never be the reason to stop
   * asking.
   */
  @Test
  void anEmptyBodyIsNotYetRatherThanBroken() {
    assertEquals(WorkspaceCapabilityRelay.Outcome.Kind.NOT_READY, relay.ingest(WORKSPACE, "").kind());
    assertEquals(
        WorkspaceCapabilityRelay.Outcome.Kind.NOT_READY, relay.ingest(WORKSPACE, "   ").kind());
    assertEquals(
        WorkspaceCapabilityRelay.Outcome.Kind.NOT_READY, relay.ingest(WORKSPACE, null).kind());
    assertTrue(catalogue.recorded().isEmpty());
  }

  /** A daemon that answers something that is not this contract at all. Loud, and terminal. */
  @Test
  void aBodyThatIsNotAReportIsBrokenAndNotRetried() {
    assertEquals(
        WorkspaceCapabilityRelay.Outcome.Kind.BROKEN, relay.ingest(WORKSPACE, "not json").kind());
    assertEquals(
        WorkspaceCapabilityRelay.Outcome.Kind.BROKEN,
        relay.ingest(WORKSPACE, "[\"an array\"]").kind());
  }

  /**
   * The door refusing — an unknown harness, or a credential it will not take. That is the two sides
   * disagreeing, so it is loud and terminal, exactly like a body that will not parse.
   */
  @Test
  void anIngestDoorThatRefusesIsBrokenAndNotRetried() {
    catalogue.answering(AgentCapabilitySink.Result.REFUSED);

    assertEquals(
        WorkspaceCapabilityRelay.Outcome.Kind.BROKEN, relay.ingest(WORKSPACE, REPORT).kind());
  }

  /** qits-projects being down is neither: the report is still true, so it is asked again. */
  @Test
  void aCatalogueThatCannotBeReachedIsAskedAgain() {
    catalogue.answering(AgentCapabilitySink.Result.UNREACHABLE);

    assertEquals(
        WorkspaceCapabilityRelay.Outcome.Kind.NOT_READY, relay.ingest(WORKSPACE, REPORT).kind());
  }

  /** Nowhere configured to write is a deployment's choice, not a failure: terminal, and quiet. */
  @Test
  void noPeerAddressIsAbsentAndQuiet() {
    catalogue.answering(AgentCapabilitySink.Result.NOT_CONFIGURED);

    assertEquals(
        WorkspaceCapabilityRelay.Outcome.Kind.ABSENT, relay.ingest(WORKSPACE, REPORT).kind());
  }
}
