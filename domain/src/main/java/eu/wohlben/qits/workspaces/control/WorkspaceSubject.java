package eu.wohlben.qits.workspaces.control;

/**
 * What a workspace is <em>for</em>, where something outside this context decided that: the
 * qits-projects ticket or epic an agent dispatch was about.
 *
 * <p><b>A record and not two adjacent {@code String} parameters</b>, which is the same reasoning
 * {@link WorkspaceService#createWorkspace} gives for keeping {@code admin} off the positional forms:
 * two same-typed arguments next to each other are read positionally and swapped silently, and a
 * workspace filed under the wrong subject is a link that opens somebody else's work.
 *
 * <p>Both members are ids in <em>another</em> context's store. Nothing here resolves either, and
 * nothing here renders either — the client composes the link, because that needs this platform's
 * public origin and the browser is the side that has been told it.
 *
 * <p>{@link #none()} is the ordinary answer: every workspace a person creates by hand names no
 * subject and states its scope in the preamble instead.
 *
 * @param ticketId the ticket a dispatch was about, or {@code null}
 * @param epicId the epic a dispatch was about, or {@code null}
 * @param entityId the subject's qualified id as qits-projects spells it — {@code
 *     <project-slug>-<number>}, e.g. {@code qits-614} — or {@code null}. It names the same row
 *     {@code ticketId}/{@code epicId} names, in the human-readable form qits-projects' own doors use;
 *     this context resolves nothing from it and carries it for exactly one reason — see {@code
 *     Workspace.entityId}
 * @param workId the work item's entity id in qits-projects, whatever its archetype (epic, ticket,
 *     feature, task, campaign), or {@code null}. When it is not given, {@link #normalized()} takes
 *     it from {@code ticketId} or {@code epicId}, which are the same kind of id. See {@code
 *     Workspace.workId}
 */
public record WorkspaceSubject(String ticketId, String epicId, String entityId, String workId) {

  private static final WorkspaceSubject NONE = new WorkspaceSubject(null, null, null, null);

  /** The subject without an explicit work id: {@link #normalized()} derives it. */
  public WorkspaceSubject(String ticketId, String epicId, String entityId) {
    this(ticketId, epicId, entityId, null);
  }

  /** No subject — the ad-hoc create, and every workspace that predates the fields. */
  public static WorkspaceSubject none() {
    return NONE;
  }

  /**
   * The subject as it should be stored, blanks normalised to {@code null} so an empty string sent by
   * a caller does not read as "this workspace is about a ticket whose id is nothing".
   */
  public WorkspaceSubject normalized() {
    String ticket = trimmed(ticketId);
    String epic = trimmed(epicId);
    String work = trimmed(workId);
    if (work == null) {
      work = ticket != null ? ticket : epic;
    }
    return new WorkspaceSubject(ticket, epic, trimmed(entityId), work);
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
