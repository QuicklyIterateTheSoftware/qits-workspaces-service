package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspacePromptAttachmentService;
import eu.wohlben.qits.workspaces.dto.WorkspacePromptAttachmentDataDto;
import eu.wohlben.qits.workspaces.entity.PromptAttachmentSource;
import eu.wohlben.qits.workspaces.entity.WorkspacePromptAttachment;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The image rows beside a workspace's prompt draft — a pasted screenshot today, a sketch export if
 * that ever returns. Host-owned state on the same terms as {@link WorkspacePromptDraftController}:
 * a database row, no container, so it works while the workspace is STOPPED.
 *
 * <p><b>A separate topic from the draft, and therefore a separate resource.</b> Text autosave fires
 * on a debounced keystroke and images do not change with it, so sharing one SSE topic would make
 * every keystroke re-download every image on every other open view. {@code PROMPT_ATTACHMENTS} is
 * fired by the service for exactly that reason, and the split here matches it.
 *
 * <p>The bytes ride as base64 in JSON rather than as multipart: the client holds a {@code data:}
 * URL from a paste event, the row is small enough for the 64 MB body limit this service already
 * carries for these uploads, and one media type across the whole API is worth more than the
 * encoding overhead. The service sniffs the magic bytes and stores <em>that</em> media type — the
 * claimed one is a hint and only the bytes decide.
 */
@Path("/workspaces/{id}/prompt-attachments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class WorkspacePromptAttachmentController {

  @Inject WorkspacePromptAttachmentService promptAttachments;

  public static record ListAttachmentsRequest() {
    public record Response(List<WorkspacePromptAttachmentDataDto> attachments) {}
  }

  /**
   * Every attachment on this workspace, oldest first, <b>with its bytes</b> — the compose UI rebuilds
   * its thumbnail rows straight from this after a reload, and the draft blob references the rows by
   * id only, so it carries no image data of its own to rebuild them from.
   *
   * <p>An empty list, not a 404, when there are none: a workspace with no images is an ordinary
   * state rather than a missing resource.
   */
  // A read, so an agent may make it too (phase 4: agents keep every read, lose writes).
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @GET
  @APIResponse(responseCode = "200", description = "The attachments, oldest first.")
  @APIResponse(
      responseCode = "404",
      description = "No such ACTIVE workspace.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public ListAttachmentsRequest.Response list(@PathParam("id") Long id) {
    return new ListAttachmentsRequest.Response(promptAttachments.listAttachments(id));
  }

  /**
   * @param mimeType what the client believes it is pasting. Advisory: the stored type is sniffed
   *     from the bytes
   * @param label the human label the compose UI shows ("Pasted image 1")
   * @param source {@code SKETCH} or {@code PASTE}
   * @param dataBase64 the base64-encoded image
   */
  public static record AddAttachmentRequest(
      String mimeType, @NotBlank String label, @NotBlank String source, @NotBlank String dataBase64) {
    /**
     * The stored row without its bytes. The caller just supplied them and has them in memory, so
     * echoing a megabyte back would be pure cost; what it does not have is the server-generated
     * {@code id} the draft blob must reference and the media type the sniff settled on.
     */
    public record Response(
        String id,
        String mimeType,
        String label,
        PromptAttachmentSource source,
        Instant createdAt) {}
  }

  /**
   * Attach one image. 400 for anything that is not valid base64 or not a PNG or JPEG (the sniff, not
   * the claim), 413 over the per-image cap ({@code qits.workspace.prompt-attachment-max-bytes}).
   *
   * <p>201, with the id the caller needs in the body. The binary content resource is stable at that
   * id and is what durable markdown documents reference.
   */
  @POST
  @APIResponse(
      responseCode = "201",
      description = "Attached; the row without its bytes.",
      // Declared rather than inferred: the method returns a `Response` so it can carry the 201 and
      // the Location, and a JAX-RS `Response` erases the body type from the generated document.
      content = @Content(schema = @Schema(implementation = AddAttachmentRequest.Response.class)))
  @APIResponse(
      responseCode = "400",
      description = "Not valid base64, not a PNG or JPEG, or an unknown source.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such ACTIVE workspace.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "413",
      description = "The image exceeds the configured per-image byte cap.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public Response add(@PathParam("id") Long id, @Valid AddAttachmentRequest request) {
    WorkspacePromptAttachment attachment =
        promptAttachments.addAttachment(
            id, request.mimeType(), request.label(), request.source(), request.dataBase64());
    return Response.status(Response.Status.CREATED)
        .entity(
            new AddAttachmentRequest.Response(
                attachment.id,
                attachment.mimeType,
                attachment.label,
                attachment.source,
                attachment.createdAt))
        .build();
  }

  /** Replace an image in place so documents that reference its content URL keep working. */
  @PUT
  @Path("/{attachmentId}")
  @APIResponse(responseCode = "200", description = "Updated; the same row id is preserved.")
  @APIResponse(
      responseCode = "400",
      description = "Not valid base64, not a PNG or JPEG, or an unknown source.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such ACTIVE workspace, or no such attachment on it.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(responseCode = "413", description = "The image exceeds the configured limit.")
  public AddAttachmentRequest.Response update(
      @PathParam("id") Long id,
      @PathParam("attachmentId") String attachmentId,
      @Valid AddAttachmentRequest request) {
    WorkspacePromptAttachment attachment =
        promptAttachments.updateAttachment(
            id,
            attachmentId,
            request.mimeType(),
            request.label(),
            request.source(),
            request.dataBase64());
    return new AddAttachmentRequest.Response(
        attachment.id,
        attachment.mimeType,
        attachment.label,
        attachment.source,
        attachment.createdAt);
  }

  /** Raw image bytes for normal browser and markdown image URLs. */
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @GET
  @Path("/{attachmentId}/content")
  @Produces({"image/png", "image/jpeg"})
  @APIResponse(responseCode = "200", description = "The stored PNG or JPEG bytes.")
  @APIResponse(
      responseCode = "404",
      description = "No such ACTIVE workspace, or no such attachment on it.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public Response content(
      @PathParam("id") Long id, @PathParam("attachmentId") String attachmentId) {
    WorkspacePromptAttachment attachment = promptAttachments.getAttachment(id, attachmentId);
    return Response.ok(attachment.bytes, attachment.mimeType)
        .header("Cache-Control", "no-cache")
        .build();
  }

  /** Remove one attachment, scoped to its workspace. 404 when either the workspace or the row is
   * unknown — a row id from another workspace is not found <em>here</em>, which is the answer that
   * says nothing about whether it exists elsewhere. */
  @DELETE
  @Path("/{attachmentId}")
  @APIResponse(responseCode = "204", description = "Removed.")
  @APIResponse(
      responseCode = "404",
      description = "No such ACTIVE workspace, or no such attachment on it.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public Response delete(
      @PathParam("id") Long id, @PathParam("attachmentId") String attachmentId) {
    promptAttachments.deleteAttachment(id, attachmentId);
    return Response.noContent().build();
  }
}
