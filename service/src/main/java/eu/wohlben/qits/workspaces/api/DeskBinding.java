package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.workspaces.control.AgentService;
import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import eu.wohlben.qits.workspaces.error.ForbiddenException;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The delivery desk's binding to its own project (qits-1493, widened by qits-1596).
 *
 * <p>The desk's token holds {@code qits:agent} + {@code qits:desk}, a {@code project} claim and no
 * Git refs. It is admitted on the owner's coordinator's writes ({@code qits:admin-agent}), but
 * <b>bound</b>: where the resource belongs to a project — a repository, a workspace by its row's
 * repository, an agent by its repository, a work item by its ACTIVE agent — that project must be
 * the one its token's {@code project} claim names, or the call is 403 and nothing happens.
 *
 * <p>The binding applies to a caller that holds {@code qits:desk} and none of {@link
 * #UNBOUND_ROLES}. {@code qits:desk} is never one of those: holding it never widens anything.
 *
 * <p><b>It fails closed.</b> A workspace or agent whose project cannot be resolved (the editor
 * workspace belongs to no repository; a registry that does not answer) is 403 for the desk. A
 * resource that does not exist at all is left to the door's own 404, so the desk learns nothing
 * about other projects it would not learn there.
 *
 * <p>The actor of a desk write is whoever this service already records for every caller: the
 * identity's principal, which for a desk bearer is its token subject.
 */
@ApplicationScoped
public class DeskBinding {

  /** The delivery desk's role. */
  public static final String DESK = "qits:desk";

  /** Roles never bound to a project, whatever else the caller holds; {@link #DESK} is not one. */
  static final List<String> UNBOUND_ROLES = List.of("qits:admin", "qits:admin-agent", "qits:system");

  static final String OWN_PROJECT_ONLY = "The delivery desk may act only within its own project.";

  /** The 403 every bound door documents in the OpenAPI document. */
  public static final String WORKSPACE_403 =
      "A `qits:desk` caller whose token's `project` claim is not the workspace's project (a"
          + " workspace of no resolvable project counts as another's). Nothing was changed.";

  /** As {@link #WORKSPACE_403}, for a door addressed by an agent. */
  public static final String AGENT_403 =
      "A `qits:desk` caller whose token's `project` claim is not the agent's project. Nothing was"
          + " changed.";

  @Inject SecurityIdentity identity;

  @Inject RepositoryLookup repositories;

  @Inject WorkspaceRepository workspaces;

  @Inject AgentWorktreeRepository agentRows;

  @Inject AgentService agents;

  /** Whether the caller is a desk bound to its project: {@code qits:desk} and no unbound role. */
  public boolean isBoundDesk() {
    return identity.hasRole(DESK) && UNBOUND_ROLES.stream().noneMatch(identity::hasRole);
  }

  /** A door, or a variant of one, that the desk may never use; a no-op for everyone else. */
  public void refuse(String why) {
    if (isBoundDesk()) {
      throw new ForbiddenException(why);
    }
  }

  /**
   * The repository's project must be the desk's. An unknown repository is left to the door's 404;
   * a registry that does not answer is 403.
   */
  public void requireRepository(String repositoryId) {
    if (!isBoundDesk()) {
      return;
    }
    if (repositoryId == null || repositoryId.isBlank()) {
      return; // the door's own validation answers a blank id
    }
    Optional<RepositoryLookup.RepositoryView> repository;
    try {
      repository = repositories.find(repositoryId);
    } catch (RuntimeException registryDidNotAnswer) {
      throw new ForbiddenException(OWN_PROJECT_ONLY);
    }
    if (repository.isEmpty()) {
      return;
    }
    requireProject(repository.get().projectId(), OWN_PROJECT_ONLY);
  }

  /** The workspace row's (any status) repository's project must be the desk's. */
  public void requireWorkspace(Long id) {
    requireWorkspace(id, null);
  }

  /**
   * As {@link #requireWorkspace(Long)}, and additionally refuses the desk {@code branchRefusal}
   * when the row stands on a branch — a door that deletes that branch on the git host is a push the
   * desk may not make. A slot workspace (qits-1152) has no branch.
   */
  public void requireWorkspace(Long id, String branchRefusal) {
    if (!isBoundDesk() || id == null) {
      return;
    }
    Optional<WorkspaceFacts> row =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    Optional.ofNullable(workspaces.findById(id))
                        .map(w -> new WorkspaceFacts(w.repositoryId, w.branch)));
    if (row.isEmpty()) {
      return;
    }
    requireProject(projectOf(row.get().repositoryId()), OWN_PROJECT_ONLY);
    if (branchRefusal != null && row.get().branch() != null && !row.get().branch().isBlank()) {
      throw new ForbiddenException(branchRefusal);
    }
  }

  /** The agent's repository's project must be the desk's; an id that names no agent is left alone. */
  public void requireAgent(String id) {
    if (!isBoundDesk() || id == null) {
      return;
    }
    UUID agentId;
    try {
      agentId = UUID.fromString(id);
    } catch (IllegalArgumentException notAnId) {
      return;
    }
    Optional<String> repositoryId =
        QuarkusTransaction.requiringNew()
            .call(() -> Optional.ofNullable(agentRows.findById(agentId)).map(a -> a.repositoryId));
    if (repositoryId.isEmpty()) {
      return;
    }
    requireProject(projectOf(repositoryId.get()), OWN_PROJECT_ONLY);
  }

  /**
   * The work item's ACTIVE agent's project must be the desk's. An item with no ACTIVE agent is not
   * a violation — the machine doors answer it with {@code agentId: null}, as they do for everyone.
   */
  public void requireWorkItem(String workId, String message) {
    if (!isBoundDesk()) {
      return;
    }
    Optional<String> agentProject = agents.projectOfActiveAgent(workId);
    if (agentProject.isEmpty()) {
      return;
    }
    requireProject(agentProject.get(), message);
  }

  private void requireProject(String projectId, String message) {
    if (projectId == null
        || projectId.isBlank()
        || !MachineIdentity.claimMatches(identity, QitsClaims.PROJECT, projectId)) {
      throw new ForbiddenException(message);
    }
  }

  private String projectOf(String repositoryId) {
    if (repositoryId == null || repositoryId.isBlank()) {
      return null;
    }
    try {
      return repositories.find(repositoryId).map(RepositoryLookup.RepositoryView::projectId).orElse(null);
    } catch (RuntimeException registryDidNotAnswer) {
      return null;
    }
  }

  private record WorkspaceFacts(String repositoryId, String branch) {}
}
