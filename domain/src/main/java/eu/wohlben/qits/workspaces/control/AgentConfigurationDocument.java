package eu.wohlben.qits.workspaces.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * The resolved agent-configuration document a workspace container is born with — the whole of what
 * qits-projects answers at {@code GET /projects/api/agent-configuration}, kept as the bytes it
 * arrived as.
 *
 * <p><b>The bytes are the value.</b> This service does not own the document's vocabulary and must
 * not re-render it: the surfaces, their harnesses, prompts and MCP attachments are qits-projects'
 * shape, and the shared harness library inside the container is what reads and validates them. A
 * record mirroring those fields here would be a third copy of a contract two repositories already
 * hold, and it would have to be released before either of them could add a field. So the document
 * travels as text, and what this class adds is the one check a fetcher can honestly make.
 *
 * <p><b>What {@link #of} checks, and why so little.</b> A document must be a JSON object carrying a
 * {@code version} and a non-empty {@code surfaces} array — that is enough to tell "qits-projects
 * answered" from "something answered": an error page, an edge 502 body, an empty document from a
 * service that is still starting. It deliberately does not check the version number, the surface
 * keys or anything inside a surface. A document from a newer qits-projects than this service must
 * reach the container unchanged, because the container's library is what was released against it,
 * and a validator here that knew less than the library would refuse configurations that work.
 *
 * <p>Parsed through a tree rather than a bound record, which is also the native-image answer: a
 * {@link JsonNode} needs no reflection registration, so there is nothing for a binary to be missing.
 *
 * @param json the document exactly as it was answered — what is stored on the row and handed to the
 *     container
 * @param version the shape version the document declares, for logging and for a human reading a row
 * @param surfaces the surface keys it carries, in document order — what a log line names so an
 *     operator can see which surfaces a container was born knowing about
 */
public record AgentConfigurationDocument(String json, int version, List<String> surfaces) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Read a document off the wire.
   *
   * @throws IllegalArgumentException when the body is not a document — unparseable, not an object,
   *     or carrying no surfaces. The message names what was wrong and is what ends up on the
   *     workspace row when a container is created without a document.
   */
  public static AgentConfigurationDocument of(String body) {
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException("the agent configuration document was empty");
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(body);
    } catch (Exception notJson) {
      throw new IllegalArgumentException(
          "the agent configuration document is not JSON: " + notJson.getMessage(), notJson);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("the agent configuration document is not a JSON object");
    }
    JsonNode surfaces = root.get("surfaces");
    if (surfaces == null || !surfaces.isArray() || surfaces.isEmpty()) {
      throw new IllegalArgumentException(
          "the agent configuration document carries no surfaces; a container born with it would"
              + " configure nothing");
    }
    List<String> keys = new ArrayList<>();
    for (JsonNode surface : surfaces) {
      JsonNode key = surface.get("surface");
      keys.add(key == null || key.isNull() ? "" : key.asText());
    }
    JsonNode version = root.get("version");
    return new AgentConfigurationDocument(
        body, version == null ? 0 : version.asInt(), List.copyOf(keys));
  }
}
