package eu.wohlben.qits.workspaces.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.RunnerOwnsWorkspacesException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The envelope stays {@code {"message"}}; a named refusal adds {@code code}, and nothing else. */
class WorkspacesExceptionMapperTest {

  private final WorkspacesExceptionMapper mapper = new WorkspacesExceptionMapper();

  @Test
  public void anUnnamedRefusalKeepsTheBareEnvelope() {
    Response response = mapper.toResponse(new ConflictException("taken"));

    assertEquals(409, response.getStatus());
    assertEquals(Map.of("message", "taken"), response.getEntity());
  }

  @Test
  public void aRunnerThatOwnsWorkspacesNamesTheRefusalAndTheRows() {
    Response response =
        mapper.toResponse(new RunnerOwnsWorkspacesException("owns two", List.of(7L, 9L)));

    assertEquals(409, response.getStatus());
    assertEquals(
        Map.of(
            "message", "owns two",
            "code", "RUNNER_OWNS_WORKSPACES",
            "workspaceIds", List.of(7L, 9L)),
        response.getEntity());
  }
}
