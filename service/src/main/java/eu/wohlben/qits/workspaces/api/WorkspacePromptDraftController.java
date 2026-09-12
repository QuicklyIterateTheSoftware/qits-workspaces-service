package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspacePromptDraftService;
import eu.wohlben.qits.workspaces.dto.WorkspacePromptDraftDto;
import eu.wohlben.qits.workspaces.mapper.WorkspacePromptDraftMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * A workspace's persisted prompt draft — the text you are composing for the next agent run, plus
 * the client-owned composition state around it.
 *
 * <p><b>Host-owned state, not a forwarder.</b> The draft is a row in this service's database and
 * never involves a container, so it reads and writes on a STOPPED workspace exactly as on a running
 * one. That is what makes it belong here rather than on the daemon's own API, and it is the whole
 * distinction the "nothing forwards" rule turns on.
 *
 * <p><b>This layer adds the HTTP shape and nothing else.</b> {@link WorkspacePromptDraftService}
 * already owns the size cap (413), the JSON well-formedness check (400) and the atomic upsert; the
 * methods below are three lines each on purpose. The service had no caller at all until this class
 * existed.
 *
 * <p>Its own root path rather than a method on {@link WorkspaceController}: a draft is a
 * singleton sub-resource of a workspace with its own verbs, and keeping it here is what keeps that
 * controller about the workspace's own lifecycle.
 */
@Path("/workspaces/{id}/prompt-draft")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class WorkspacePromptDraftController {

  @Inject WorkspacePromptDraftService promptDrafts;

  @Inject WorkspacePromptDraftMapper promptDraftMapper;

  public static record GetPromptDraftRequest() {
    public record Response(WorkspacePromptDraftDto draft) {}
  }

  /**
   * The workspace's draft. <b>404 when none has been saved</b>, deliberately rather than an empty
   * draft: "I have not composed anything here" and "I composed this and cleared it" are the same
   * state to the server, and inventing a row for either would give the client a {@code updatedAt}
   * to dedup against that no save ever produced.
   */
  // A read, so an agent may make it too (phase 4: agents keep every read, lose writes).
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @GET
  @APIResponse(responseCode = "200", description = "The saved draft.")
  @APIResponse(
      responseCode = "404",
      description = "No such ACTIVE workspace, or it has no saved draft.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public GetPromptDraftRequest.Response get(@PathParam("id") Long id) {
    return new GetPromptDraftRequest.Response(promptDraftMapper.toDto(promptDrafts.getDraft(id)));
  }

  /**
   * @param content the opaque composition JSON the client owns. The server validates only that it
   *     is one well-formed JSON document — the schema inside it is none of this service's business,
   *     which is what lets the compose UI change shape without a migration here
   * @param serializedPrompt the launch-ready markdown, the server-readable half. Nullable while the
   *     composition has nothing deliverable in it yet
   */
  public static record SavePromptDraftRequest(@NotNull String content, String serializedPrompt) {
    public record Response(WorkspacePromptDraftDto draft) {}
  }

  /**
   * Upsert the draft — idempotent, and the autosave path, so it is called on a debounce while
   * someone types.
   *
   * <p><b>The persisted entity comes back, not the request.</b> The client stores the returned
   * {@code updatedAt} to recognise its own echo on the {@code prompt-draft} SSE topic, so the value
   * has to be the one a later GET returns byte-for-byte — which means reading it back after the
   * write rather than stamping a timestamp here.
   *
   * <p>Two refusals, both from the service: a 400 when {@code content} is not one well-formed JSON
   * document, and a 413 when it and {@code serializedPrompt} together exceed the configured cap
   * ({@code qits.workspace.prompt-draft-max-bytes}, 2 MB). Both are checked before anything touches
   * the database, so a buggy autosave loop of rejected saves costs no round-trips.
   */
  @PUT
  @APIResponse(responseCode = "200", description = "The persisted draft, with its fresh updatedAt.")
  @APIResponse(
      responseCode = "400",
      description = "The content is not a well-formed JSON document.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such ACTIVE workspace.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "413",
      description = "The draft exceeds the configured byte cap.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public SavePromptDraftRequest.Response save(
      @PathParam("id") Long id, @Valid SavePromptDraftRequest request) {
    return new SavePromptDraftRequest.Response(
        promptDraftMapper.toDto(
            promptDrafts.saveDraft(id, request.content(), request.serializedPrompt())));
  }

  /**
   * Discard the draft and its attachment rows — the images are the draft's payload, so clearing one
   * clears the other. Idempotent: 204 whether a draft was there or not, because "there is no draft
   * for this workspace" is the state the caller asked for either way.
   */
  @DELETE
  @APIResponse(responseCode = "204", description = "There is no draft for this workspace.")
  @APIResponse(
      responseCode = "404",
      description = "No such ACTIVE workspace.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public Response delete(@PathParam("id") Long id) {
    promptDrafts.deleteDraft(id);
    return Response.noContent().build();
  }
}
