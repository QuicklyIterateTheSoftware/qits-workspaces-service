package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.error.DomainException;
import eu.wohlben.qits.workspaces.error.IntegrateConflictException;
import eu.wohlben.qits.workspaces.error.RunnerOwnsWorkspacesException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the workspaces domain's framework-free {@link DomainException}s (each carrying a status
 * code) to HTTP responses.
 *
 * <p>It lives here, in {@code service}, for the same reason the sibling {@code CiExceptionMapper}
 * does: the {@code domain} module carries no JAX-RS, which is what lets it stay a plain library jar.
 *
 * <p>Scoped to <em>this</em> context's exception type. An application that also runs the monorepo's
 * {@code eu.wohlben.qits.domain.error.DomainException} keeps its own mapper for it; the two coexist
 * because they map unrelated types.
 *
 * <p><b>The envelope is {@code {"message": …}} and stays that way</b> — one shape for every domain
 * error, which is what lets a client handle them uniformly. {@link IntegrateConflictException} is
 * the single exception and it is <em>additive</em>: the same {@code message}, plus a {@code reason}
 * and (for the conflict modes) a {@code conflicts} array. Integrate has four ways to refuse that a
 * person does four different things about, and prose was the only channel carrying the difference —
 * so a client had to word-match it. A field is not a second envelope; it is the same one with the
 * discriminator the prose was already trying to encode.
 *
 * <p>A refusal that names itself ({@link DomainException#code()}, qits-ci's {@code code} member)
 * adds {@code code} the same additive way, and {@link RunnerOwnsWorkspacesException} adds the
 * {@code workspaceIds} the runner still owns.
 */
@Provider
public class WorkspacesExceptionMapper implements ExceptionMapper<DomainException> {

  @Override
  public Response toResponse(DomainException exception) {
    int status = exception.statusCode();
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = Response.Status.fromStatusCode(status).getReasonPhrase();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", message);
    if (exception.code() != null) {
      body.put("code", exception.code());
    }
    if (exception instanceof RunnerOwnsWorkspacesException owns) {
      body.put("workspaceIds", owns.workspaceIds());
      List<Map<String, Object>> workspaces = new ArrayList<>();
      for (RunnerOwnsWorkspacesException.OwnedWorkspace owned : owns.workspaces()) {
        // A map rather than the record, so a null branch is written as null like every other key.
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", owned.id());
        entry.put("repositoryId", owned.repositoryId());
        entry.put("branch", owned.branch());
        workspaces.add(entry);
      }
      body.put("workspaces", workspaces);
    }
    if (exception instanceof IntegrateConflictException conflict) {
      body.put("reason", conflict.reason().name());
      if (!conflict.conflicts().isEmpty()) {
        body.put("conflicts", conflict.conflicts());
      }
    }
    return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
  }
}
