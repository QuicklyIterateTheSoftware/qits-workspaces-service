package eu.wohlben.qits.workspaces.control;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import eu.wohlben.qits.eventstream.selection.EventSelection.Op;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a declared wait waits on (epic qits-1681, {@code V33}): its <b>subject</b> — a release
 * request, a CI run, a work item, a work item's comments or a branch's delivery — read out of its
 * {@link EventSelection}. Pure: no bean, no database, no clock.
 *
 * <p><b>Parsing</b> ({@link #parse}). A subject is recognised only when <b>every</b> group of the
 * selection names the <b>same</b> ref, with op {@code exact}, on the path its event carries the
 * subject's id at:
 *
 * <table>
 *   <tr><th>event</th><th>ref path(s)</th><th>kind</th><th>target path</th></tr>
 *   <tr><td>{@code ReleaseRequestStateChanged}</td><td>{@code releaseRequestQualifiedId} or
 *       {@code releaseRequestId}</td><td>RELEASE_REQUEST</td><td>{@code state}</td></tr>
 *   <tr><td>{@code BuildStatusChanged}</td><td>{@code runId}</td><td>CI_RUN</td><td>{@code status}</td></tr>
 *   <tr><td>{@code BuildSuccessful}, {@code BuildFailed}</td><td>{@code runId}</td><td>CI_RUN</td><td>none</td></tr>
 *   <tr><td>{@code EntityTransitioned}</td><td>{@code entities[].entityId}</td><td>WORK_ITEM</td>
 *       <td>{@code entities[].status}</td></tr>
 *   <tr><td>{@code EntityCommented}</td><td>{@code entityId}</td><td>COMMENTS</td><td>none</td></tr>
 *   <tr><td>{@code BranchDeliveryChanged}</td><td>{@code branch}</td><td>BRANCH</td>
 *       <td>{@code step} (the union also takes {@code everywhere}'s exact values, in the same
 *       order)</td></tr>
 * </table>
 *
 * Anything else — another event, a group naming no ref or a different one, a {@code prefix} on the
 * ref path — is {@link Kind#OTHER} with a null ref. The target states are the union of the target
 * path's {@code exact} values across the groups, in their order; {@code []} when it names none. A
 * BRANCH wait's target path is {@code step}, but a group may instead (or additionally) name {@code
 * everywhere} with {@code exact}: both contribute to the same union, in the order their groups
 * appear, and a selection mixing the two is still BRANCH rather than OTHER. Its {@link
 * Subject#statePath} and {@link Subject#repository} say how an event is read for it, below.
 *
 * <p><b>Reading an event</b> ({@link #stateIn}): what state an event says the subject is in, when it
 * names this subject at all. For BRANCH it is read at the path the wait targets (qits-1736), the
 * subject's {@link Subject#statePath}: {@code everywhere} — the branch's least advanced step across
 * the project's repositories — when any group names an {@code exact} {@code everywhere}, else
 * {@code step}. An {@code everywhere} wait's state is therefore the event's {@code everywhere}, and
 * an event with none says no state; reading {@code step} there would record one repository going
 * LIVE as the whole branch being LIVE, while the wait — rightly — still waits. The early {@code
 * step} groups such a wait carries (FAILED, CONFLICTED, WITHDRAWN in any repository) still fire it
 * through the matcher, but they never show as its subject state. When every group names the same
 * {@code repository} with {@code exact} ({@link Subject#repository}, as {@code qits wait branch
 * --repository} writes it), an event of another repository says no state either: a step there is
 * not this wait's. Groups that disagree on it, or a group naming none, leave no repository filter —
 * such a wait listens to more than one repository, so no single one's events can be ruled out.
 *
 * <p><b>Final states</b> ({@link #isFinal}), which the attention evaluation reads: a request is done
 * FINALIZED, WITHDRAWN or OBSOLETE; a run is done in anything but QUEUED and RUNNING; an item DONE or
 * DROPPED; a branch is done LIVE or WITHDRAWN; comments and OTHER never are.
 */
public final class WaitSubjects {

  /** What kind of thing a wait waits on. */
  public enum Kind {
    RELEASE_REQUEST,
    CI_RUN,
    WORK_ITEM,
    COMMENTS,
    BRANCH,
    OTHER
  }

  /**
   * A wait's parsed subject.
   *
   * @param ref the value the selection names, as written; null for {@link Kind#OTHER}
   * @param targetStates the state values the selection waits for; empty when it names none
   * @param statePath BRANCH only (qits-1736): the event path its state is read at, {@code step} or
   *     {@code everywhere}; null for every other kind
   * @param repository BRANCH only (qits-1736): the repository every group names with {@code exact},
   *     whose events alone say its state; null when the groups name none or disagree
   */
  public record Subject(
      Kind kind, String ref, List<String> targetStates, String statePath, String repository) {
    public Subject {
      targetStates = List.copyOf(targetStates);
    }

    /** A subject with no BRANCH state path and no repository: every kind but BRANCH. */
    public Subject(Kind kind, String ref, List<String> targetStates) {
      this(kind, ref, targetStates, null, null);
    }

    static final Subject OTHER = new Subject(Kind.OTHER, null, List.of());
  }

  public static final String RELEASE_REQUEST_STATE_CHANGED = "ReleaseRequestStateChanged";

  public static final String BUILD_STATUS_CHANGED = "BuildStatusChanged";

  public static final String BUILD_SUCCESSFUL = "BuildSuccessful";

  public static final String BUILD_FAILED = "BuildFailed";

  public static final String ENTITY_TRANSITIONED = "EntityTransitioned";

  public static final String ENTITY_COMMENTED = "EntityCommented";

  public static final String BRANCH_DELIVERY_CHANGED = "BranchDeliveryChanged";

  /** A release request's final states. */
  public static final Set<String> RELEASE_REQUEST_FINAL = Set.of("FINALIZED", "WITHDRAWN", "OBSOLETE");

  /** A run's active states: every other status is final. */
  public static final Set<String> CI_RUN_ACTIVE = Set.of("QUEUED", "RUNNING");

  /** A work item's final states. */
  public static final Set<String> WORK_ITEM_FINAL = Set.of("DONE", "DROPPED");

  /** A branch's final states ({@code step} values). */
  public static final Set<String> BRANCH_FINAL = Set.of("LIVE", "WITHDRAWN");

  /** The status {@code BuildSuccessful} stands for. */
  public static final String SUCCESS = "SUCCESS";

  /** The status {@code BuildFailed} stands for. */
  public static final String FAILED = "FAILED";

  private static final String ENTITY_PATH = "entities[]";

  private static final String ENTITY_ID = "entityId";

  private static final String ENTITY_STATUS = "status";

  private static final String STEP = "step";

  private static final String EVERYWHERE = "everywhere";

  private static final String REPOSITORY = "repository";

  /** How {@link #stateIn(String, JsonNode, Collection)} reads a branch: its step, in any repository. */
  private static final Subject ANY_BRANCH_STEP = new Subject(Kind.BRANCH, null, List.of(), STEP, null);

  /**
   * Per event: the kind, the ref paths and the target path(s) (empty: none). BRANCH is the only
   * kind with two — {@code step} and {@code everywhere} both contribute to the one union of target
   * states, in the order their groups appear.
   */
  private record Rule(Kind kind, Set<String> refPaths, Set<String> targetPaths) {
    Rule(Kind kind, Set<String> refPaths, String targetPath) {
      this(kind, refPaths, targetPath == null ? Set.of() : Set.of(targetPath));
    }
  }

  private static final Map<String, Rule> RULES =
      Map.of(
          RELEASE_REQUEST_STATE_CHANGED,
          new Rule(
              Kind.RELEASE_REQUEST, Set.of("releaseRequestQualifiedId", "releaseRequestId"), "state"),
          BUILD_STATUS_CHANGED, new Rule(Kind.CI_RUN, Set.of("runId"), "status"),
          BUILD_SUCCESSFUL, new Rule(Kind.CI_RUN, Set.of("runId"), (String) null),
          BUILD_FAILED, new Rule(Kind.CI_RUN, Set.of("runId"), (String) null),
          ENTITY_TRANSITIONED,
          new Rule(
              Kind.WORK_ITEM,
              Set.of(ENTITY_PATH + "." + ENTITY_ID),
              ENTITY_PATH + "." + ENTITY_STATUS),
          ENTITY_COMMENTED, new Rule(Kind.COMMENTS, Set.of(ENTITY_ID), (String) null),
          BRANCH_DELIVERY_CHANGED, new Rule(Kind.BRANCH, Set.of("branch"), Set.of(STEP, EVERYWHERE)));

  private WaitSubjects() {}

  /** The subject {@code selection} names; {@link Kind#OTHER} with a null ref when it names none. */
  public static Subject parse(EventSelection selection) {
    if (selection == null || selection.when() == null || selection.when().isEmpty()) {
      return Subject.OTHER;
    }
    Rule rule = RULES.get(selection.event());
    if (rule == null) {
      return Subject.OTHER;
    }
    String ref = null;
    LinkedHashSet<String> targets = new LinkedHashSet<>();
    boolean targetsEverywhere = false;
    // Every group's exact repository, or none (null in the set) for a group naming none.
    Set<String> repositories = new LinkedHashSet<>();
    for (List<Condition> group : selection.when()) {
      String groupRef = null;
      String groupRepository = null;
      for (Condition condition : group) {
        if (REPOSITORY.equals(condition.path())
            && condition.op() == Op.EXACT
            && condition.value() != null
            && groupRepository == null) {
          groupRepository = condition.value();
        }
        if (EVERYWHERE.equals(condition.path())
            && rule.targetPaths().contains(EVERYWHERE)
            && condition.op() == Op.EXACT
            && condition.value() != null) {
          targetsEverywhere = true;
        }
        if (rule.refPaths().contains(condition.path())) {
          if (condition.op() != Op.EXACT || condition.value() == null) {
            return Subject.OTHER;
          }
          if (groupRef != null && !groupRef.equals(condition.value())) {
            return Subject.OTHER;
          }
          groupRef = condition.value();
        } else if (rule.targetPaths().contains(condition.path())
            && condition.op() == Op.EXACT
            && condition.value() != null) {
          targets.add(condition.value());
        }
      }
      if (groupRef == null || (ref != null && !ref.equals(groupRef))) {
        return Subject.OTHER;
      }
      ref = groupRef;
      repositories.add(groupRepository);
    }
    if (rule.kind() != Kind.BRANCH) {
      return new Subject(rule.kind(), ref, new ArrayList<>(targets));
    }
    String repository = repositories.size() == 1 ? repositories.iterator().next() : null;
    return new Subject(
        rule.kind(), ref, new ArrayList<>(targets), targetsEverywhere ? EVERYWHERE : STEP, repository);
  }

  /**
   * The state {@code payload}, an event of {@code eventName}, says the subject is in — empty when
   * the event does not name the subject (none of {@code refs} at its ref path) or carries no state
   * for it. {@code refs} are every id the wait knows its subject by: the ref as written, and the
   * uuid and qualified id once resolved (nulls are ignored). {@code BuildSuccessful} is {@link
   * #SUCCESS} and {@code BuildFailed} {@link #FAILED}; an {@code EntityTransitioned} answers the
   * status of the entity whose id is a ref. {@code EntityCommented} carries no state, so it is
   * always empty. {@code BranchDeliveryChanged} answers its {@code step} when its {@code branch} is
   * a ref, in any repository — the reading of a subject that targets {@code step} and names no
   * repository; a wait's own reading is {@link #stateIn(Subject, String, JsonNode, Collection)}.
   */
  public static Optional<String> stateIn(String eventName, JsonNode payload, Collection<String> refs) {
    return stateIn(ANY_BRANCH_STEP, eventName, payload, refs);
  }

  /**
   * {@link #stateIn(String, JsonNode, Collection)}, as {@code subject} reads it: the same for every
   * kind but BRANCH, whose {@code BranchDeliveryChanged} answers the value at the subject's {@link
   * Subject#statePath} — {@code everywhere} for a wait targeting it, where a null or missing {@code
   * everywhere} is no state, else {@code step} — and nothing for an event of another repository than
   * the subject's {@link Subject#repository}, when it names one (qits-1736). A null {@code subject},
   * or one of another kind, reads a branch's {@code step} in any repository.
   */
  public static Optional<String> stateIn(
      Subject subject, String eventName, JsonNode payload, Collection<String> refs) {
    if (eventName == null || payload == null || !payload.isObject()) {
      return Optional.empty();
    }
    Set<String> known = new LinkedHashSet<>();
    refs.stream().filter(Objects::nonNull).forEach(known::add);
    if (known.isEmpty()) {
      return Optional.empty();
    }
    return switch (eventName) {
      case RELEASE_REQUEST_STATE_CHANGED ->
          known.contains(text(payload, "releaseRequestId"))
                  || known.contains(text(payload, "releaseRequestQualifiedId"))
              ? Optional.ofNullable(text(payload, "state"))
              : Optional.empty();
      case BUILD_STATUS_CHANGED ->
          known.contains(text(payload, "runId"))
              ? Optional.ofNullable(text(payload, "status"))
              : Optional.empty();
      case BUILD_SUCCESSFUL ->
          known.contains(text(payload, "runId")) ? Optional.of(SUCCESS) : Optional.empty();
      case BUILD_FAILED ->
          known.contains(text(payload, "runId")) ? Optional.of(FAILED) : Optional.empty();
      case ENTITY_TRANSITIONED -> entityStatus(payload.get("entities"), known);
      case BRANCH_DELIVERY_CHANGED -> branchState(subject, payload, known);
      default -> Optional.empty();
    };
  }

  /** Whether {@code state} is final for {@code kind}; never for COMMENTS, OTHER or a null state. */
  public static boolean isFinal(Kind kind, String state) {
    if (kind == null || state == null) {
      return false;
    }
    return switch (kind) {
      case RELEASE_REQUEST -> RELEASE_REQUEST_FINAL.contains(state);
      case CI_RUN -> !CI_RUN_ACTIVE.contains(state);
      case WORK_ITEM -> WORK_ITEM_FINAL.contains(state);
      case BRANCH -> BRANCH_FINAL.contains(state);
      case COMMENTS, OTHER -> false;
    };
  }

  /** {@code kind} by its stored name; null (a wait from before V33, a desk's from before qits-1668) stays null. */
  public static Kind kindOf(String stored) {
    if (stored == null) {
      return null;
    }
    try {
      return Kind.valueOf(stored);
    } catch (IllegalArgumentException unknown) {
      return Kind.OTHER;
    }
  }

  private static Optional<String> branchState(Subject subject, JsonNode payload, Set<String> refs) {
    if (!refs.contains(text(payload, "branch"))) {
      return Optional.empty();
    }
    boolean branchSubject = subject != null && subject.kind() == Kind.BRANCH;
    String repository = branchSubject ? subject.repository() : null;
    if (repository != null && !repository.equals(text(payload, REPOSITORY))) {
      return Optional.empty();
    }
    String path = branchSubject && subject.statePath() != null ? subject.statePath() : STEP;
    return Optional.ofNullable(text(payload, path));
  }

  private static Optional<String> entityStatus(JsonNode entities, Set<String> refs) {
    if (entities == null || !entities.isArray()) {
      return Optional.empty();
    }
    for (JsonNode entity : entities) {
      if (entity != null && refs.contains(text(entity, ENTITY_ID))) {
        return Optional.ofNullable(text(entity, ENTITY_STATUS));
      }
    }
    return Optional.empty();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() || value.isContainerNode() ? null : value.asText();
  }
}
