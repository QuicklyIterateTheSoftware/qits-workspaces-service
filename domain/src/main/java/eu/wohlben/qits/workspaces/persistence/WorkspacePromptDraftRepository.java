package eu.wohlben.qits.workspaces.persistence;

import eu.wohlben.qits.workspaces.entity.WorkspacePromptDraft;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class WorkspacePromptDraftRepository implements PanacheRepository<WorkspacePromptDraft> {

  /** The draft for a workspace, keyed by the workspace's {@code id} (the draft's shared PK/FK). */
  public Optional<WorkspacePromptDraft> findByAgentId(java.util.UUID agentId) {
    return find("agentId", agentId).firstResultOptional();
  }

  /**
   * Atomically inserts or updates a workspace's draft in one statement, keyed on the shared PK.
   * Because the draft's primary key <em>is</em> the workspace id, a non-atomic read-then-insert
   * would let two concurrent first-saves collide on that PK; the DB-native upsert serializes them
   * under the row lock (last write wins) instead of 500ing the loser. {@code updated_at} is assigned
   * by the DB clock ({@code CURRENT_TIMESTAMP}) so its value matches what a subsequent read returns
   * — the {@code @UpdateTimestamp} on the entity governs only the (now unused) Hibernate persist
   * path.
   *
   * <p>It was H2's {@code merge into … key (…)} until the store moved to postgres, and {@code insert
   * … on conflict … do update} is the same statement in the dialect that has it. Nothing about the
   * atomicity argument above changed with the spelling.
   */
  public void upsert(java.util.UUID agentId, String content, String serializedPrompt) {
    // The update arm lists only content/serialized_prompt/updated_at, so the other columns
    // (prompt_version, last_run_*) are left untouched; on insert they take their DDL defaults
    // (prompt_version 0, last_run_* null).
    getEntityManager()
        .createNativeQuery(
            "insert into agent_prompt_draft"
                + " (agent_id, content, serialized_prompt, updated_at)"
                + " values (?1, ?2, ?3, current_timestamp)"
                + " on conflict (agent_id) do update set"
                + " content = excluded.content,"
                + " serialized_prompt = excluded.serialized_prompt,"
                + " updated_at = excluded.updated_at")
        .setParameter(1, agentId)
        .setParameter(2, content)
        .setParameter(3, serializedPrompt)
        .executeUpdate();
    // Bump the version in the same transaction — a PUT means the composition changed (the frontend
    // only saves when dirty), so every upsert names a new draft state. Kept a separate statement to
    // avoid a self-referencing subquery inside the upsert.
    getEntityManager()
        .createNativeQuery(
            "update agent_prompt_draft set prompt_version = prompt_version + 1"
                + " where agent_id = ?1")
        .setParameter(1, agentId)
        .executeUpdate();
  }

  /**
   * Records that the draft's current version was handed to an agent run: stamps {@code
   * last_run_at}, copies the live {@code prompt_version} into {@code last_run_prompt_version}, and
   * records the launched command id. Idempotent no-op when the workspace has no draft row.
   */
  public void recordRun(java.util.UUID agentId, String commandId) {
    getEntityManager()
        .createNativeQuery(
            "update agent_prompt_draft set last_run_at = current_timestamp,"
                + " last_run_prompt_version = prompt_version, last_run_command_id = ?2"
                + " where agent_id = ?1")
        .setParameter(1, agentId)
        .setParameter(2, commandId)
        .executeUpdate();
  }

  /** Removes a workspace's draft if present — idempotent (no-op when absent). */
  public void deleteByAgentId(java.util.UUID agentId) {
    delete("agentId", agentId);
  }
}
