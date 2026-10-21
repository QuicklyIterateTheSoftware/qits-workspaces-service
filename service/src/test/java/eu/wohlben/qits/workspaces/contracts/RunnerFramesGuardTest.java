package eu.wohlben.qits.workspaces.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.runner.protocol.LifecycleMessage;
import eu.wohlben.qits.runner.protocol.RunnerWire;
import eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerRegistry;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenFiles;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerMessage;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Every frame this service can send a workspace runner has a golden master</b> (qits-950, A3,
 * the sending side). The frames are read off the code, never listed by hand: every class of the
 * sealed {@link WorkspacesRunnerMessage} set and of qits-runner-protocol's {@link
 * LifecycleMessage} set that this module's compiled main classes construct — a {@code new}, or a
 * static factory of the frame's own class answering one ({@code Retire.deleted}). The protocol
 * marks no direction on a class, so what this service builds is what it can send; the frames it
 * only receives ({@code hello}, {@code inventory}, …) are never built here.
 *
 * <p>A frame built and recorded under no {@code frame:<type>} operation of the committed {@code
 * golden-masters/index.json} fails, naming the frame and the class that builds it — add its row to
 * {@link RunnerFrames#FRAMES} and record it. So does a recorded frame nothing here builds any more.
 *
 * <p>A frame's wire {@code type} is its record's simple name with a lower-case first letter, and
 * the guard holds that to the protocol's own type constants ({@link WorkspacesRunnerProtocol.Type},
 * {@link RunnerWire.Type}), so a frame that broke the convention fails here rather than slipping
 * past under a name nobody records.
 */
class RunnerFramesGuardTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void everyFrameTheServiceSendsIsRecorded() throws Exception {
    Map<String, Set<String>> sent = framesBuilt();
    Set<String> recorded = recordedFrames();
    List<String> missing = new ArrayList<>();
    sent.forEach(
        (type, builders) -> {
          if (!recorded.contains(type)) {
            missing.add(
                type
                    + " (built in "
                    + String.join(", ", builders)
                    + ") has no frame:"
                    + type
                    + " golden master — add it to RunnerFrames.FRAMES and record it with"
                    + " -Dgolden.update=true");
          }
        });
    for (String type : recorded) {
      if (!sent.containsKey(type)) {
        missing.add(
            "frame:"
                + type
                + " is recorded, and nothing in this service builds a "
                + type
                + " any more");
      }
    }
    assertTrue(missing.isEmpty(), String.join("\n", missing));
  }

  @Test
  void theTableIsWhatTheIndexRecords() throws Exception {
    Set<String> table = new TreeSet<>();
    RunnerFrames.FRAMES.forEach(frame -> table.add(frame.type()));
    assertEquals(recordedFrames(), table, "RunnerFrames.FRAMES and golden-masters/index.json");
  }

  /** Each frame type this module's main classes build, with the classes that build it. */
  static Map<String, Set<String>> framesBuilt() throws IOException, IllegalAccessException {
    Map<String, Class<?>> frames = new TreeMap<>();
    for (Class<?> sealed : List.of(WorkspacesRunnerMessage.class, LifecycleMessage.class)) {
      for (Class<?> frame : sealed.getPermittedSubclasses()) {
        frames.put(frame.getName().replace('.', '/'), frame);
      }
    }
    Set<String> declaredTypes = declaredTypes();
    Map<String, Set<String>> built = new TreeMap<>();
    int classes = 0;
    for (ClassModel model : mainClasses()) {
      classes++;
      String builder = model.thisClass().asInternalName();
      for (MethodModel method : model.methods()) {
        CodeModel code = method.code().orElse(null);
        if (code == null) {
          continue;
        }
        code.forEach(
            element -> {
              String frame = null;
              if (element instanceof NewObjectInstruction created) {
                frame = created.className().asInternalName();
              } else if (element instanceof InvokeInstruction call
                  && call.opcode() == Opcode.INVOKESTATIC
                  && call.typeSymbol()
                      .returnType()
                      .descriptorString()
                      .equals("L" + call.owner().asInternalName() + ";")) {
                frame = call.owner().asInternalName();
              }
              Class<?> type = frame == null ? null : frames.get(frame);
              if (type != null) {
                built
                    .computeIfAbsent(typeOf(type, declaredTypes), k -> new TreeSet<>())
                    .add(builder.substring(builder.lastIndexOf('/') + 1));
              }
            });
      }
    }
    assertTrue(classes > 50, "only " + classes + " main classes: is the scan reading them?");
    assertTrue(!built.isEmpty(), "no runner frame is built anywhere: is the scan reading them?");
    return built;
  }

  /** The frame types the committed index records, as {@code frame:<type>} operations. */
  static Set<String> recordedFrames() throws IOException {
    JsonNode index =
        JSON.readTree(GoldenFiles.repositoryRoot().resolve("golden-masters/index.json").toFile());
    Set<String> recorded = new TreeSet<>();
    for (JsonNode state : index.path("states")) {
      for (JsonNode operation : state.path("operations")) {
        String id = operation.path("operationId").asText();
        if (id.startsWith(RunnerFrames.OPERATION_PREFIX)) {
          recorded.add(id.substring(RunnerFrames.OPERATION_PREFIX.length()));
        }
      }
    }
    return recorded;
  }

  /** The record's simple name, lower-cased first letter — which must be a declared type. */
  private static String typeOf(Class<?> frame, Set<String> declared) {
    String name = frame.getSimpleName();
    String type = name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
    assertTrue(
        declared.contains(type),
        frame.getName() + " would travel as '" + type + "', which no protocol type constant names");
    return type;
  }

  /** Every type constant the two protocol jars declare. */
  private static Set<String> declaredTypes() throws IllegalAccessException {
    Set<String> declared = new HashSet<>(WorkspacesRunnerProtocol.Type.ALL);
    for (Field field : RunnerWire.Type.class.getFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
        declared.add((String) field.get(null));
      }
    }
    return declared;
  }

  private static List<ClassModel> mainClasses() throws IOException {
    Path root;
    try {
      root =
          Path.of(
              WorkspaceRunnerRegistry.class
                  .getProtectionDomain()
                  .getCodeSource()
                  .getLocation()
                  .toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
    List<ClassModel> models = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
        models.add(ClassFile.of().parse(Files.readAllBytes(file)));
      }
    }
    return models;
  }
}
