package eu.wohlben.qits.workspaces.persistence;

import eu.wohlben.qits.workspaces.entity.WorkspacePromptAttachment;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class WorkspacePromptAttachmentRepository
    implements PanacheRepository<WorkspacePromptAttachment> {

  /** A workspace's attachments, oldest first (the order they were attached). */
  public List<WorkspacePromptAttachment> listByAgentId(java.util.UUID agentId) {
    return list("agentId = ?1 order by createdAt", agentId);
  }

  /** One attachment, only if it belongs to the given workspace (guards cross-workspace access). */
  public Optional<WorkspacePromptAttachment> findByAgentIdAndId(java.util.UUID agentId, String id) {
    return find("agentId = ?1 and id = ?2", agentId, id).firstResultOptional();
  }

  /** Removes one attachment scoped to its workspace — returns true if a row was deleted. */
  public boolean deleteByAgentIdAndId(java.util.UUID agentId, String id) {
    return delete("agentId = ?1 and id = ?2", agentId, id) > 0;
  }

  /** Removes all of a workspace's attachments — idempotent (no-op when none exist). */
  public void deleteByAgentId(java.util.UUID agentId) {
    delete("agentId", agentId);
  }
}
