package eu.wohlben.qits.workspaces.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The two directions of {@link WorkspaceRunner#capabilities}, and the merge between them. qits-ci's
 * {@code RunnerCapabilities}, with one difference: a runner here reports in pieces (its version at
 * registration, {@code dotClaudeVolume} in its inventory, {@code login} in its login state), so a
 * report is <b>merged</b> into what is stored rather than replacing it.
 *
 * <p><b>The merge is by top-level key.</b> A key in the report replaces the stored value whole, and a
 * key the report does not mention keeps its last known value, offline included. So a {@code login}
 * object is always the newest one the runner sent, never a mix of two probes.
 *
 * <p><b>The runner's word, never interpreted here.</b> The one rule on the way in is shape and size:
 * an object, at most {@link #MAX_CHARS} of JSON, because it is caller-supplied text that lands in a
 * row and on every listing.
 *
 * <p><b>Reading never throws.</b> A stored value that is not a JSON object reads back as null, "said
 * nothing", rather than costing a listing its answer. {@code readTree} plus a walk needs no
 * native-image registration.
 */
public final class WorkspaceRunnerCapabilities {

  /** 16 KiB of JSON is far more than a runner has to say about itself, and a bound all the same. */
  public static final int MAX_CHARS = 16 * 1024;

  /** The runner's own version, as its binary reports it. */
  public static final String VERSION = "version";

  /** The runner's node-local agent home volume (qits-858). */
  public static final String DOT_CLAUDE_VOLUME = "dotClaudeVolume";

  /** The node's agent login state: {@code {claude, kimi, checkedAt}} (qits-858). */
  public static final String LOGIN = "login";

  /** Shared and read-only: {@code readTree} and {@code writeValueAsString} mutate no mapper. */
  private static final ObjectMapper JSON = new ObjectMapper();

  private WorkspaceRunnerCapabilities() {}

  /**
   * The column text after merging {@code said} into {@code stored}: every key of {@code said}
   * replaces the stored one, every other stored key stays. A null or empty report leaves {@code
   * stored} as it is; a stored value that cannot be read is replaced.
   *
   * @throws IllegalArgumentException when {@code said} is not a JSON object, or the result is too
   *     large to keep
   */
  public static String merge(String stored, JsonNode said) {
    if (said == null || said.isNull() || said.isMissingNode()) {
      return stored;
    }
    if (!said.isObject()) {
      throw new IllegalArgumentException("capabilities must be a JSON object");
    }
    if (said.isEmpty()) {
      return stored;
    }
    JsonNode known = decode(stored);
    ObjectNode merged = known == null ? JSON.createObjectNode() : ((ObjectNode) known).deepCopy();
    said.properties().forEach(field -> merged.set(field.getKey(), field.getValue()));
    return encode(merged);
  }

  /**
   * The column text for {@code capabilities}, or null for nothing.
   *
   * @throws IllegalArgumentException when the value is not a JSON object or is too large to keep
   */
  public static String encode(JsonNode capabilities) {
    if (capabilities == null || capabilities.isNull() || capabilities.isMissingNode()) {
      return null;
    }
    if (!capabilities.isObject()) {
      throw new IllegalArgumentException("capabilities must be a JSON object");
    }
    String text;
    try {
      text = JSON.writeValueAsString(capabilities);
    } catch (Exception unwritable) {
      throw new IllegalArgumentException("capabilities could not be written as JSON");
    }
    if (text.length() > MAX_CHARS) {
      throw new IllegalArgumentException(
          "capabilities are "
              + text.length()
              + " characters of JSON, above the "
              + MAX_CHARS
              + " kept");
    }
    return text;
  }

  /** The stored object, or null when there is none or it cannot be read back as one. */
  public static JsonNode decode(String stored) {
    if (stored == null || stored.isBlank()) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(stored);
      return node != null && node.isObject() ? node : null;
    } catch (Exception unreadable) {
      return null;
    }
  }

  /** One string the runner said at the top level, or null when it said none there. */
  public static String text(JsonNode said, String field) {
    return said != null && said.path(field).isTextual() ? said.path(field).textValue() : null;
  }
}
