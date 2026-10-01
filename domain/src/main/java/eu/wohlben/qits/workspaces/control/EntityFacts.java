package eu.wohlben.qits.workspaces.control;

/**
 * What the in-container daemon is told about a dispatched workspace's subject beyond its id: the
 * title, the status word and whether it is blocked (qits-617). The daemon names its agent sessions
 * {@code [❗]<status square> <entityId> <title>} from these and {@code Workspace.entityId}; nothing
 * in this service renders, validates or interprets any of them.
 *
 * <p>Carried, never resolved — {@link WorkspaceSubject}'s reading, for its reason: the subject lives
 * in qits-projects' store and that context sends what it knows.
 *
 * @param title the subject's title as qits-projects holds it, or null when the caller sent none
 * @param status the status word, an enum constant over there (e.g. {@code REFINED}), or null
 * @param blocked whether the subject is blocked
 */
public record EntityFacts(String title, String status, boolean blocked) {

  /** Blank strings read as absent, so a blank never reaches a column or a container spec. */
  public EntityFacts normalized() {
    return new EntityFacts(trimmed(title), trimmed(status), blocked);
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
