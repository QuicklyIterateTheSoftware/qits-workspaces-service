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
 */
public record WorkspaceSubject(String ticketId, String epicId) {

  private static final WorkspaceSubject NONE = new WorkspaceSubject(null, null);

  /** No subject — the ad-hoc create, and every workspace that predates the fields. */
  public static WorkspaceSubject none() {
    return NONE;
  }

  /**
   * The subject as it should be stored, blanks normalised to {@code null} so an empty string sent by
   * a caller does not read as "this workspace is about a ticket whose id is nothing".
   */
  public WorkspaceSubject normalized() {
    return new WorkspaceSubject(trimmed(ticketId), trimmed(epicId));
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
