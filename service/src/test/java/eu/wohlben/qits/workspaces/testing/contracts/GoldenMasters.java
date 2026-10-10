package eu.wohlben.qits.workspaces.testing.contracts;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue;
import au.com.dius.pact.core.model.matchingrules.MatchingRule;
import au.com.dius.pact.core.model.matchingrules.NullMatcher;
import au.com.dius.pact.core.model.matchingrules.RegexMatcher;
import au.com.dius.pact.core.model.matchingrules.TypeMatcher;
import au.com.dius.pact.core.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>qits-projects' recorded answers, as this repository's tests consume them</b> (epic qits-546).
 *
 * <p>qits-projects records what it answers for each provider state — {@code golden-masters/index.json}
 * plus one JSON per (state, operation) — and publishes the tree as {@code
 * eu.wohlben.qits:qits-projects-golden-masters}, a test-scoped pin in the root pom. This class reads
 * it off the classpath and offers it two ways:
 *
 * <ul>
 *   <li>{@link #body} — the recorded JSON verbatim, for the existing fakes ({@code
 *       HttpRepositoryLookupTest}, {@code StoryPeers}) to serve instead of hand-written documents;
 *   <li>{@link #interaction} — a pact-jvm V4 interaction for the same (state, operation), with
 *       matchers derived from the index's {@code frozen} lists, for {@code ProjectsConsumerPactTest}.
 * </ul>
 *
 * <p><b>The frozen lists are read from the index, never inferred from a value's shape.</b> A string
 * that happens to look like a UUID is still type-matched unless the recorder listed its path under
 * {@code frozen.ids}. The mapping:
 *
 * <ul>
 *   <li>{@code frozen.ids} — the whole value is a UUID: {@code uuid} matcher;
 *   <li>{@code frozen.instants} — an ISO-8601 timestamp: a regex matcher ({@link #ISO_INSTANT}),
 *       which accepts any fraction length and offset, unlike a fixed {@code datetime} format;
 *   <li>{@code frozen.strings} — a string carrying a frozen value: type match;
 *   <li>{@code frozen.listFilteredTo} — the array the recorder reduced to the state's own
 *       entities: {@code minArrayLike(recorded length)}, "contains", never equals;
 *   <li>every other leaf — type match ({@code null} only where the recording holds nothing else).
 * </ul>
 *
 * <p><b>Every other non-empty array is {@code minMaxArrayLike(n, n)}</b> with n the recorded length:
 * exactly that many elements, each matched against ONE template, so the contract does not depend on
 * the provider's element order — which qits-projects does not guarantee (its recorder sorts
 * repository entries by name; the live answer need not). The template is the MERGE of every
 * recorded element: a leaf null in one element and a string in another becomes {@code type OR null}
 * (a V4 combined matcher), so a nullable field such as {@code component} matches in any position.
 * Unknown keys in {@code frozen} are tolerated.
 *
 * <p><b>A pact binds only what this service reads</b> (qits-1149). Each interaction names the body
 * paths its consumer code reads ({@code consumes}); the recorded body is cut down to those paths
 * before the matchers are built, and an interaction that reads nothing carries the status alone.
 *
 * <p><b>More than one provider.</b> Each provider publishes its tree at the same classpath root, so
 * the index is chosen by its {@code provider} field, and its files are read next to that index. The
 * one-argument methods are qits-projects'; the {@link Provider} overloads serve the others.
 */
public final class GoldenMasters {

  /** The consumer, as the pact names it: the repository name, never the bare application name. */
  public static final String CONSUMER = "qits-workspaces-service";

  /**
   * One provider: its repository name (the pact's provider name) and its application name (what
   * its golden-master index names, and the jar's prefix: {@code <application>-golden-masters}).
   */
  public record Provider(String repository, String application) {
    public Provider {
      Objects.requireNonNull(repository, "repository");
      Objects.requireNonNull(application, "application");
    }
  }

  /** qits-projects: the repository registry and the agent-waiting door. */
  public static final Provider PROJECTS = new Provider("qits-projects-service", "qits-projects");

  /** qits-idp: the commission doors for clients and tokens. */
  public static final Provider IDP = new Provider("qits-idp-service", "qits-idp");

  /** qits-containers: the place and volume doors behind {@code qits-containers-client}. */
  public static final Provider CONTAINERS =
      new Provider("qits-containers-service", "qits-containers");

  /** qits-workspace-daemon: the agent surface every workspace container serves. */
  public static final Provider WORKSPACE_DAEMON =
      new Provider("qits-workspace-daemon", "qits-workspace-daemon");

  /** The provider, as the pact names it: the repository name, never the bare application name. */
  public static final String PROVIDER = PROJECTS.repository();

  /** The provider as the golden-master index names it: the application name. */
  public static final String INDEX_PROVIDER = PROJECTS.application();

  /** Where the jar puts the tree on the classpath. */
  public static final String ROOT = "golden-masters/";

  /**
   * {@code consumes} for a call whose client parses the answer as a JSON object but whose code reads
   * no field of it — {@code qits-containers-client} binds every 2xx body and refuses one that does
   * not bind. The pact then holds an empty object: any object answers it.
   */
  public static final List<String> A_JSON_BODY = List.of("$");

  /** An ISO-8601 timestamp, any fraction length, Z or a numeric offset. */
  public static final String ISO_INSTANT =
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})$";

  private static final String UUID_REGEX =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)}");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** One loaded index and where it was found, so its files resolve next to it. */
  private record Index(JsonNode root, URL location) {}

  private static final Map<String, Index> INDEXES = new ConcurrentHashMap<>();

  private GoldenMasters() {}

  // --- the index --------------------------------------------------------------------------------

  /** One recorded (state, operation), as the index describes it. */
  public record Operation(
      Provider provider,
      String state,
      Map<String, String> params,
      String operationId,
      String method,
      String path,
      int status,
      String file,
      Set<String> ids,
      Set<String> instants,
      Set<String> strings,
      String listFilteredTo) {

    /** The path with every {@code {param}} replaced by the state's frozen example. */
    public String examplePath() {
      return substitute(path, name -> params.get(name));
    }

    /** The path as a provider-state expression: {@code {param}} becomes {@code ${param}}. */
    public String expressionPath() {
      return substitute(path, name -> "${" + name + "}");
    }

    private String substitute(String template, java.util.function.Function<String, String> value) {
      Matcher m = PARAM.matcher(template);
      StringBuilder out = new StringBuilder();
      while (m.find()) {
        String name = m.group(1);
        if (!params.containsKey(name)) {
          throw new IllegalStateException(
              "golden master " + state + "/" + operationId + ": path " + template
                  + " names {" + name + "}, which the state's params do not hold");
        }
        m.appendReplacement(out, Matcher.quoteReplacement(value.apply(name)));
      }
      m.appendTail(out);
      return out.toString();
    }
  }

  /** qits-projects' {@link #params(Provider, String)}. */
  public static Map<String, String> params(String state) {
    return params(PROJECTS, state);
  }

  /** The provider state's frozen example params (e.g. {@code repositoryId}). */
  public static Map<String, String> params(Provider provider, String state) {
    Map<String, String> params = new LinkedHashMap<>();
    stateNode(provider, state)
        .path("params")
        .fields()
        .forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
    return params;
  }

  /** qits-projects' {@link #operation(Provider, String, String)}. */
  public static Operation operation(String state, String operationId) {
    return operation(PROJECTS, state, operationId);
  }

  /** The index entry for one (state, operation); fails naming both when the index has none. */
  public static Operation operation(Provider provider, String state, String operationId) {
    JsonNode stateNode = stateNode(provider, state);
    for (JsonNode op : stateNode.path("operations")) {
      if (operationId.equals(op.path("operationId").asText())) {
        JsonNode frozen = op.path("frozen");
        JsonNode filtered = frozen.path("listFilteredTo");
        return new Operation(
            provider,
            state,
            params(provider, state),
            operationId,
            op.path("method").asText(),
            op.path("path").asText(),
            op.path("status").asInt(),
            op.path("file").asText(),
            strings(frozen.path("ids")),
            strings(frozen.path("instants")),
            strings(frozen.path("strings")),
            filtered.isTextual() ? filtered.asText() : null);
      }
    }
    throw new IllegalArgumentException(
        provider.application() + "'s golden masters record no operation " + operationId
            + " in state '" + state + "'");
  }

  /** qits-projects' {@link #body(Provider, String, String)}. */
  public static String body(String state, String operationId) {
    return body(PROJECTS, state, operationId);
  }

  /** The recorded JSON for one (state, operation), byte for byte as the jar carries it. */
  @SuppressWarnings("deprecation") // new URL(context, spec) is the one resolver jar: URLs have
  public static String body(Provider provider, String state, String operationId) {
    Operation op = operation(provider, state, operationId);
    try {
      return read(new URL(index(provider).location(), op.file()), op.file());
    } catch (MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** qits-projects' {@link #json(Provider, String, String)}. */
  public static JsonNode json(String state, String operationId) {
    return json(PROJECTS, state, operationId);
  }

  /** {@link #body}, parsed — a fresh tree each call, so a caller may edit it. */
  public static JsonNode json(Provider provider, String state, String operationId) {
    try {
      return MAPPER.readTree(body(provider, state, operationId));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // --- the pact ---------------------------------------------------------------------------------

  /**
   * What made this service make the call — the {@code qits-trigger} reference every interaction
   * carries. One of four kinds, each naming the entry point by the key the kind uses:
   *
   * <ul>
   *   <li>{@code operation} — {@code operationId}, the consumer's own openapi operationId;
   *   <li>{@code event} — {@code event}, the bus event type that enters the path;
   *   <li>{@code schedule} — {@code schedule}, the scheduled method or job;
   *   <li>{@code ui} — {@code interaction}, a UI interaction of {@code app}.
   * </ul>
   */
  public record Trigger(String kind, String app, String key, String value) {

    public Trigger {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(app, "app");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
    }

    public static Trigger operation(String operationId) {
      return new Trigger("operation", CONSUMER, "operationId", operationId);
    }

    public static Trigger event(String eventType) {
      return new Trigger("event", CONSUMER, "event", eventType);
    }

    public static Trigger schedule(String schedule) {
      return new Trigger("schedule", CONSUMER, "schedule", schedule);
    }

    public static Trigger ui(String app, String interaction) {
      return new Trigger("ui", app, "interaction", interaction);
    }

    /** The {@code qits-trigger} group, all values strings, in a fixed key order. */
    public Map<String, String> reference() {
      Map<String, String> ref = new LinkedHashMap<>();
      ref.put("kind", kind);
      ref.put("app", app);
      ref.put(key, value);
      return ref;
    }
  }

  /** The interaction's description: the trigger first, so (description, state) stays unique. */
  public static String description(String operationId, Trigger trigger) {
    return trigger.value() + ": " + operationId;
  }

  /**
   * Add the V4 HTTP interaction for one recorded (state, operation) of {@code provider}, reached
   * from {@code trigger}.
   *
   * <p>{@code given(state, params)}; the request path from the index template, each {@code
   * {param}} as a provider-state expression with the frozen example (a query in the template
   * becomes exact query parameters); {@code requestBody} when the consumer sends one; the status
   * exact; and the per-interaction {@code comments.references} — {@code qits-call} (the provider
   * operation) and {@code qits-trigger}.
   *
   * <p><b>The response body is the recording cut down to {@code consumes}</b>, the JSON paths this
   * service's code reads ({@code $.repository.id}, {@code $.entries[*].repository.name}, {@code
   * $[*].clientId}), with the matchers the class javadoc lists. An empty {@code consumes} is a
   * status-only interaction: no body and no content type, because nothing here reads either.
   *
   * <p>{@code requestBody} is this consumer's OWN expectation, never the recording's request: what
   * this service sends is its half of the contract.
   *
   * @throws NullPointerException when {@code trigger} is null: an interaction nobody can attribute
   *     to an entry point is exactly what the references exist to prevent
   */
  public static PactBuilder interaction(
      PactBuilder builder,
      Provider provider,
      String state,
      String operationId,
      Trigger trigger,
      DslPart requestBody,
      List<String> consumes) {
    Objects.requireNonNull(trigger, "trigger: every interaction names the entry point that makes it");
    Objects.requireNonNull(consumes, "consumes: name what the consumer reads, or List.of()");
    Operation op = operation(provider, state, operationId);
    DslPart body = consumes.isEmpty() ? null : responseBody(op, consumes);
    String[] pathAndQuery = op.path().split("\\?", 2);
    Operation pathOnly =
        new Operation(
            op.provider(), op.state(), op.params(), op.operationId(), op.method(), pathAndQuery[0],
            op.status(), op.file(), op.ids(), op.instants(), op.strings(), op.listFilteredTo());
    Map<String, Object> references = new LinkedHashMap<>();
    Map<String, String> call = new LinkedHashMap<>();
    call.put("app", provider.repository());
    call.put("operationId", operationId);
    references.put("qits-call", call);
    references.put("qits-trigger", trigger.reference());
    return builder.expectsToReceiveHttpInteraction(
        description(operationId, trigger),
        http -> {
          http.state(state, new LinkedHashMap<String, Object>(op.params()));
          http.withRequest(
              request -> {
                request
                    .method(op.method())
                    .path(
                        Matchers.fromProviderState(
                            pathOnly.expressionPath(), pathOnly.examplePath()));
                if (pathAndQuery.length > 1) {
                  for (String pair : pathAndQuery[1].split("&")) {
                    String[] kv = pair.split("=", 2);
                    request.queryParameter(kv[0], kv.length > 1 ? kv[1] : "");
                  }
                }
                return requestBody == null ? request : request.body(requestBody);
              });
          http.willRespondWith(
              response -> {
                response.status(op.status());
                return body == null
                    ? response
                    : response
                        .header(
                            "Content-Type",
                            Matchers.regexp("application/json.*", "application/json"))
                        .body(body);
              });
          // pact-jvm 4.6's DSL has no setter for an arbitrary comment group (only `comment(text)`
          // and the test name), but the V4 model's comments map is mutable and written verbatim.
          http.getInteraction().getComments().put("references", Json.toJson(references));
          return http;
        });
  }

  /** {@link #interaction(PactBuilder, Provider, String, String, Trigger, DslPart, List)} for a GET. */
  public static PactBuilder interaction(
      PactBuilder builder,
      Provider provider,
      String state,
      String operationId,
      Trigger trigger,
      List<String> consumes) {
    return interaction(builder, provider, state, operationId, trigger, null, consumes);
  }

  /** The recorded body cut to {@code consumes}, with the index's matchers. */
  static DslPart responseBody(Operation op, List<String> consumes) {
    JsonNode recorded = prune(json(op.provider(), op.state(), op.operationId()), consumes, op);
    if (recorded.isArray()) {
      return rootArray(Shape.of(recorded), op);
    }
    if (!recorded.isObject()) {
      throw new IllegalStateException(
          "golden master " + op.state() + "/" + op.operationId()
              + ": only an object or array body is supported, got " + recorded.getNodeType());
    }
    PactDslJsonBody root = new PactDslJsonBody();
    fillObject(root, Shape.of(recorded), "$", op);
    return root;
  }

  /** A body that is an array of objects at the root, like qits-idp's listings. */
  private static DslPart rootArray(Shape array, Operation op) {
    int n = array.length;
    if (n == 0) {
      return new PactDslJsonArray();
    }
    if (array.element.kind != Shape.Kind.OBJECT) {
      throw unsupported(op, "$[*]", "a root array of " + array.element.kind);
    }
    PactDslJsonBody template =
        "$".equals(op.listFilteredTo())
            ? PactDslJsonArray.arrayMinLike(n, n)
            : PactDslJsonArray.arrayMinMaxLike(n, n, n);
    fillObject(template, array.element, "$[*]", op);
    return template.closeObject();
  }

  /**
   * {@code json}, an object this service sends, as a request body matched exactly. For a body the
   * consumer composes itself and captures off its own wire, where every value is its own.
   */
  public static DslPart exactBody(JsonNode json) {
    if (!json.isObject()) {
      throw new IllegalArgumentException("an exact request body is an object, got " + json.getNodeType());
    }
    PactDslJsonBody body = new PactDslJsonBody();
    exactFields(body, json);
    return body;
  }

  private static void exactFields(PactDslJsonBody target, JsonNode object) {
    object
        .fields()
        .forEachRemaining(
            field -> {
              String name = field.getKey();
              JsonNode value = field.getValue();
              if (value.isNull()) {
                target.nullValue(name);
              } else if (value.isTextual()) {
                target.stringValue(name, value.asText());
              } else if (value.isBoolean()) {
                target.booleanValue(name, value.asBoolean());
              } else if (value.isNumber()) {
                target.numberValue(name, value.numberValue());
              } else if (value.isObject()) {
                PactDslJsonBody nested = target.object(name);
                exactFields(nested, value);
                nested.closeObject();
              } else {
                PactDslJsonArray array = target.array(name);
                exactElements(array, value);
                array.closeArray();
              }
            });
  }

  private static void exactElements(PactDslJsonArray target, JsonNode array) {
    for (JsonNode value : array) {
      if (value.isNull()) {
        target.nullValue();
      } else if (value.isTextual()) {
        target.stringValue(value.asText());
      } else if (value.isBoolean()) {
        target.booleanValue(value.asBoolean());
      } else if (value.isNumber()) {
        target.numberValue(value.numberValue());
      } else if (value.isObject()) {
        PactDslJsonBody nested = target.object();
        exactFields(nested, value);
        nested.closeObject();
      } else {
        PactDslJsonArray nested = target.array();
        exactElements(nested, value);
        nested.closeArray();
      }
    }
  }

  // --- cutting the recording to what is read ----------------------------------------------------

  private static final Pattern SEGMENT = Pattern.compile("\\.([A-Za-z0-9_-]+)|\\[\\*]");

  /**
   * The recording holding only the {@code consumes} paths and their ancestors. A path that names an
   * object or an array keeps the whole of it. A path the recording does not hold fails, naming it:
   * a consumer cannot read what the provider never answered.
   */
  static JsonNode prune(JsonNode recorded, List<String> consumes, Operation op) {
    JsonNode out = recorded.isArray() ? MAPPER.createArrayNode() : MAPPER.createObjectNode();
    for (String path : consumes) {
      if ("$".equals(path)) {
        continue; // A_JSON_BODY: the container, and nothing in it
      }
      copy(recorded, out, segments(path), 0, path, op);
    }
    return out;
  }

  private static List<String> segments(String path) {
    if (!path.startsWith("$")) {
      throw new IllegalArgumentException("a consumed path starts with $: " + path);
    }
    List<String> out = new ArrayList<>();
    Matcher m = SEGMENT.matcher(path);
    int at = 1;
    while (m.find()) {
      if (m.start() != at) {
        throw new IllegalArgumentException("cannot read consumed path " + path);
      }
      out.add(m.group(1) == null ? "[*]" : m.group(1));
      at = m.end();
    }
    if (at != path.length()) {
      throw new IllegalArgumentException("cannot read consumed path " + path);
    }
    return out;
  }

  private static void copy(
      JsonNode src, JsonNode dst, List<String> segs, int i, String path, Operation op) {
    String seg = segs.get(i);
    boolean last = i == segs.size() - 1;
    if ("[*]".equals(seg)) {
      if (!src.isArray() || !(dst instanceof ArrayNode out)) {
        throw notRecorded(op, path);
      }
      for (int e = 0; e < src.size(); e++) {
        JsonNode element = src.get(e);
        if (last) {
          if (out.size() <= e) {
            out.add(element.deepCopy());
          } else {
            out.set(e, element.deepCopy());
          }
          continue;
        }
        if (out.size() <= e) {
          out.add(container(element));
        }
        if (element.isContainerNode()) {
          copy(element, out.get(e), segs, i + 1, path, op);
        }
      }
      return;
    }
    if (!src.isObject() || !src.has(seg) || !(dst instanceof ObjectNode out)) {
      throw notRecorded(op, path);
    }
    JsonNode child = src.get(seg);
    if (last || !child.isContainerNode()) {
      out.set(seg, child.deepCopy());
      return;
    }
    if (!out.has(seg)) {
      out.set(seg, container(child));
    }
    copy(child, out.get(seg), segs, i + 1, path, op);
  }

  private static JsonNode container(JsonNode like) {
    if (like.isArray()) {
      return MAPPER.createArrayNode();
    }
    if (like.isObject()) {
      return MAPPER.createObjectNode();
    }
    return like.deepCopy();
  }

  private static IllegalStateException notRecorded(Operation op, String path) {
    return new IllegalStateException(
        "golden master " + op.state() + "/" + op.operationId() + " holds nothing at consumed path "
            + path);
  }

  // --- the body ---------------------------------------------------------------------------------

  private static void fillObject(PactDslJsonBody target, Shape shape, String path, Operation op) {
    for (Map.Entry<String, Shape> field : shape.fields.entrySet()) {
      String name = field.getKey();
      Shape child = field.getValue();
      String childPath = path + "." + name;
      switch (child.kind) {
        case NULL -> target.nullValue(name);
        case LEAF -> leaf(target, name, child, childPath, op);
        case OBJECT -> {
          if (child.nullable) {
            throw unsupported(op, childPath, "an object that is null in some elements");
          }
          PactDslJsonBody nested = target.object(name);
          fillObject(nested, child, childPath, op);
          nested.closeObject();
        }
        case ARRAY -> array(target, name, child, childPath, op);
      }
    }
  }

  private static void leaf(
      PactDslJsonBody target, String name, Shape leaf, String path, Operation op) {
    JsonNode example = leaf.example;
    if (op.ids().contains(path)) {
      requireText(example, path, op, "ids");
      if (leaf.nullable) {
        target.or(name, example.asText(), new RegexMatcher(UUID_REGEX, example.asText()), NullMatcher.INSTANCE);
      } else {
        target.uuid(name, example.asText());
      }
    } else if (op.instants().contains(path)) {
      requireText(example, path, op, "instants");
      if (leaf.nullable) {
        target.or(name, example.asText(), new RegexMatcher(ISO_INSTANT, example.asText()), NullMatcher.INSTANCE);
      } else {
        target.stringMatcher(name, ISO_INSTANT, example.asText());
      }
    } else if (leaf.nullable) {
      // frozen.strings or an ordinary leaf: type match, widened to null where a sibling had null.
      target.or(name, scalar(example), TypeMatcher.INSTANCE, NullMatcher.INSTANCE);
    } else if (example.isTextual()) {
      target.stringType(name, example.asText());
    } else if (example.isNumber()) {
      target.numberType(name, example.numberValue());
    } else if (example.isBoolean()) {
      target.booleanType(name, example.asBoolean());
    } else {
      throw unsupported(op, path, "a " + example.getNodeType() + " leaf");
    }
  }

  private static void array(
      PactDslJsonBody target, String name, Shape array, String path, Operation op) {
    if (array.nullable) {
      throw unsupported(op, path, "an array that is null in some elements");
    }
    boolean filtered = path.equals(op.listFilteredTo());
    int n = array.length;
    if (n == 0) {
      // Nothing to build a template from: the recording says "empty", and an empty array with no
      // rule is compared as exactly that.
      target.array(name).closeArray();
      return;
    }
    Shape element = array.element;
    String elementPath = path + "[*]";
    switch (element.kind) {
      case OBJECT -> {
        PactDslJsonBody template =
            filtered ? target.minArrayLike(name, n, n) : target.minMaxArrayLike(name, n, n, n);
        fillObject(template, element, elementPath, op);
        DslPart closed = template.closeObject();
        ((PactDslJsonArray) closed).closeArray();
      }
      case LEAF -> {
        PactDslJsonRootValue value = rootLeaf(element, elementPath, op);
        if (filtered) {
          target.minArrayLike(name, n, value, n);
        } else {
          target.minMaxArrayLike(name, n, n, value, n);
        }
      }
      default -> throw unsupported(op, elementPath, "an array of " + element.kind);
    }
  }

  private static PactDslJsonRootValue rootLeaf(Shape leaf, String path, Operation op) {
    if (leaf.nullable) {
      throw unsupported(op, path, "an array holding nulls");
    }
    JsonNode example = leaf.example;
    if (op.ids().contains(path)) {
      requireText(example, path, op, "ids");
      return PactDslJsonRootValue.uuid(example.asText());
    }
    if (op.instants().contains(path)) {
      requireText(example, path, op, "instants");
      return PactDslJsonRootValue.stringMatcher(ISO_INSTANT, example.asText());
    }
    if (example.isTextual()) {
      return PactDslJsonRootValue.stringType(example.asText());
    }
    if (example.isNumber()) {
      return PactDslJsonRootValue.numberType(example.numberValue());
    }
    if (example.isBoolean()) {
      return PactDslJsonRootValue.booleanType(example.asBoolean());
    }
    throw unsupported(op, path, "a " + example.getNodeType() + " array element");
  }

  private static Object scalar(JsonNode example) {
    if (example.isTextual()) {
      return example.asText();
    }
    if (example.isNumber()) {
      return example.numberValue();
    }
    if (example.isBoolean()) {
      return example.asBoolean();
    }
    throw new IllegalStateException("not a scalar: " + example);
  }

  private static void requireText(JsonNode example, String path, Operation op, String list) {
    if (!example.isTextual()) {
      throw new IllegalStateException(
          "golden master " + op.state() + "/" + op.operationId() + ": frozen." + list + " names "
              + path + ", which holds " + example.getNodeType() + ", not a string");
    }
  }

  private static IllegalStateException unsupported(Operation op, String path, String what) {
    return new IllegalStateException(
        "golden master " + op.state() + "/" + op.operationId() + ": " + path + " is " + what
            + ", which GoldenMasters cannot express as a pact matcher yet");
  }

  /**
   * The structure of a recorded value, with an array's elements MERGED into one template: field
   * union, a leaf's first non-null example, and {@code nullable} wherever any element held null
   * (or lacked the field). Used for structure and examples only — which matcher a leaf gets is the
   * index's decision, by path.
   */
  private static final class Shape {
    enum Kind {
      NULL,
      LEAF,
      OBJECT,
      ARRAY
    }

    Kind kind;
    boolean nullable;
    JsonNode example;
    final LinkedHashMap<String, Shape> fields = new LinkedHashMap<>();
    Shape element;
    int length;

    static Shape of(JsonNode node) {
      Shape shape = new Shape();
      if (node == null || node.isNull() || node.isMissingNode()) {
        shape.kind = Kind.NULL;
        shape.nullable = true;
      } else if (node.isObject()) {
        shape.kind = Kind.OBJECT;
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
          Map.Entry<String, JsonNode> e = it.next();
          shape.fields.put(e.getKey(), of(e.getValue()));
        }
      } else if (node.isArray()) {
        shape.kind = Kind.ARRAY;
        shape.length = node.size();
        for (JsonNode e : node) {
          shape.element = shape.element == null ? of(e) : merge(shape.element, of(e));
        }
      } else {
        shape.kind = Kind.LEAF;
        shape.example = node;
      }
      return shape;
    }

    static Shape merge(Shape a, Shape b) {
      if (a.kind == Kind.NULL) {
        b.nullable = true;
        return b;
      }
      if (b.kind == Kind.NULL) {
        a.nullable = true;
        return a;
      }
      if (a.kind != b.kind) {
        throw new IllegalStateException(
            "golden master array elements disagree: " + a.kind + " and " + b.kind);
      }
      a.nullable |= b.nullable;
      switch (a.kind) {
        case OBJECT -> {
          List<String> keys = new ArrayList<>(a.fields.keySet());
          for (String key : b.fields.keySet()) {
            if (!keys.contains(key)) {
              keys.add(key);
            }
          }
          LinkedHashMap<String, Shape> merged = new LinkedHashMap<>();
          for (String key : keys) {
            Shape left = a.fields.get(key);
            Shape right = b.fields.get(key);
            merged.put(
                key,
                left == null ? merge(of(null), right) : right == null ? merge(left, of(null)) : merge(left, right));
          }
          a.fields.clear();
          a.fields.putAll(merged);
        }
        case ARRAY -> {
          a.length = Math.min(a.length, b.length);
          a.element =
              a.element == null ? b.element : b.element == null ? a.element : merge(a.element, b.element);
        }
        default -> {
          // LEAF: keep a's example; the matcher is a type match, so one example stands for all.
        }
      }
      return a;
    }
  }

  // --- reading the jar --------------------------------------------------------------------------

  /**
   * The index whose {@code provider} is {@code provider}'s application. Every provider's jar puts
   * its tree at the same root, so the classpath may hold several {@code index.json}; the field is
   * what tells them apart, never the order of the classpath.
   */
  private static Index index(Provider provider) {
    return INDEXES.computeIfAbsent(provider.application(), app -> load(provider));
  }

  private static Index load(Provider provider) {
    List<String> seen = new ArrayList<>();
    for (URL url : resources(ROOT + "index.json")) {
      JsonNode loaded;
      try {
        loaded = MAPPER.readTree(read(url, ROOT + "index.json"));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      String owner = loaded.path("provider").asText();
      seen.add(owner);
      if (!provider.application().equals(owner)) {
        continue;
      }
      if (loaded.path("formatVersion").asInt() != 1) {
        throw new IllegalStateException(
            owner + "'s golden-masters/index.json is formatVersion " + loaded.path("formatVersion")
                + "; GoldenMasters reads formatVersion 1");
      }
      return new Index(loaded, url);
    }
    throw new IllegalStateException(
        "no golden-masters/index.json of " + provider.application() + " on the test classpath (found "
            + seen + ") — is eu.wohlben.qits:" + provider.application()
            + "-golden-masters a test dependency of this module?");
  }

  private static JsonNode stateNode(Provider provider, String state) {
    for (JsonNode node : index(provider).root().path("states")) {
      if (state.equals(node.path("name").asText())) {
        return node;
      }
    }
    throw new IllegalArgumentException(
        provider.application() + "'s golden masters record no state '" + state + "'");
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> out = new java.util.LinkedHashSet<>();
    for (JsonNode e : array) {
      out.add(e.asText());
    }
    return Set.copyOf(out);
  }

  /** Every copy of {@code name} on the classpath. */
  private static List<URL> resources(String name) {
    // This class's own loader first: StoryPeers answers on stub threads whose context loader is
    // whatever thread started the stub, and the jar is on this class's test classpath regardless.
    List<URL> out = new ArrayList<>();
    for (ClassLoader loader :
        List.of(
            Objects.requireNonNullElse(
                GoldenMasters.class.getClassLoader(), ClassLoader.getSystemClassLoader()),
            Objects.requireNonNullElse(
                Thread.currentThread().getContextClassLoader(), ClassLoader.getSystemClassLoader()))) {
      try {
        Enumeration<URL> found = loader.getResources(name);
        while (found.hasMoreElements()) {
          URL url = found.nextElement();
          if (out.stream().noneMatch(u -> u.toString().equals(url.toString()))) {
            out.add(url);
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      if (!out.isEmpty()) {
        return out;
      }
    }
    return out;
  }

  private static String read(URL url, String name) {
    try (InputStream in = url.openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + name + " at " + url, e);
    }
  }
}
