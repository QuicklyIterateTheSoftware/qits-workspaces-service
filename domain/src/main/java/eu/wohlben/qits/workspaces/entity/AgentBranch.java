package eu.wohlben.qits.workspaces.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * One branch an agent pushed, in one repository (qits-1152, D22) — what the release at VERIFIED
 * names, and what a released branch's resolution finds its agents by.
 *
 * <p>{@code @Uncaused} by decision: it is written only by the native upsert in {@code
 * AgentBranchRepository}, on the daemon registry's thread when an {@code AgentBranchPushed} frame
 * arrives, where no causation scope stands and no {@code @PrePersist} runs. A push is the agent's
 * own act, not the effect of an event this service could name.
 */
@Entity
@Table(name = "agent_branch")
@Uncaused
public class AgentBranch extends PanacheEntityBase {

  @EmbeddedId public Key key;

  @Column(length = 64)
  public String sha;

  @Column(name = "pushed_at", nullable = false)
  public Instant pushedAt;

  /** The primary key: the agent, the repository id and the branch. */
  @Embeddable
  public static class Key implements Serializable {

    @Column(name = "agent_id", nullable = false)
    public UUID agentId;

    @Column(name = "repository_id", nullable = false)
    public String repositoryId;

    @Column(name = "branch", nullable = false, columnDefinition = "text")
    public String branch;

    public Key() {}

    public Key(UUID agentId, String repositoryId, String branch) {
      this.agentId = agentId;
      this.repositoryId = repositoryId;
      this.branch = branch;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key k
          && java.util.Objects.equals(agentId, k.agentId)
          && java.util.Objects.equals(repositoryId, k.repositoryId)
          && java.util.Objects.equals(branch, k.branch);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(agentId, repositoryId, branch);
    }
  }
}
