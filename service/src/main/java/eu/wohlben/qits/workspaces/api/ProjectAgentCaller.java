package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.workspaces.error.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a project agent is (qits-2596, epic qits-2520 decision 6): the long-running agent
 * qits-agents-service runs for a project, known only by its bearer. A caller is one when it holds
 * {@code qits:agent}, its token's {@code context_kind} claim is {@value #PROJECT_AGENT} (qits-idp's
 * {@code TokenService} stamps it on every commissioned token of that kind) and its {@code project}
 * claim is a project uuid. Its holder key is its principal name — e.g. {@code
 * tok-project-agent-qits-developer} — and that project. No path parameter names the agent, so
 * nobody declares a wait for another.
 *
 * <p>The one place the test is made: a workspace agent ({@code context_kind} {@code agent}), a
 * desk, a person or the platform is none, whatever else it holds.
 *
 * @param subject the principal name, the holder
 * @param projectId the token's project
 */
public record ProjectAgentCaller(String subject, UUID projectId) {

  /** The role a project agent's token holds. */
  static final String AGENT_ROLE = "qits:agent";

  /** The claim that names the kind of a commissioned token. */
  static final String CONTEXT_KIND = "context_kind";

  /** A project agent's {@value #CONTEXT_KIND}. */
  static final String PROJECT_AGENT = "project-agent";

  /** The 403 everybody else gets. */
  static final String ONLY_PROJECT_AGENTS = "only a project's long-running agent holds these waits";

  /** The caller as a project agent, if it is one. */
  static Optional<ProjectAgentCaller> of(SecurityIdentity identity) {
    if (identity == null
        || identity.isAnonymous()
        || !identity.hasRole(AGENT_ROLE)
        || identity.getPrincipal() == null) {
      return Optional.empty();
    }
    String subject = identity.getPrincipal().getName();
    if (subject == null || subject.isBlank()) {
      return Optional.empty();
    }
    if (!PROJECT_AGENT.equals(MachineIdentity.claim(identity, CONTEXT_KIND).orElse(null))) {
      return Optional.empty();
    }
    Optional<String> project = MachineIdentity.claim(identity, QitsClaims.PROJECT);
    if (project.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(new ProjectAgentCaller(subject, UUID.fromString(project.get().trim())));
    } catch (IllegalArgumentException notAProject) {
      return Optional.empty();
    }
  }

  /**
   * The caller as a project agent.
   *
   * @throws ForbiddenException {@value #ONLY_PROJECT_AGENTS}
   */
  static ProjectAgentCaller require(SecurityIdentity identity) {
    return of(identity).orElseThrow(() -> new ForbiddenException(ONLY_PROJECT_AGENTS));
  }
}
