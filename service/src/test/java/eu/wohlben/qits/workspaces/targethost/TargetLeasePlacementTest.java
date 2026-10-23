package eu.wohlben.qits.workspaces.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.workspaces.control.TargetPlacement;
import io.quarkus.test.junit.QuarkusTest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TargetLeasePlacement} over the real generated client against a stub qits-system: what a
 * create sends (the lease's work key and owner ref, the spec as the runner decodes it, the bearer),
 * and how every answer that is not a lease reads — a 404 as no lease, a refusal with its code, and
 * nothing answering as status -1.
 */
@QuarkusTest
public class TargetLeasePlacementTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private HttpServer server;
  private final AtomicReference<String> sentBody = new AtomicReference<>();
  private final AtomicReference<String> sentBearer = new AtomicReference<>();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private TargetLeasePlacement answering(int status, String body) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/system/api",
        exchange -> {
          sentBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          sentBearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
          if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
    return SystemContract.placementAgainst(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        Map.of("authorization", "Bearer service-token"));
  }

  @Test
  public void aCreateSendsTheWorkspaceLeaseAndReadsTheAnswer() throws Exception {
    UUID id = UUID.randomUUID();
    TargetLeasePlacement placement =
        answering(
            201,
            "{\"id\":\""
                + id
                + "\",\"ownerRef\":\"17\",\"state\":\"QUEUED\",\"targetId\":null,"
                + "\"detail\":\"no target has a free WORKSPACE slot\",\"extra\":1}");

    TargetPlacement.Lease lease =
        placement.create(
            new TargetPlacement.LeaseRequest(
                17L,
                null,
                SystemContract.request().spec(),
                new TargetPlacement.Adopt("qits-workspaces-runner-ws-17-8d45fd1d",
                    "qits-workspaces-runner-ws-17-8d45fd1d"),
                null));

    assertEquals(id, lease.id());
    assertEquals(TargetPlacement.LeaseState.QUEUED, lease.state());
    assertEquals(17L, lease.rowId());
    assertEquals("Bearer service-token", sentBearer.get());
    JsonNode sent = JSON.readTree(sentBody.get());
    assertEquals("WORKSPACE", sent.path("slotType").asText());
    assertEquals("17", sent.path("ownerRef").asText());
    assertEquals("ws-17", sent.path("workKey").asText());
    assertEquals(0, sent.path("priority").asInt());
    assertEquals("1000", sent.path("spec").path("user").asText());
    assertEquals(4096, sent.path("spec").path("limits").path("pidsLimit").asLong());
    assertEquals(
        "qits-workspaces-runner-ws-17-8d45fd1d", sent.path("adopt").path("volume").asText());
    assertFalse(sent.has("projectId"), "an absent member is left out");
    assertFalse(sent.has("pinTargetId"));
  }

  @Test
  public void aRefusalCarriesItsStatusAndCode() throws Exception {
    TargetLeasePlacement placement =
        answering(400, "{\"code\":\"IMAGE_NOT_ALLOWED\",\"message\":\"not a workspace image\"}");

    TargetPlacement.LeaseRefused refused =
        assertThrows(
            TargetPlacement.LeaseRefused.class,
            () -> placement.create(SystemContract.request()));

    assertEquals(400, refused.status());
    assertEquals("IMAGE_NOT_ALLOWED", refused.code());
    assertTrue(refused.permanent());
    assertTrue(refused.getMessage().contains("not a workspace image"), refused.getMessage());
  }

  @Test
  public void aMissingLeaseIsEmptyAndAMissingVolumeIsFalse() throws Exception {
    TargetLeasePlacement placement = answering(404, "{\"message\":\"No lease\"}");

    assertEquals(Optional.empty(), placement.get(UUID.randomUUID()));
    assertEquals(Optional.empty(), placement.end(UUID.randomUUID()));
    assertFalse(placement.purgeWorkVolume("ws-17"));
  }

  @Test
  public void aVolumeInUseIsARefusal() throws Exception {
    TargetLeasePlacement placement =
        answering(409, "{\"code\":\"WORK_VOLUME_IN_USE\",\"message\":\"in use\"}");

    TargetPlacement.LeaseRefused refused =
        assertThrows(
            TargetPlacement.LeaseRefused.class, () -> placement.purgeWorkVolume("ws-17"));
    assertEquals(409, refused.status());
    assertFalse(refused.permanent(), "a 409 is about the moment");
  }

  @Test
  public void nothingAnsweringIsStatusMinusOne() {
    TargetLeasePlacement placement =
        SystemContract.placementAgainst("http://127.0.0.1:9", Map.of());

    TargetPlacement.LeaseRefused refused =
        assertThrows(TargetPlacement.LeaseRefused.class, () -> placement.list());
    assertEquals(-1, refused.status());
  }

  @Test
  public void theTargetsReadTheirEngineIdAndHostNameWhereTheRunnerReportedThem() throws Exception {
    UUID id = UUID.randomUUID();
    TargetLeasePlacement placement =
        answering(
            200,
            "[{\"id\":\"" + id + "\",\"name\":\"lab\",\"engineId\":\"E1\",\"hostname\":null},"
                + "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"other\"}]");

    var targets = placement.targets();

    assertEquals(new TargetPlacement.Target(id, "lab", "E1", null), targets.get(0));
    assertEquals(null, targets.get(1).engineId());
  }
}
