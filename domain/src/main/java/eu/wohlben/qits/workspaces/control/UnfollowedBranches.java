package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import eu.wohlben.qits.eventstream.selection.EventSelection.Op;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * <b>Which branches the delivery ledger never follows</b> (qits-1960), so a {@code
 * BranchDeliveryChanged} wait on one of them is refused at the door instead of waiting for an event
 * that can never arrive: qits-projects' {@code BranchDeliveries.considered(branch, main)} ("Which
 * branches" in that class's own javadoc) never records a step for a repository's default branch, a
 * {@code release/} backing branch or a {@code maintenance/} bump, and a released tag is never passed
 * to it as a branch at all — a request's implicit source is a release, not a branch — so none of the
 * three is ever the {@code branch} a {@code BranchDeliveryChanged} event carries.
 *
 * <p>This is a hand transcription of that rule, the same way {@link EventPayloadCatalog}'s qits-ci
 * and qits-projects entries are transcribed by hand (qits-1849): there is no compile-time link to
 * qits-projects-service, and nothing here is pinned against a rename or a widened rule over there.
 * {@link #refusal} widens the check past {@code considered}'s own three literal rejections to catch
 * the tag forms a caller could plausibly type — {@code refs/tags/<name>} and the released {@code
 * YYYY.MMDD.HHMMSS} form itself — since {@code considered} is never asked about a tag in the first
 * place and so never says so itself.
 */
public final class UnfollowedBranches {

  private static final String EVENT = "BranchDeliveryChanged";

  private static final String PATH = "branch";

  /** A released tag's form, {@code YYYY.MMDD.HHMMSS}. */
  private static final Pattern RELEASED_TAG = Pattern.compile("^\\d{4}\\.\\d{3,4}\\.\\d{1,6}$");

  private UnfollowedBranches() {}

  /**
   * Why a {@code BranchDeliveryChanged} wait naming {@code branch} can never be announced, in one
   * sentence for a 400; empty for every other event, every other path, and a branch the ledger does
   * follow.
   */
  public static Optional<String> refusal(EventSelection selection) {
    if (!EVENT.equals(selection.event())) {
      return Optional.empty();
    }
    for (List<Condition> group : selection.when()) {
      for (Condition condition : group) {
        if (PATH.equals(condition.path())) {
          Optional<String> refused = refusalOf(condition);
          if (refused.isPresent()) {
            return refused;
          }
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<String> refusalOf(Condition condition) {
    String value = condition.value();
    if (condition.op() == Op.EXACT && neverFollowedExact(value)) {
      return Optional.of(sentence(value));
    }
    if (condition.op() == Op.PREFIX && neverFollowedPrefix(value)) {
      return Optional.of(sentence(value));
    }
    return Optional.empty();
  }

  private static boolean neverFollowedExact(String value) {
    return value != null
        && (value.startsWith("release/")
            || value.startsWith("maintenance/")
            || value.equals("main")
            || value.startsWith("refs/tags/")
            || RELEASED_TAG.matcher(value).matches());
  }

  private static boolean neverFollowedPrefix(String value) {
    return value != null && (value.startsWith("release/") || value.startsWith("maintenance/"));
  }

  private static String sentence(String value) {
    return "BranchDeliveryChanged is never announced for '"
        + value
        + "': the delivery ledger follows only a request's named branches, never main, release/*,"
        + " maintenance/* or a tag. To follow a bump branch, wait on its request: --event"
        + " ReleaseRequestStateChanged --when 'branches[]="
        + value
        + " state=FINALIZED' --when 'branches[]="
        + value
        + " state=REJECTED'. When the bump's name is not known yet: --when 'repoName=<repository>"
        + " branches[]^=maintenance/ state=FINALIZED' --when 'repoName=<repository>"
        + " branches[]^=maintenance/ state=REJECTED'.";
  }
}
