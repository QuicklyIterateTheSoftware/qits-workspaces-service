package eu.wohlben.qits.workspaces.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.error.ConflictException;
import jakarta.ws.rs.core.Response;
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
  public void aNamedRefusalAddsItsCode() {
    Response response =
        mapper.toResponse(new ConflictException("TARGET_UNAVAILABLE", "qits-system is away"));

    assertEquals(409, response.getStatus());
    assertEquals(
        Map.of("message", "qits-system is away", "code", "TARGET_UNAVAILABLE"),
        response.getEntity());
  }
}
