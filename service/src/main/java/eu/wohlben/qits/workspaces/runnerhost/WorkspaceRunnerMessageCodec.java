package eu.wohlben.qits.workspaces.runnerhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerCodec;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerMessage;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

/**
 * The bridge between a runner frame and its JSON text — qits-ci's {@code CiRunnerMessageCodec} for
 * this socket. The framework-free codecs do the field mapping: qits-runner-protocol's {@link
 * RunnerCodec} for the ten lifecycle frames, handing every other {@code type} to {@link
 * WorkspacesRunnerCodec}. This class only bolts on Jackson, so the wire is spelled in the protocol
 * jars and never here; the runner does the same with a Vert.x {@code JsonObject}.
 *
 * <p>{@link #decode} lets the codecs' strictness through as an exception — an unknown or missing
 * {@code type} and a missing required field both throw — and {@link WorkspaceRunnerSocket} catches
 * it: a frame this host cannot read costs that frame, never the runner's socket.
 */
@ApplicationScoped
public class WorkspaceRunnerMessageCodec {

  /** Stateless and immutable, so one serves every connection. */
  static final RunnerCodec<WorkspacesRunnerMessage> CODEC =
      new RunnerCodec<>(WorkspacesRunnerProtocol.VOCABULARY, new WorkspacesRunnerCodec());

  @Inject ObjectMapper objectMapper;

  /** Serialize a frame to the JSON text sent over the socket. */
  public String encode(RunnerMessage message) {
    try {
      return objectMapper.writeValueAsString(CODEC.encode(message));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode a workspaces-runner frame", e);
    }
  }

  /** Parse a received JSON text frame. */
  @SuppressWarnings("unchecked")
  public RunnerMessage decode(String json) {
    try {
      return CODEC.decode(objectMapper.readValue(json, Map.class));
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to decode a workspaces-runner frame", e);
    }
  }
}
