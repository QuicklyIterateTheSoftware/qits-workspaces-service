package eu.wohlben.qits.workspaces.control;

/**
 * Where a container's harness capability report is written: qits-projects' capability catalogue.
 *
 * <p><b>The mirror image of {@link AgentConfigurationSource}.</b> That port reads the document a
 * container is born with; this one writes back what the harnesses inside it turned out to be able to
 * do. Both name the same peer and both are implemented on the same {@code projects} oidc client —
 * they are the two halves of one conversation, made at two different moments of a container's life.
 *
 * <p><b>The body is the daemon's answer, unchanged.</b> {@code GET /agents/available} and {@code PUT
 * /projects/api/agent-capabilities} are deliberately the same shape: the ingest door was built as a
 * relay's door, and its javadoc in qits-projects forbids a carrier that reshapes, because a relay
 * with an opinion is a third place the contract can drift. So this port takes a {@code String} for
 * {@link AgentConfigurationSource}'s reason read backwards — the bytes belong to qits-projects and
 * to the harness library, and a record here would be this repository holding a copy of a contract it
 * does not own.
 *
 * <p><b>Nothing here throws and nothing here is waited on.</b> The caller is a background relay
 * reacting to a container start; the worst outcome of every branch is that the editor's dropdowns
 * keep the values they had. What the caller does need is to tell a peer that <em>refused</em> from a
 * peer that could not be <em>asked</em> — one is terminal and one is worth asking again — which is
 * the whole of why this answers a {@link Result} rather than {@code void}.
 */
public interface AgentCapabilitySink {

  /**
   * Hand one container's {@code /agents/available} body to the catalogue.
   *
   * @param reportBody the daemon's answer, byte for byte
   */
  Ingest ingest(String reportBody);

  /** What the catalogue did with it, and enough detail to name in a log line. */
  record Ingest(Result result, String detail) {

    public static Ingest recorded(String detail) {
      return new Ingest(Result.RECORDED, detail);
    }

    public static Ingest refused(String detail) {
      return new Ingest(Result.REFUSED, detail);
    }

    public static Ingest unreachable(String detail) {
      return new Ingest(Result.UNREACHABLE, detail);
    }

    public static Ingest notConfigured(String detail) {
      return new Ingest(Result.NOT_CONFIGURED, detail);
    }
  }

  /** Three terminal answers and one worth retrying; the relay maps each onto its own vocabulary. */
  enum Result {
    /** The catalogue took it. */
    RECORDED,
    /** The door said no — an unknown harness, a body it will not read, a credential it refuses.
     * Terminal, and loud: that is two sides disagreeing about a contract. */
    REFUSED,
    /** qits-projects could not be asked, or answered a 5xx. Worth asking again. */
    UNREACHABLE,
    /** No peer address is configured, so there is nowhere to write. Terminal, and quiet. */
    NOT_CONFIGURED
  }
}
