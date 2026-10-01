package eu.wohlben.qits.workspaces.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspaces.control.CaptureService;
import eu.wohlben.qits.workspaces.dto.CaptureContent;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.entity.Workspace;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.zip.GZIPInputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The capture ingest: a running app's browser posts an SPA snapshot here (CORS-opened by {@link
 * CaptureCorsRoute} on exactly this path) and lands on a new workspace whose goal carries the
 * captured context. Identity comes self-stamped from the payload ({@code qits.repository.id}) at
 * the same unauthenticated trust level as the OTLP receiver's resource attributes — but unlike
 * telemetry, ingest fails closed: an unresolvable repository creates nothing.
 *
 * <p>Hidden from the OpenAPI document: this is a wire endpoint for the capture library, not part of
 * the JSON API the generated Angular client consumes.
 *
 * <p>Both payload shapes are registered for reflection because <strong>neither appears on a method
 * signature Quarkus can index</strong>: the request arrives as {@code byte[]} (it is gzipped, and
 * inflated under a cap before parsing) and is turned into a {@link CaptureRequest} by an injected
 * {@code ObjectMapper}, while the reply goes back inside a {@code jakarta.ws.rs.core.Response} that
 * hides {@link CaptureResponse}. Native-image therefore keeps no members of either, and the binary
 * answered every capture with 400 "Malformed capture payload" — the parse failing on reflection,
 * caught as an {@code IOException} and reported as a client error. The JVM suite could not see it;
 * only a request against the binary did. Nested records need naming individually.
 */
@RegisterForReflection(
    targets = {
      CaptureResource.CaptureRequest.class,
      CaptureResource.CaptureRequest.Identity.class,
      CaptureResource.CaptureRequest.Page.class,
      CaptureResource.CaptureRequest.Environment.class,
      CaptureResource.CaptureRequest.Viewport.class,
      CaptureResource.CaptureRequest.Dom.class,
      CaptureResource.CaptureRequest.Selection.class,
      CaptureResource.CaptureResponse.class,
      CaptureResource.CaptureResponse.WorkspaceRef.class
    })
@Path("capture")
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class CaptureResource {

  /**
   * Cap on the <em>decompressed</em> payload (413 past it); the wire size is separately bounded by
   * Quarkus' {@code quarkus.http.limits.max-body-size}. Enforced during inflation, so a gzip bomb
   * never materializes.
   */
  @ConfigProperty(name = "qits.capture.max-payload-bytes", defaultValue = "10485760")
  long maxPayloadBytes = 10_485_760;

  @Inject ObjectMapper objectMapper;
  @Inject CaptureService captureService;

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  // Hidden from docs/openapi.yml, but it still carries an operationId: the consumer pact against
  // qits-projects names this door as the trigger of its repository lookup (`qits-trigger`).
  @Operation(hidden = true, operationId = "captureWorkspace")
  public Response capture(byte[] body, @Context UriInfo uriInfo) {
    byte[] json = gunzipBounded(body == null ? new byte[0] : body);
    CaptureRequest request = parse(json);
    String repoId = request.identity() == null ? null : request.identity().repositoryId();
    Workspace workspace = captureService.capture(repoId, toContent(request), Instant.now());
    String url =
        requestOrigin(uriInfo)
            + "/repositories/"
            + workspace.repositoryId
            + "/workspaces/"
            + workspace.workspaceId
            + "/wip";
    return Response.status(Response.Status.CREATED)
        .entity(
            new CaptureResponse(
                new CaptureResponse.WorkspaceRef(
                    workspace.id, workspace.workspaceId, workspace.branch),
                url))
        .build();
  }

  /**
   * The capture payload
   * (docs/epics/qits-integration-angular/features/2026-07-14_spa-feature-capture.md, "The capture
   * payload").
   */
  public record CaptureRequest(
      String capturedAt,
      Identity identity,
      Page page,
      Environment environment,
      Dom dom,
      Selection selection,
      JsonNode state) {
    public record Identity(
        @JsonProperty("qits.repository.id") String repositoryId,
        @JsonProperty("qits.workspace.id") String workspaceId) {}

    public record Page(String url, String appPath, String routePattern, String title) {}

    public record Environment(Viewport viewport, String userAgent, String prefersColorScheme) {}

    public record Viewport(Integer width, Integer height, Double devicePixelRatio) {}

    public record Dom(String html, Boolean truncated, Long bytes) {}

    public record Selection(
        String html,
        Boolean truncated,
        Long bytes,
        String selector,
        String tag,
        String component) {}
  }

  public record CaptureResponse(WorkspaceRef workspace, String url) {
    public record WorkspaceRef(Long id, String workspaceId, String branch) {}
  }

  private CaptureRequest parse(byte[] json) {
    try {
      return objectMapper.readValue(json, CaptureRequest.class);
    } catch (IOException e) {
      throw new BadRequestException("Malformed capture payload");
    }
  }

  private CaptureContent toContent(CaptureRequest request) {
    CaptureRequest.Page page = request.page();
    CaptureRequest.Environment env = request.environment();
    CaptureRequest.Viewport viewport = env == null ? null : env.viewport();
    CaptureRequest.Dom dom = request.dom();
    return new CaptureContent(
        request.capturedAt(),
        request.identity() == null ? null : request.identity().workspaceId(),
        page == null
            ? null
            : new CaptureContent.Page(
                page.url(), page.appPath(), page.routePattern(), page.title()),
        env == null
            ? null
            : new CaptureContent.Environment(
                viewport == null ? null : viewport.width(),
                viewport == null ? null : viewport.height(),
                viewport == null ? null : viewport.devicePixelRatio(),
                env.userAgent(),
                env.prefersColorScheme()),
        dom == null || dom.html() == null
            ? null
            : new CaptureContent.Dom(
                dom.html(),
                Boolean.TRUE.equals(dom.truncated()),
                dom.bytes() == null ? dom.html().length() : dom.bytes()),
        toSelection(request.selection()),
        stateJson(request.state()));
  }

  private CaptureContent.Selection toSelection(CaptureRequest.Selection selection) {
    if (selection == null || selection.html() == null) {
      return null;
    }
    return new CaptureContent.Selection(
        selection.html(),
        Boolean.TRUE.equals(selection.truncated()),
        selection.bytes() == null ? selection.html().length() : selection.bytes(),
        selection.selector(),
        selection.tag(),
        selection.component());
  }

  private String stateJson(JsonNode state) {
    if (state == null || state.isNull()) {
      return null;
    }
    try {
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state);
    } catch (JacksonException e) {
      throw new BadRequestException("Malformed capture state");
    }
  }

  /**
   * The browser-facing origin of the created workspace's page: scheme + authority the request
   * actually reached qits on ({@code Host}), which is browser-reachable by definition. Deliberately
   * <em>not</em> the {@code Origin} header — that is the capturing <em>app's</em> origin, where
   * {@code /repositories/…} doesn't exist for a standalone cross-origin app.
   */
  private static String requestOrigin(UriInfo uriInfo) {
    URI base = uriInfo.getBaseUri();
    return base.getScheme() + "://" + base.getAuthority();
  }

  /**
   * Returns the identity payload as-is (JSON never starts with the gzip magic {@code 0x1f 0x8b}),
   * or inflates a gzipped one — counting <em>during</em> inflation and aborting with 413 the moment
   * the decompressed size exceeds the cap.
   */
  private byte[] gunzipBounded(byte[] body) {
    boolean gzipped = body.length >= 2 && body[0] == 0x1f && body[1] == (byte) 0x8b;
    if (!gzipped) {
      if (body.length > maxPayloadBytes) {
        throw payloadTooLarge();
      }
      return body;
    }
    try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int read;
      while ((read = gz.read(chunk)) != -1) {
        if (out.size() + read > maxPayloadBytes) {
          throw payloadTooLarge();
        }
        out.write(chunk, 0, read);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new BadRequestException("Malformed gzip payload");
    }
  }

  private WebApplicationException payloadTooLarge() {
    return new WebApplicationException(
        "Capture payload exceeds " + maxPayloadBytes + " bytes decompressed", 413);
  }
}
