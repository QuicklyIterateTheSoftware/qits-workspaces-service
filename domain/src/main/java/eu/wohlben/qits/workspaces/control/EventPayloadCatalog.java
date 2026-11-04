package eu.wohlben.qits.workspaces.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import eu.wohlben.qits.eventstream.selection.EventSelection.Op;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Which payload fields an event carries (qits-1849), so a declared wait whose path its event never
 * carries is refused at the door instead of waiting forever: {@code BuildStatusChanged} has {@code
 * repoName}, and a wait on {@code repositoryName} matched nothing, silently.
 *
 * <p>The catalog is the classpath resource {@value #RESOURCE} beside this class, read as a JSON tree
 * — never bound, so a native image needs no reflection for it, only the resource itself
 * ({@code quarkus.native.resources.includes} in {@code service}). Its {@code _comment} says which
 * entries are pinned by {@code EventPayloadCatalogDriftTest} (this repository's own events) and which
 * are transcribed by hand from qits-ci and qits-projects and pinned by nothing.
 *
 * <p>{@link #refusal} walks every condition's path as {@code EventSelectionEvaluator} would and
 * refuses what can never match: a segment the event does not carry, {@code []} on a field that is no
 * list, a segment below a single value, a list passed through without {@code []}, and an {@code
 * exact}/{@code prefix} on a list or an object (it compares the whole JSON literal). {@code exists}
 * and {@code absent} hold on any declared field — every one is optional on the wire, since a null is
 * dropped from a payload — including a bare list ({@code !entities}). An event the catalog does not
 * name is not checked at all.
 */
public final class EventPayloadCatalog {

  /** The resource's name, relative to this class's package. */
  public static final String RESOURCE = "event-fields.json";

  /** The payload fields a {@code QitsEvent} never carries: {@code CanonicalJson} moves them to the envelope. */
  public static final List<String> ENVELOPE_FIELDS = List.of("eventId", "occurredAt");

  /** A payload field's shape. */
  public sealed interface Field permits Scalar, Obj, ListOf {}

  /** A string, number or boolean. */
  public record Scalar() implements Field {}

  /** An object with named fields. */
  public record Obj(Map<String, Field> fields) implements Field {}

  /** A list; its element is a {@link Scalar} or an {@link Obj}. */
  public record ListOf(Field element) implements Field {}

  private static final Scalar SCALAR = new Scalar();

  private final Map<String, Obj> events;

  private EventPayloadCatalog(Map<String, Obj> events) {
    this.events = events;
  }

  private static final class Shipped {
    private static final EventPayloadCatalog INSTANCE = load();
  }

  /** The catalog this jar ships. */
  public static EventPayloadCatalog shipped() {
    return Shipped.INSTANCE;
  }

  static EventPayloadCatalog load() {
    try (InputStream in = EventPayloadCatalog.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is not on the classpath");
      }
      return parse(new ObjectMapper().readTree(in));
    } catch (IOException unreadable) {
      throw new UncheckedIOException(RESOURCE + " is not readable", unreadable);
    }
  }

  /** Reads the catalog's JSON form; see the resource's {@code _comment}. */
  static EventPayloadCatalog parse(JsonNode root) {
    JsonNode listed = root.path("events");
    if (!listed.isObject()) {
      throw new IllegalStateException(RESOURCE + " has no \"events\" object");
    }
    Map<String, Obj> events = new TreeMap<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = listed.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> event = it.next();
      if (!event.getKey().startsWith("_")) {
        events.put(event.getKey(), object(event.getKey(), event.getValue()));
      }
    }
    return new EventPayloadCatalog(Collections.unmodifiableMap(events));
  }

  private static Obj object(String where, JsonNode node) {
    if (!node.isObject()) {
      throw new IllegalStateException(RESOURCE + ": " + where + " is not an object of fields");
    }
    Map<String, Field> fields = new TreeMap<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> field = it.next();
      if (!field.getKey().startsWith("_")) {
        fields.put(field.getKey(), field(where + "." + field.getKey(), field.getValue()));
      }
    }
    return new Obj(Collections.unmodifiableMap(fields));
  }

  private static Field field(String where, JsonNode node) {
    if (node.isTextual() && node.textValue().equals("scalar")) {
      return SCALAR;
    }
    if (node.isObject()) {
      return object(where, node);
    }
    if (node.isArray() && node.size() == 1) {
      Field element = field(where + "[]", node.get(0));
      if (element instanceof ListOf) {
        throw new IllegalStateException(RESOURCE + ": " + where + " is a list of lists");
      }
      return new ListOf(element);
    }
    throw new IllegalStateException(
        RESOURCE + ": " + where + " is neither \"scalar\", {…}, [\"scalar\"] nor [{…}]");
  }

  /** Every event name the catalog checks. */
  public Set<String> events() {
    return events.keySet();
  }

  /** The event's payload, as a top-level object; empty for an event the catalog does not name. */
  public Optional<Obj> payload(String event) {
    return Optional.ofNullable(events.get(event));
  }

  /**
   * Why the selection can never match its event, in one sentence for a 400; empty when every path
   * can, or when the catalog does not name the event.
   */
  public Optional<String> refusal(EventSelection selection) {
    Obj payload = events.get(selection.event());
    if (payload == null) {
      return Optional.empty();
    }
    for (List<Condition> group : selection.when()) {
      for (Condition condition : group) {
        Optional<String> refused = refusal(selection.event(), payload, condition);
        if (refused.isPresent()) {
          return refused;
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<String> refusal(String event, Obj payload, Condition condition) {
    String[] segments = condition.path().split("\\.", -1);
    Field at = payload;
    StringBuilder walked = new StringBuilder();
    for (String segment : segments) {
      boolean each = segment.endsWith("[]");
      String name = each ? segment.substring(0, segment.length() - 2) : segment;
      String here = walked.isEmpty() ? "" : walked.toString();
      if (at instanceof Scalar) {
        return Optional.of(
            event + "'s '" + here + "' is a single value, so '" + condition.path()
                + "' never resolves; compare '" + here + "' itself.");
      }
      if (at instanceof ListOf) {
        return Optional.of(
            event + "'s '" + here + "' is a list, so '" + condition.path()
                + "' never resolves; write '" + here + "[]." + name
                + "' to look into its elements.");
      }
      Obj object = (Obj) at;
      Field field = object.fields().get(name);
      String path = here.isEmpty() ? name : here + "." + name;
      if (field == null) {
        return Optional.of(unknown(event, here, name, path, object));
      }
      if (each && !(field instanceof ListOf)) {
        return Optional.of(
            event + "'s '" + path + "' is not a list; drop the [] from '" + condition.path() + "'.");
      }
      at = each ? ((ListOf) field).element() : field;
      walked.setLength(0);
      walked.append(each ? path + "[]" : path);
    }
    if (condition.op() == Op.EXACT || condition.op() == Op.PREFIX) {
      String path = walked.toString();
      if (at instanceof ListOf list) {
        String into =
            list.element() instanceof Obj element
                ? "'" + path + "[].<field>' (" + names(element) + ")"
                : "'" + path + "[]'";
        return Optional.of(
            event + "'s '" + path + "' is a list, and " + condition.op().wire()
                + " never matches a whole list; write " + into + " to compare its elements.");
      }
      if (at instanceof Obj object) {
        return Optional.of(
            event + "'s '" + path + "' is an object, and " + condition.op().wire()
                + " never matches a whole object; compare one of its fields: " + names(object) + ".");
      }
    }
    return Optional.empty();
  }

  private static String unknown(String event, String parent, String name, String path, Obj object) {
    StringBuilder reason = new StringBuilder(event).append(" carries no field '").append(path).append("'");
    if (parent.isEmpty() && ENVELOPE_FIELDS.contains(name)) {
      reason.append(" (").append(name).append(" rides the envelope, not the payload)");
    }
    Optional<String> hint = closest(name, object.fields().keySet());
    if (hint.isPresent()) {
      reason
          .append("; did you mean '")
          .append(parent.isEmpty() ? hint.get() : parent + "." + hint.get())
          .append("'?");
    } else {
      reason.append('.');
    }
    reason.append(parent.isEmpty() ? " Its fields: " : " The fields of '" + parent + "': ");
    return reason.append(names(object)).append('.').toString();
  }

  /** The object's field names, sorted, a list written {@code name[]}. */
  private static String names(Obj object) {
    return object.fields().entrySet().stream()
        .map(f -> f.getValue() instanceof ListOf ? f.getKey() + "[]" : f.getKey())
        .collect(Collectors.joining(", "));
  }

  /**
   * The candidate the typed name most likely meant, if any is close: one differing only by case,
   * then by a Levenshtein distance of at most 3 (and less than the typed name's length), then one
   * containing the other, then an abbreviation — the shorter's letters in order within the longer,
   * the same first letter, at least 4 of them ({@code repoName} for {@code repositoryName}). All
   * compared case-insensitively; within a rank the smaller distance wins, then the name.
   */
  static Optional<String> closest(String typed, Collection<String> candidates) {
    String t = typed.toLowerCase(Locale.ROOT);
    record Scored(String name, int rank, int distance) {}
    return candidates.stream()
        .map(
            candidate -> {
              String c = candidate.toLowerCase(Locale.ROOT);
              int distance = levenshtein(t, c);
              int rank;
              if (c.equals(t)) {
                rank = 0;
              } else if (distance <= 3 && distance < t.length()) {
                rank = 1;
              } else if (c.contains(t) || t.contains(c)) {
                rank = 2;
              } else if (abbreviates(t, c)) {
                rank = 3;
              } else {
                return null;
              }
              return new Scored(candidate, rank, distance);
            })
        .filter(Objects::nonNull)
        .min(
            Comparator.comparingInt(Scored::rank)
                .thenComparingInt(Scored::distance)
                .thenComparing(Scored::name))
        .map(Scored::name);
  }

  private static boolean abbreviates(String a, String b) {
    String shorter = a.length() <= b.length() ? a : b;
    String longer = shorter == a ? b : a;
    if (shorter.length() < 4 || shorter.charAt(0) != longer.charAt(0)) {
      return false;
    }
    int at = 0;
    for (int i = 0; i < longer.length() && at < shorter.length(); i++) {
      if (longer.charAt(i) == shorter.charAt(at)) {
        at++;
      }
    }
    return at == shorter.length();
  }

  static int levenshtein(String a, String b) {
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
        current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }
}
