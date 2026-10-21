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
import eu.wohlben.qits.workspaces.runnerhost.RunnerFramesContract;
import eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerSocket;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
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
 *
 * <p><b>The receiving side (qits-950, A3, for A2).</b> Every frame type {@link WorkspaceRunnerSocket}
 * dispatches — the case labels of {@code onMessage}'s pattern switch, read off its bytecode (the
 * {@code SwitchBootstraps.typeSwitch} call's labels), never a hand list — needs a row in {@link
 * RunnerFramesContract}, the message pact of the frames the runner sends; a frame the socket handles
 * with no row fails here by name. So does a row for a frame the socket no longer handles, and a frame
 * the runner records (its pinned golden masters) that no row receives.
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

  @Test
  void everyFrameTheServiceHandlesHasAContractRow() throws Exception {
    Set<String> handled = framesHandled();
    Set<String> rows = contractFrames();
    List<String> missing = new ArrayList<>();
    for (String type : handled) {
      if (!rows.contains(type)) {
        missing.add(
            type
                + " is dispatched by WorkspaceRunnerSocket.onMessage and has no row in"
                + " RunnerFramesContract — add one asserting what the service does with it");
      }
    }
    for (String type : rows) {
      if (!handled.contains(type)) {
        missing.add(
            "RunnerFramesContract has a row for " + type + ", which WorkspaceRunnerSocket no longer"
                + " dispatches");
      }
    }
    assertTrue(missing.isEmpty(), String.join("\n", missing));
  }

  @Test
  void everyFrameTheRunnerRecordsHasAContractRow() {
    Set<String> rows = contractFrames();
    Set<String> recorded = new TreeSet<>();
    GoldenMasters.recordedFrames(GoldenMasters.WORKSPACES_RUNNER)
        .forEach(
            entry ->
                recorded.add(entry.getValue().substring(GoldenMasters.FRAME_PREFIX.length())));
    assertTrue(!recorded.isEmpty(), "the runner's pinned golden masters record no frame");
    Set<String> unreceived = new TreeSet<>(recorded);
    unreceived.removeAll(rows);
    assertTrue(
        unreceived.isEmpty(),
        "qits-workspaces-runner-daemon records frames no RunnerFramesContract row receives: "
            + unreceived);
  }

  /** The frame types {@link RunnerFramesContract}'s rows receive. */
  private static Set<String> contractFrames() {
    Set<String> rows = new TreeSet<>();
    RunnerFramesContract.TABLE.rows()
        .forEach(row -> rows.add(row.operationId().substring(GoldenMasters.FRAME_PREFIX.length())));
    return rows;
  }

  /**
   * The frame types {@link WorkspaceRunnerSocket#onMessage} dispatches: the class labels of its
   * pattern switch, which javac hands {@code SwitchBootstraps.typeSwitch} as bootstrap arguments.
   */
  static Set<String> framesHandled() throws IOException, IllegalAccessException {
    Map<String, Class<?>> frames = new TreeMap<>();
    for (Class<?> sealed : List.of(WorkspacesRunnerMessage.class, LifecycleMessage.class)) {
      for (Class<?> frame : sealed.getPermittedSubclasses()) {
        frames.put(frame.descriptorString(), frame);
      }
    }
    Set<String> declaredTypes = declaredTypes();
    ClassModel socket;
    try (InputStream in =
        WorkspaceRunnerSocket.class.getResourceAsStream("WorkspaceRunnerSocket.class")) {
      socket = ClassFile.of().parse(in.readAllBytes());
    }
    Set<String> handled = new TreeSet<>();
    int switches = 0;
    for (MethodModel method : socket.methods()) {
      if (!method.methodName().equalsString("onMessage")) {
        continue;
      }
      CodeModel code = method.code().orElseThrow();
      for (java.lang.classfile.CodeElement element : code) {
        if (element instanceof InvokeDynamicInstruction indy
            && indy.bootstrapMethod().methodName().equals("typeSwitch")) {
          switches++;
          for (ConstantDesc label : indy.bootstrapArgs()) {
            if (label instanceof ClassDesc type && frames.containsKey(type.descriptorString())) {
              handled.add(typeOf(frames.get(type.descriptorString()), declaredTypes));
            }
          }
        }
      }
    }
    assertTrue(switches > 0, "no pattern switch in WorkspaceRunnerSocket.onMessage: is it still the dispatch?");
    assertTrue(!handled.isEmpty(), "WorkspaceRunnerSocket.onMessage dispatches no frame: is the scan reading it?");
    return handled;
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
