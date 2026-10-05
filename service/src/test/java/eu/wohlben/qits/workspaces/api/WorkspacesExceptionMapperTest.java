package eu.wohlben.qits.workspaces.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.RunnerOwnsWorkspacesException;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
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
        mapper.toResponse(
            new RunnerOwnsWorkspacesException(
                "owns two",
                List.of(
                    new RunnerOwnsWorkspacesException.OwnedWorkspace(7L, "repo-a", "feature/x"),
                    new RunnerOwnsWorkspacesException.OwnedWorkspace(9L, "repo-b", null))));

    assertEquals(409, response.getStatus());
    Map<String, Object> seven = new LinkedHashMap<>();
    seven.put("id", 7L);
    seven.put("repositoryId", "repo-a");
    seven.put("branch", "feature/x");
    Map<String, Object> nine = new LinkedHashMap<>();
    nine.put("id", 9L);
    nine.put("repositoryId", "repo-b");
    nine.put("branch", null);
    assertEquals(
        Map.of(
            "message", "owns two",
            "code", "RUNNER_OWNS_WORKSPACES",
            "workspaceIds", List.of(7L, 9L),
            "workspaces", List.of(seven, nine)),
        response.getEntity());
  }
}
