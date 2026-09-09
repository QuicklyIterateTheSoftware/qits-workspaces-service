package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * Where the agent-configuration document comes from: qits-projects, which holds the store and
 * resolves every session surface a container may serve into one document.
 *
 * <p>One outbound HTTP collaborator, declared here and implemented in {@code service/…/wiring}
 * exactly as {@link RepositoryLookup} and {@link CredentialCommissioner} are — {@code domain} names
 * what it needs and a deployment decides where the answer comes from.
 *
 * <p><b>A snapshot, not a subscription.</b> It is read once, when a container is provisioned; the
 * document then lives on the workspace row and in the container's own environment for as long as
 * that container does. There is no poll and no push: the launch path inside the container is a pure
 * local render with no runtime dependency on this call, and an edit in the store reaches the next
 * container. That is the epic's decision, and it is why this port has one method and no lifecycle.
 *
 * <p><b>Absent is a supported configuration, in both spellings, and the two behave identically.</b>
 * With no implementation installed the {@code Instance} is unresolvable; with one installed against
 * a deployment that has no address for qits-projects, {@link #fetch} answers empty. Either way a
 * container is created carrying no document — which is what every workspace container did before
 * this port existed, and what the harness library's shipped defaults are for. Neither absence is
 * recorded on the workspace: nothing failed. It is the same distinction {@link
 * CredentialCommissioner} draws between "there is no issuer here" and "the issuer could not be
 * asked".
 *
 * <p><b>A wired implementation must throw rather than answer nothing when a call fails.</b> The
 * distinction is {@link RepositoryLookup}'s: "there is nowhere to ask" and "the place to ask did not
 * answer" are different facts, and only the second is worth recording on the row. It is also the
 * only failure this port has — there is no "no such document": a surface qits-projects has never
 * been told about reads as its shipped default there, so a 200 always carries a whole document.
 */
@FunctionalInterface
public interface AgentConfigurationSource {

  /**
   * The whole resolved document, as of now, or empty when this deployment has nowhere to ask (see
   * the class javadoc — that is a configuration, not a failure).
   *
   * @throws RuntimeException when qits-projects could not be asked, refused, or answered something
   *     that is not a document. The message is what lands on {@code
   *     Workspace.agentConfigurationError} and is read by whoever wonders why a container is running
   *     on shipped defaults, so it names the address and the reason rather than a stack frame.
   */
  Optional<AgentConfigurationDocument> fetch();
}
