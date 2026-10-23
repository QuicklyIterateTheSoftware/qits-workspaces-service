package eu.wohlben.qits.workspaces.targethost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspaces.control.RunnerLaunchSpec;
import eu.wohlben.qits.workspaces.control.TargetPlacement;
import eu.wohlben.qits.workspaces.wiring.IdpProjectsBearer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * The domain's {@link TargetPlacement} port over qits-system's HTTP doors (epic qits-1481,
 * qits-1532): it replaces the runner socket's {@code RunnerPlacementDriver}. A WORKSPACE lease per
 * TARGET row — {@code slotType WORKSPACE}, {@code ownerRef <rowId>}, {@code workKey ws-<rowId>},
 * {@code priority 0} and the spec {@code RunnerWorkspaceSpecs.compose} made — ended with {@code
 * DELETE /system/api/leases/{id}}, its work volume purged with {@code DELETE
 * /system/api/work-volumes/ws-<rowId>}.
 *
 * <p><b>The bearer is this service's own</b> ({@link IdpProjectsBearer}: the {@code qits} oidc
 * client, {@code qits:system}, audience {@code qits-platform}). qits-system makes the lease's owner
 * {@code qits-workspaces} from it and admits it as the WORKSPACE tenant. With the client switched
 * off (dev, test) no header is sent.
 *
 * <p><b>Every failure is a {@link TargetPlacement.LeaseRefused}</b>, with qits-system's status and
 * {@code code} where it answered ({@code ErrorBody{code, message}}), and status -1 where nothing
 * did. A 404 to a read or an end is no lease, the same outcome, and answers empty.
 *
 * <p>{@code @DefaultBean}, for {@code HttpRepositoryLookup}'s reason: the suites' {@code
 * FakeTargetPlacement} wins over it with no change on anybody's part, and {@code SystemContract}
 * builds this class by hand against its pact mock servers.
 */
@ApplicationScoped
@DefaultBean
public class TargetLeasePlacement implements TargetPlacement {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject @RestClient SystemLeases system;

  @Inject IdpProjectsBearer bearer;

  @Override
  public Lease create(LeaseRequest request) {
    RunnerLaunchSpec spec = request.spec();
    RunnerLaunchSpec.Limits limits = spec.limits();
    Long pidsLimit;
    try {
      pidsLimit = limits == null ? null : pids(limits.pids());
    } catch (IllegalStateException unusable) {
      throw new LeaseRefused(400, null, unusable.getMessage(), unusable);
    }
    SystemLeases.CreateLease body =
        new SystemLeases.CreateLease(
            SLOT_TYPE,
            TargetPlacement.ownerRef(request.rowId()),
            request.projectId(),
            TargetPlacement.workKey(request.rowId()),
            0,
            new SystemLeases.LeaseSpec(
                spec.image(),
                spec.env(),
                spec.user(),
                limits == null
                    ? null
                    : new SystemLeases.Limits(
                        limits.memory(),
                        limits.memorySwap(),
                        pidsLimit,
                        limits.cpus(),
                        limits.oomScoreAdj())),
            request.pinTargetId(),
            adopt(request.adopt()));
    return view(call("ask for a lease for workspace " + request.rowId(),
        () -> system.createLease(authorization(), body)));
  }

  @Override
  public Optional<Lease> end(UUID leaseId) {
    return optional("end lease " + leaseId, () -> system.endLease(authorization(), leaseId));
  }

  @Override
  public Optional<Lease> get(UUID leaseId) {
    return optional("read lease " + leaseId, () -> system.getLease(authorization(), leaseId));
  }

  @Override
  public List<Lease> list() {
    List<SystemLeases.LeaseView> listed =
        call("list this service's leases", () -> system.listLeases(authorization()));
    return listed == null ? List.of() : listed.stream().map(TargetLeasePlacement::view).toList();
  }

  @Override
  public boolean purgeWorkVolume(String workKey) {
    try {
      call("purge work volume " + workKey, () -> system.purgeWorkVolume(authorization(), workKey));
      return true;
    } catch (LeaseRefused refused) {
      if (refused.status() == 404) {
        return false;
      }
      throw refused;
    }
  }

  @Override
  public List<Target> targets() {
    List<SystemLeases.TargetView> listed =
        call("list the targets", () -> system.listTargets(authorization()));
    if (listed == null) {
      return List.of();
    }
    return listed.stream()
        .map(
            t ->
                new Target(
                    t.id(),
                    t.name(),
                    text(t.capabilities(), "engineId"),
                    text(t.capabilities(), "hostname")))
        .toList();
  }

  // --- internals --------------------------------------------------------------------------------

  private String authorization() {
    return bearer.authorization().orElse(null);
  }

  private Optional<Lease> optional(String what, Supplier<SystemLeases.LeaseView> attempt) {
    try {
      return Optional.ofNullable(call(what, attempt)).map(TargetLeasePlacement::view);
    } catch (LeaseRefused refused) {
      if (refused.status() == 404) {
        return Optional.empty();
      }
      throw refused;
    }
  }

  private static <T> T call(String what, Supplier<T> attempt) {
    try {
      return attempt.get();
    } catch (WebApplicationException http) {
      int status = http.getResponse().getStatus();
      String[] answer = answer(http);
      throw new LeaseRefused(
          status,
          answer[0],
          "qits-system answered " + status + " when asked to " + what
              + (answer[1] == null ? "" : ": " + answer[1]),
          http);
    } catch (LeaseRefused refused) {
      throw refused;
    } catch (RuntimeException transport) {
      throw new LeaseRefused(
          -1, null, "qits-system could not be asked to " + what + ": " + transport, transport);
    }
  }

  /** {@code [code, message]} of qits-system's {@code ErrorBody}, each null when absent. */
  private static String[] answer(WebApplicationException http) {
    try {
      String body = http.getResponse().readEntity(String.class);
      if (body == null || body.isBlank()) {
        return new String[2];
      }
      JsonNode node = JSON.readTree(body);
      return new String[] {text(node, "code"), text(node, "message")};
    } catch (Exception unreadable) {
      return new String[2];
    }
  }

  static Lease view(SystemLeases.LeaseView view) {
    if (view == null) {
      return null;
    }
    return new Lease(
        view.id(),
        view.ownerRef(),
        LeaseState.parse(view.state()),
        view.targetId(),
        view.exitCode(),
        view.detail(),
        view.requestedAt());
  }

  private static Map<String, String> adopt(Adopt adopt) {
    if (adopt == null) {
      return null;
    }
    Map<String, String> named = new LinkedHashMap<>();
    named.put("container", adopt.container());
    named.put("volume", adopt.volume());
    return named;
  }

  /** The domain keeps the pids limit as docker's text; the wire carries it as a number. */
  static Long pids(String pids) {
    if (pids == null || pids.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(pids.trim());
    } catch (NumberFormatException notANumber) {
      throw new IllegalStateException("the configured pids limit '" + pids + "' is not a number");
    }
  }

  private static String text(JsonNode node, String field) {
    return node != null && node.path(field).isTextual() ? node.path(field).asText() : null;
  }
}
