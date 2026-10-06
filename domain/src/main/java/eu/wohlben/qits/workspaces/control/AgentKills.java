package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * What a workspace row says when its coding agent was <em>killed</em> rather than finished
 * (qits-951): a {@code runtimeError} on a row that stays RUNNING.
 *
 * <p><b>Why the row, and why {@code runtimeError}.</b> The cgroup OOM killer takes the agent and
 * leaves the container — the daemon is PID 1 and survives, docker still reads {@code running} — so
 * {@code runtimeStatus} is truthfully RUNNING, and the agent-activity rollup can only say the
 * session {@code ENDED}, which is also what a session that ended cleanly says. Something has to say
 * <em>it died</em>, and it has to outlive the rollup's in-memory cache (which a disconnect drops and
 * a TTL expires) and be on {@code /workspaces/{id}}, which the SPA and qits-projects already read.
 * {@code runtimeError} is the field that already means "this went wrong, and here is why", so the
 * kill is written there rather than into a new column or a new runtime status: a new status would be
 * a lie about the container, and a new enum value on the DTO is a deserialisation failure for every
 * reader that has not learned it.
 *
 * <p><b>Recognisable by its code</b>, like the runner refusals ({@code EDGE_PLANE_UNCONFIGURED:
 * …}): {@value #OOM_KILLED} or {@value #KILLED}, then the sentence. That is what lets {@link
 * #clear} take back only what it wrote — a provision failure in the same column is not this class's
 * to clear — and what lets a runner's inventory, which clears the column on every row it holds
 * running, leave this one standing ({@link #isAgentKill}).
 *
 * <p><b>It clears the way the column always has, plus one way of its own.</b> Every re-provision
 * already nulls {@code runtimeError} — ensure, queue, a runner's claim and launch, a delete — and
 * that is the "relaunch the container" half. The other half is {@link #clear}, called when an agent
 * in the workspace starts a turn again: the row then has a working agent in it, and an error still
 * standing would tell a dispatcher that reads it that the new turn failed too.
 */
@ApplicationScoped
public class AgentKills {

  private static final Logger LOG = Logger.getLogger(AgentKills.class);

  /** The code of an agent the cgroup's out-of-memory killer took. */
  public static final String OOM_KILLED = "AGENT_OOM_KILLED";

  /** The code of an agent that died by SIGKILL with no OOM kill counted for it. */
  public static final String KILLED = "AGENT_KILLED";

  /** 128 + SIGKILL: the exit code every kill this class records carries. */
  public static final int SIGKILL_EXIT = 137;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject WorkspaceChangePublisher changePublisher;

  /** Whether {@code runtimeError} is an agent kill this class wrote. */
  public static boolean isAgentKill(String runtimeError) {
    return runtimeError != null
        && (runtimeError.startsWith(OOM_KILLED + ": ") || runtimeError.startsWith(KILLED + ": "));
  }

  /**
   * The error an agent kill is recorded as: its code, then the daemon's own sentence when it sent
   * one — which names the memory cap — and otherwise this host's, which knows only the exit code.
   *
   * @param oom whether the daemon attributed the kill to the OOM killer
   * @param commandId the agent's command, for a reader who wants the Commands view
   * @param detail the daemon's sentence, or null when the frame carried none
   */
  public static String describe(boolean oom, String commandId, String detail) {
    String code = oom ? OOM_KILLED : KILLED;
    if (detail != null && !detail.isBlank()) {
      return code + ": " + detail;
    }
    String how = oom ? "the out-of-memory killer" : "SIGKILL";
    return code
        + ": the coding agent (command "
        + commandId
        + ") was killed by "
        + how
        + " (exit code "
        + SIGKILL_EXIT
        + ") before its turn finished";
  }

  /**
   * Write {@code error} onto the ACTIVE row {@code rowId}, leaving its runtime status alone, and
   * tell the open views. A row that is gone or resolved is not written: the kill is about a
   * container that no longer matters.
   */
  public void record(Long rowId, String error) {
    Workspace written =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository
                        .findActiveById(rowId)
                        .map(
                            row -> {
                              row.runtimeError = truncate(error);
                              return row;
                            })
                        .orElse(null));
    if (written == null) {
      LOG.debugf("workspace %s is not active; its agent kill is not recorded", rowId);
      return;
    }
    LOG.warnf("workspace %s: %s", rowId, error);
    changePublisher.runtimeChanged(written.repositoryId, written.id);
  }

  /**
   * Clear an agent kill recorded on {@code rowId}, and nothing else: a {@code runtimeError} that is
   * not one of this class's is left exactly as it is.
   */
  public void clear(Long rowId) {
    Workspace cleared =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository
                        .findActiveById(rowId)
                        .filter(row -> isAgentKill(row.runtimeError))
                        .map(
                            row -> {
                              row.runtimeError = null;
                              return row;
                            })
                        .orElse(null));
    if (cleared != null) {
      changePublisher.runtimeChanged(cleared.repositoryId, cleared.id);
    }
  }

  private static String truncate(String s) {
    return s.length() <= 2000 ? s : s.substring(0, 2000);
  }
}
