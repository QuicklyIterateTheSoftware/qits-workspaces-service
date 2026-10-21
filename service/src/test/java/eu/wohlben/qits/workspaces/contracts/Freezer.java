package eu.wohlben.qits.workspaces.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Freezes one state's answer</b> so every recording is byte-identical. qits-projects' freezing
 * rules, with two differences that follow from the states here:
 *
 * <ul>
 *   <li><b>Ids.</b> A UUID already in frozen form ({@code 00000000-0000-4000-8000-…}) stays as it
 *       is: the states write qits-projects' frozen ids on purpose, so a consumer can join the two
 *       recordings. Any other UUID becomes {@code 00000000-0000-4000-8000-0000000001NN}, numbered by
 *       first appearance. Both are listed in {@link #idPaths}.
 *   <li><b>Row ids.</b> A workspace's generated row id (a number in a field named {@code id},
 *       {@code workspaceRowId} or {@code workspaceId}) becomes its place in the state's write order,
 *       1 for the first row written. A row the call itself made (a dispatch) takes the next number
 *       at its first appearance. So does each number in a list of them ({@code workspaceIds}, a
 *       refused runner delete's). Listed in {@link #numberPaths}.
 *   <li><b>Instants.</b> Every ISO-8601 instant becomes {@value #FROZEN_INSTANT}.
 * </ul>
 *
 * <p><b>A runner frame names its rows in fields of its own</b> (qits-950): {@code rowId}, the
 * {@code owned} list, and the base's held and adopted lists, which carry row ids as strings. {@link
 * #frames} freezes those too. It is a mode and not a default because the HTTP answers use {@code
 * owned} for a count.
 */
public final class Freezer {

  static final Pattern UUID =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  static final Pattern FROZEN_UUID = Pattern.compile("00000000-0000-4000-8000-[0-9a-f]{12}");

  static final Pattern INSTANT =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})");

  static final String FROZEN_INSTANT = "2026-01-01T00:00:00Z";

  /** A path segment of digits alone: where a row id sits inside an address. */
  static final Pattern ROW_ID_SEGMENT = Pattern.compile("(?<=/)\\d+(?=/|$)");

  /** The fields that hold a workspace row id. */
  static final Set<String> ROW_ID_FIELDS = Set.of("id", "workspaceRowId", "workspaceId");

  /** The fields that hold a list of workspace row ids. */
  static final Set<String> ROW_ID_LIST_FIELDS = Set.of("workspaceIds");

  /** The fields of a runner frame that hold a row id, a list of them, and a list of them as text. */
  static final Set<String> FRAME_ROW_ID_FIELDS = Set.of("rowId");

  static final Set<String> FRAME_ROW_ID_LIST_FIELDS = Set.of("owned");

  static final Set<String> FRAME_ROW_ID_TEXT_LIST_FIELDS =
      Set.of("adoptedWorkspaces", "heldWorkspaces");

  private Set<String> rowIdFields = ROW_ID_FIELDS;
  private Set<String> rowIdListFields = ROW_ID_LIST_FIELDS;
  private Set<String> rowIdTextListFields = Set.of();

  private final Map<Long, Long> rowIds = new HashMap<>();
  private final Map<String, String> ids = new HashMap<>();
  private final Set<String> idPaths = new LinkedHashSet<>();
  private final Set<String> instantPaths = new LinkedHashSet<>();
  private final Set<String> stringPaths = new LinkedHashSet<>();
  private final Set<String> numberPaths = new LinkedHashSet<>();

  /** The state's row ids in write order: the first becomes 1. */
  public Freezer rowIds(List<Long> writeOrder) {
    for (int i = 0; i < writeOrder.size(); i++) {
      rowIds.put(writeOrder.get(i), (long) (i + 1));
    }
    return this;
  }

  /** Freezes a runner frame: its own row id fields as well. */
  public Freezer frames() {
    rowIdFields = union(ROW_ID_FIELDS, FRAME_ROW_ID_FIELDS);
    rowIdListFields = union(ROW_ID_LIST_FIELDS, FRAME_ROW_ID_LIST_FIELDS);
    rowIdTextListFields = FRAME_ROW_ID_TEXT_LIST_FIELDS;
    return this;
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> both = new LinkedHashSet<>(a);
    both.addAll(b);
    return Set.copyOf(both);
  }

  public JsonNode freeze(JsonNode node) {
    return freeze(node, "$", null);
  }

  public String freezeParam(String value) {
    return freezeIds(value);
  }

  /**
   * A param that holds a workspace row id ({@code workspaceId}), frozen as the answers freeze one:
   * its place in the state's write order.
   */
  public String freezeRowIdParam(String value) {
    long row = Long.parseLong(value);
    return String.valueOf(rowIds.computeIfAbsent(row, k -> (long) (rowIds.size() + 1)));
  }

  /**
   * {@code value} with every path segment that is one of the state's row ids ({@code
   * /workspaces/daemon/<id>}) replaced by its frozen number; any other segment as it is.
   */
  public String freezeRowIdSegments(String value) {
    Matcher m = ROW_ID_SEGMENT.matcher(value);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      Long frozen = rowIds.get(Long.parseLong(m.group()));
      m.appendReplacement(out, frozen == null ? m.group() : String.valueOf(frozen));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** Records that the value at {@code path} was replaced by a fixed one before freezing. */
  public void markString(String path) {
    stringPaths.add(path);
  }

  public List<String> idPaths() {
    return new ArrayList<>(idPaths);
  }

  public List<String> instantPaths() {
    return new ArrayList<>(instantPaths);
  }

  public List<String> stringPaths() {
    return new ArrayList<>(stringPaths);
  }

  public List<String> numberPaths() {
    return new ArrayList<>(numberPaths);
  }

  private JsonNode freeze(JsonNode node, String path, String field) {
    if (node == null || node.isNull()) {
      return node;
    }
    if (node.isTextual() && field != null && rowIdTextListFields.contains(field)) {
      Long frozen =
          rowIds.computeIfAbsent(Long.parseLong(node.asText()), k -> (long) (rowIds.size() + 1));
      numberPaths.add(path);
      return TextNode.valueOf(String.valueOf(frozen));
    }
    if (node.isTextual()) {
      return TextNode.valueOf(freezeText(node.asText(), path));
    }
    if (node.isIntegralNumber()
        && field != null
        && (rowIdFields.contains(field) || rowIdListFields.contains(field))) {
      Long frozen = rowIds.computeIfAbsent(node.asLong(), k -> (long) (rowIds.size() + 1));
      numberPaths.add(path);
      return LongNode.valueOf(frozen);
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      for (JsonNode element : node) {
        out.add(
            freeze(
                element,
                path + "[*]",
                field != null
                        && (rowIdListFields.contains(field) || rowIdTextListFields.contains(field))
                    ? field
                    : null));
      }
      return out;
    }
    if (node.isObject()) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        out.set(
            entry.getKey(),
            freeze(entry.getValue(), path + "." + entry.getKey(), entry.getKey()));
      }
      return out;
    }
    return node;
  }

  private String freezeText(String value, String path) {
    if (INSTANT.matcher(value).matches()) {
      instantPaths.add(path);
      return FROZEN_INSTANT;
    }
    if (UUID.matcher(value).matches()) {
      idPaths.add(path);
      return freezeIds(value);
    }
    String result = freezeIds(value);
    if (!result.equals(value)) {
      stringPaths.add(path);
    }
    return result;
  }

  private String freezeIds(String value) {
    Matcher m = UUID.matcher(value);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String original = m.group().toLowerCase(Locale.ROOT);
      String frozen =
          FROZEN_UUID.matcher(original).matches()
              ? original
              : ids.computeIfAbsent(
                  original,
                  k -> String.format("00000000-0000-4000-8000-%012x", 0x100 + ids.size()));
      m.appendReplacement(out, Matcher.quoteReplacement(frozen));
    }
    m.appendTail(out);
    return out.toString();
  }
}
