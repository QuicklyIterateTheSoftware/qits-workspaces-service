package eu.wohlben.qits.workspaces.targethost;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * qits-system's lease, work-volume and target doors (epic qits-1481), the six calls {@link
 * TargetLeasePlacement} makes: {@code createLease}, {@code listLeases}, {@code getLease}, {@code
 * endLease}, {@code purgeWorkVolume} and {@code listTargets}. The address is {@code
 * qits.system.url} (scheme, host and port, no path); qits-system serves its own {@code /system/api}
 * segment. The bearer is this service's own {@code qits} oidc client's ({@code qits:system},
 * audience {@code qits-platform}); qits-system names the lease's owner from it, never from a body
 * field.
 *
 * <p>{@code listLeases} names no filter: qits-system answers a {@code qits:system} caller its own
 * leases, and every lease of this service is a WORKSPACE one.
 *
 * <p>The records are narrowed to what this service sends and reads; every answer ignores the rest.
 */
@Path("/system/api")
@RegisterRestClient(configKey = "qits-system")
@RegisterForReflection(
    targets = {
      SystemLeases.CreateLease.class,
      SystemLeases.LeaseSpec.class,
      SystemLeases.Limits.class,
      SystemLeases.LeaseView.class,
      SystemLeases.TargetView.class
    })
public interface SystemLeases {

  @POST
  @Path("/leases")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  LeaseView createLease(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization, CreateLease request);

  @GET
  @Path("/leases")
  @Produces(MediaType.APPLICATION_JSON)
  List<LeaseView> listLeases(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization);

  @GET
  @Path("/leases/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  LeaseView getLease(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization, @PathParam("id") UUID id);

  @DELETE
  @Path("/leases/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  LeaseView endLease(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization, @PathParam("id") UUID id);

  @DELETE
  @Path("/work-volumes/{workKey}")
  @Produces(MediaType.APPLICATION_JSON)
  JsonNode purgeWorkVolume(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @PathParam("workKey") String workKey);

  @GET
  @Path("/targets")
  @Produces(MediaType.APPLICATION_JSON)
  List<TargetView> listTargets(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization);

  /** qits-system's {@code CreateLeaseRequest}. Absent members are left out of the body. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CreateLease(
      String slotType,
      String ownerRef,
      UUID projectId,
      String workKey,
      Integer priority,
      LeaseSpec spec,
      UUID pinTargetId,
      Map<String, String> adopt) {}

  /** {@code LeaseSpec{image, env, user, limits}}, the shape qits-system-runner decodes. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record LeaseSpec(String image, Map<String, String> env, String user, Limits limits) {
    @Override
    public String toString() {
      // The environment carries the lease's token.
      return "LeaseSpec[image=" + image + ", user=" + user + ", limits=" + limits + "]";
    }
  }

  /** The runner's {@code Limits}; an unset limit is left out. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Limits(
      String memory, String memorySwap, Long pidsLimit, String cpus, Integer oomScoreAdj) {}

  /** qits-system's {@code Lease}, narrowed to what this service reads. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record LeaseView(
      UUID id,
      String ownerRef,
      String state,
      UUID targetId,
      Integer exitCode,
      String detail,
      Instant requestedAt) {}

  /**
   * qits-system's {@code Target}, narrowed to what a legacy pin matches on: its id, its name, and
   * the docker engine's host name and id its runner reported (each null until it did).
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TargetView(UUID id, String name, String hostname, String engineId) {}
}
