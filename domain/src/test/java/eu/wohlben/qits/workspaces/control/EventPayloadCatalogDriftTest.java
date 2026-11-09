package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.workspaces.control.EventPayloadCatalog.Field;
import eu.wohlben.qits.workspaces.control.EventPayloadCatalog.ListOf;
import eu.wohlben.qits.workspaces.control.EventPayloadCatalog.Obj;
import eu.wohlben.qits.workspaces.control.EventPayloadCatalog.Scalar;
import eu.wohlben.qits.workspaces.events.AgentRemoved;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The catalog's entries for this repository's own events (qits-1849) are the records in {@code
 * workspaces-events}, read the way {@code CanonicalJson} writes them: every record component but
 * {@code QitsEvent}'s own ({@code eventId}, {@code occurredAt}), a record as an object, a {@code
 * List} as a list, anything else a single value. Every {@code QitsEvent} record in that package is
 * found on the classpath, so a new event without an entry fails here. Every other entry is
 * transcribed by hand from another publisher and is not pinned by this test; that includes {@code
 * TargetLeaseChanged}, whose qits-workspaces copy lives in {@code service}'s targethost package, not
 * here.
 */
class EventPayloadCatalogDriftTest {

  private static final String PACKAGE = AgentRemoved.class.getPackageName();

  private static final Set<String> QITS_EVENT_METHODS = Set.of("eventId", "occurredAt", "signature", "name");

  @Test
  void everyLifecycleEventHasAnEntryMatchingItsRecord() throws Exception {
    List<Class<?>> events = lifecycleEvents();
    assertTrue(events.size() >= 20 && events.contains(AgentRemoved.class), "found only " + events);
    EventPayloadCatalog catalog = EventPayloadCatalog.shipped();
    for (Class<?> event : events) {
      assertEquals(
          QitsEvent.class,
          event.getMethod("signature").getDeclaringClass(),
          event.getSimpleName() + " overrides signature(); its wire name is no longer its class name");
      Obj entry =
          catalog
              .payload(event.getSimpleName())
              .orElseThrow(() -> new AssertionError(event.getSimpleName() + " has no entry in " + EventPayloadCatalog.RESOURCE));
      assertEquals(fieldsOf(event, true), entry, event.getSimpleName() + "'s entry differs from its record");
    }
  }

  @Test
  void theEnvelopeFieldsAreTheOnesQitsEventDeclares() {
    for (String field : EventPayloadCatalog.ENVELOPE_FIELDS) {
      assertTrue(QITS_EVENT_METHODS.contains(field), field);
    }
    for (String name : EventPayloadCatalog.shipped().events()) {
      Obj payload = EventPayloadCatalog.shipped().payload(name).orElseThrow();
      for (String field : EventPayloadCatalog.ENVELOPE_FIELDS) {
        assertTrue(!payload.fields().containsKey(field), name + " lists the envelope's " + field);
      }
      assertNotEquals(Map.of(), payload.fields(), name + " lists no field");
    }
  }

  /** A record's payload shape; at the top level {@code QitsEvent}'s own components are left out. */
  private static Obj fieldsOf(Class<?> record, boolean event) {
    Map<String, Field> fields = new TreeMap<>();
    for (RecordComponent component : record.getRecordComponents()) {
      if (event && QITS_EVENT_METHODS.contains(component.getName())) {
        continue;
      }
      fields.put(component.getName(), shapeOf(component.getGenericType(), record + "." + component.getName()));
    }
    return new Obj(fields);
  }

  private static Field shapeOf(Type type, String where) {
    if (type instanceof ParameterizedType generic) {
      Class<?> raw = (Class<?>) generic.getRawType();
      if (List.class.isAssignableFrom(raw) || Set.class.isAssignableFrom(raw)) {
        return new ListOf(shapeOf(generic.getActualTypeArguments()[0], where + "[]"));
      }
      fail(where + " is a " + type + "; teach this test and the catalog its shape");
    }
    Class<?> raw = (Class<?>) type;
    if (raw.isRecord()) {
      return fieldsOf(raw, false);
    }
    if (raw.isPrimitive()
        || raw.isEnum()
        || Number.class.isAssignableFrom(raw)
        || raw == String.class
        || raw == Boolean.class
        || raw == UUID.class
        || raw == Instant.class) {
      return new Scalar();
    }
    if (Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw) || raw.isArray()) {
      fail(where + " is a raw " + raw + "; teach this test and the catalog its shape");
    }
    return fail(where + " is a " + raw + ", which this test cannot read as a payload shape");
  }

  /** Every concrete {@code QitsEvent} record in the events package, in a directory or a jar. */
  private static List<Class<?>> lifecycleEvents() throws IOException, URISyntaxException, ClassNotFoundException {
    String folder = PACKAGE.replace('.', '/');
    Set<String> names = new TreeSet<>();
    Enumeration<URL> roots = AgentRemoved.class.getClassLoader().getResources(folder);
    while (roots.hasMoreElements()) {
      URL root = roots.nextElement();
      if (root.getProtocol().equals("file")) {
        try (Stream<Path> files = Files.list(Path.of(root.toURI()))) {
          files.map(p -> p.getFileName().toString()).forEach(names::add);
        }
      } else if (root.getProtocol().equals("jar")) {
        JarURLConnection connection = (JarURLConnection) root.openConnection();
        connection.setUseCaches(false);
        try (JarFile jar = connection.getJarFile()) {
          jar.stream()
              .map(e -> e.getName())
              .filter(n -> n.startsWith(folder + "/") && n.indexOf('/', folder.length() + 1) < 0)
              .map(n -> n.substring(folder.length() + 1))
              .forEach(names::add);
        }
      }
    }
    List<Class<?>> events = new ArrayList<>();
    for (String file : names) {
      if (!file.endsWith(".class") || file.contains("$")) {
        continue;
      }
      Class<?> type = Class.forName(PACKAGE + "." + file.substring(0, file.length() - ".class".length()));
      if (type.isRecord() && QitsEvent.class.isAssignableFrom(type)) {
        events.add(type);
      }
    }
    return events;
  }
}
