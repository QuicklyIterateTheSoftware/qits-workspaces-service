package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.EditorLifecycle;
import eu.wohlben.qits.workspaces.control.EditorService;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The editor's door, and there is only one of it — for the one editor there is.
 *
 * <p><b>Find-or-create, and no status read beside it.</b> {@code POST /editor/ensure} is the whole
 * readiness protocol: a fresh editor answers 201, an existing one 200, and both carry the same body.
 * A caller polls this and nothing else, which is what lets a reader who reloads mid-start rejoin the
 * editor already coming up instead of asking for a second one. The pairing is {@code
 * TerminalController.open}'s — {@code fresh} becomes the status and never a field.
 *
 * <p><b>No parameters and an empty body</b>, because there is nothing left to say. It used to carry
 * {@code ?repositoryId=<wrapper>}: there was one editor per project and it rode that project's
 * wrapper repository's main workspace, so the door needed to be told which one and refused a
 * repository that was not a wrapper (400) or did not exist (404). One editor for the platform takes
 * all three away — the parameter, and both refusals with it. Two callers on two different projects'
 * pages now post the same request and reach the same container, which is the point.
 *
 * <p>Who may ask is this class's {@code @RolesAllowed("qits:admin")}, the standing rule {@code
 * WorkspaceController} carries: asking for a workspace container already requires the platform admin
 * role, and an editor is a workspace container.
 */
@Path("/editor")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
// The door answers through a bare JAX-RS Response (fresh() decides 201 vs 200), so nothing tells
// the native-image build the record is serialized — CaptureResource's measured failure, one door
// over: the JVM suite green, the binary 500ing every ensure with "No serializer found".
// NativeImageContractTest holds this line in place.
@RegisterForReflection(targets = EditorController.EditorSessionResponse.class)
public class EditorController {

  @Inject EditorService editors;

  /**
   * The answer, as a BARE object rather than in this context's usual {@code {thing: …}} envelope.
   *
   * <p>That is deliberate and it is the one place here that departs: the client polls this every two
   * seconds and reads four scalars off it, so an envelope would be a wrapper around a wrapper. The
   * routes that answer a {@code WorkspaceDto} keep theirs — a named payload is what makes a
   * collection and an item one shape.
   *
   * @param workspaceId the editor workspace's row id as a String — the identity {@code
   *     /workspaces/{id}/stop-container} and {@code /recreate-container} address, which is why a
   *     branch label would not do
   * @param containerStatus the workspace's runtime status, as this service last recorded it
   * @param editorState what the daemon last reported: {@code STARTING}, {@code RUNNING}, {@code
   *     ENDED}, or null when nothing has been reported
   * @param editorReady the readiness, and the only field to act on — the container is running and the
   *     editor inside it says it is serving
   */
  public record EditorSessionResponse(
      String workspaceId, String containerStatus, String editorState, boolean editorReady) {

    static EditorSessionResponse of(EditorService.EditorSession session) {
      EditorLifecycle state = session.editorState();
      return new EditorSessionResponse(
          session.workspaceId(),
          session.containerStatus(),
          state == null ? null : state.name(),
          session.editorReady());
    }
  }

  /**
   * Make sure there is an editor, and say whether it answers yet.
   *
   * <p>Idempotent: the singleton editor row is written or handed back, and the container is asked
   * for only when asking could change something — see {@link EditorService} for the two reasons it
   * does not ask, and why neither of them is a lock.
   */
  @POST
  @Path("/ensure")
  @APIResponse(responseCode = "201", description = "The editor was started by this call.")
  @APIResponse(responseCode = "200", description = "The editor was already there.")
  public Response ensure() {
    EditorService.EditorSession session = editors.ensure();
    return Response.status(session.fresh() ? 201 : 200)
        .entity(EditorSessionResponse.of(session))
        .build();
  }
}
