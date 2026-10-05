package eu.wohlben.qits.workspaces.control;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunnerCapabilities;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.DomainException;
import eu.wohlben.qits.workspaces.error.ForbiddenException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.error.RunnerOwnsWorkspacesException;
import eu.wohlben.qits.workspaces.mapper.WorkspaceRunnerMapper;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The workspace runners: every rule about a {@link WorkspaceRunner}'s state, and every write to one.
 * qits-ci's {@code CiRunners} is the exemplar, and the shape is the same.
 *
 * <p><b>What is NOT here is qits-idp.</b> A runner's lifecycle is interleaved with calls to qits-idp
 * (a registration token at create and at every rotation, a client at registration, both given back
 * at delete), and every one is HTTP, so they belong to the service module. What this class offers
 * the caller is the two halves around each call: a check that can refuse <em>before</em> anything
 * is commissioned ({@link #requireCreatable}, {@link #requireUnregistered}, {@link
 * #requireRegistrable}), and a write that records what was commissioned <em>after</em> ({@link
 * #create}, {@link #replaceRegistrationToken}, {@link #markRegistered}). Nothing holds a
 * transaction across the network: each method is its own {@code requiringNew}, so a slow idp holds
 * no connection.
 *
 * <p><b>The name rule is {@link #NAME}</b>: it becomes part of what an operator types and of the
 * runner's node names, so it is kept to the one shape safe in both. A taken name is a 409, checked
 * before a token is commissioned and again by {@code uq_workspace_runner_name} for the race.
 *
 * <p><b>A runner that owns an ACTIVE workspace cannot be deleted</b> ({@link
 * RunnerOwnsWorkspacesException}, 409 {@code RUNNER_OWNS_WORKSPACES} with the row ids). A workspace
 * is sticky to its runner because its volume lives on that node; deleting the runner would strand
 * it. A resolved workspace naming the runner never holds it up: {@code workspace.runner_id} has no
 * foreign key.
 */
@ApplicationScoped
public class WorkspaceRunners {

  /** A runner's name: a lower-case letter, then up to 63 lower-case letters, digits and hyphens. */
  public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");

  /** The widest description a runner keeps: {@code workspace_runner.description}'s width. */
  public static final int DESCRIPTION_MAX = 1024;

  /** The slots a runner is created with when the request names none: the column's default. */
  public static final int DEFAULT_SLOTS = 1;

  /**
   * The reason a runner is quarantined with the moment it registers. Holding a token proves nothing
   * about pulling the workspace image and running it, so the runner takes no workspace until its
   * first health check says it can.
   */
  public static final String AWAITING_FIRST_HEALTH_CHECK = "awaiting first health check";

  @Inject WorkspaceRunnerRepository runners;

  @Inject WorkspaceRepository workspaces;

  @Inject WorkspaceRunnerMapper mapper;

  /** 400 unless {@code name} is a runner name; see {@link #NAME}. */
  public static void requireName(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new BadRequestException(
          "A runner name is a lower-case letter followed by at most 63 lower-case letters, digits"
              + " and hyphens ([a-z][a-z0-9-]{0,63})");
    }
  }

  private static void requireSlots(Integer slots) {
    if (slots != null && slots < 0) {
      throw new BadRequestException("slots is at least 0; a runner with 0 slots is drained");
    }
  }

  private static void requireDescription(String description) {
    if (description != null && description.length() > DESCRIPTION_MAX) {
      throw new BadRequestException(
          "A runner description is at most " + DESCRIPTION_MAX + " characters");
    }
  }

  /**
   * Everything a create can be refused for, asked <b>before</b> a registration token is
   * commissioned: a malformed name, slots or description (400), and a taken name (409).
   */
  public void requireCreatable(String name, String description, Integer slots) {
    requireName(name);
    requireSlots(slots);
    requireDescription(description);
    boolean taken =
        QuarkusTransaction.requiringNew().call(() -> runners.findByName(name).isPresent());
    if (taken) {
      throw nameTaken(name);
    }
  }

  /**
   * Records a runner whose registration token has already been commissioned. {@code id} is minted by
   * the caller, because the token's context id at qits-idp is this runner's id and has to exist
   * before the row does.
   *
   * @throws ConflictException when the name was taken in between
   */
  public WorkspaceRunner create(
      UUID id,
      String name,
      String description,
      Integer slots,
      String registrationTokenId,
      String registrationTokenSubject) {
    requireCreatable(name, description, slots);
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                WorkspaceRunner runner = new WorkspaceRunner();
                runner.id = Objects.requireNonNull(id, "id");
                runner.name = name;
                runner.description = blankToNull(description);
                runner.slots = slots == null ? DEFAULT_SLOTS : slots;
                runner.registrationTokenId = registrationTokenId;
                runner.registrationTokenSubject = registrationTokenSubject;
                runner.createdAt = Instant.now();
                runners.persist(runner);
                runners.flush();
                return runner;
              });
    } catch (DomainException refused) {
      throw refused;
    } catch (RuntimeException collided) {
      // The one constraint a fresh uuid can collide with is the name's; anything else is not a 409
      // and is rethrown as it came.
      boolean taken =
          QuarkusTransaction.requiringNew().call(() -> runners.findByName(name).isPresent());
      if (taken) {
        throw nameTaken(name);
      }
      throw collided;
    }
  }

  private static ConflictException nameTaken(String name) {
    return new ConflictException("A runner named " + name + " already exists");
  }

  /** Every runner, by name. */
  public List<WorkspaceRunner> list() {
    return QuarkusTransaction.requiringNew().call(runners::listByName);
  }

  /** One runner, or 404. */
  public WorkspaceRunner get(UUID id) {
    WorkspaceRunner runner = QuarkusTransaction.requiringNew().call(() -> runners.findById(id));
    if (runner == null) {
      throw notFound(id);
    }
    return runner;
  }

  /** The registered runner a commissioned client belongs to: the socket's question about a dial. */
  public Optional<WorkspaceRunner> findByClientId(String clientId) {
    if (clientId == null || clientId.isBlank()) {
      return Optional.empty();
    }
    return QuarkusTransaction.requiringNew().call(() -> runners.findByClientId(clientId));
  }

  /**
   * Changes what an operator may change about a runner. A null leaves the value as it is; slots 0 is
   * allowed and drains the runner; a blank description clears it. Telling a connected runner its new
   * slots is the caller's.
   */
  public WorkspaceRunner patch(UUID id, Integer slots, String description) {
    requireSlots(slots);
    requireDescription(description);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkspaceRunner runner = found(id);
              if (slots != null) {
                runner.slots = slots;
              }
              if (description != null) {
                runner.description = blankToNull(description);
              }
              return runner;
            });
  }

  /**
   * 409 unless a registration token may be issued for this runner now, which is while it is
   * unregistered. Asked before a rotation commissions a new token.
   */
  public WorkspaceRunner requireUnregistered(UUID id) {
    WorkspaceRunner runner = get(id);
    if (runner.registered()) {
      throw registeredAlready(runner);
    }
    return runner;
  }

  /**
   * Swaps in a freshly commissioned registration token and answers the id of the one it replaced,
   * or null, so the caller can delete that one at qits-idp.
   *
   * @throws ConflictException when the runner registered in between
   */
  public String replaceRegistrationToken(UUID id, String tokenId, String tokenSubject) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkspaceRunner runner = found(id);
              if (runner.registered()) {
                throw registeredAlready(runner);
              }
              String previous = runner.registrationTokenId;
              runner.registrationTokenId = tokenId;
              runner.registrationTokenSubject = tokenSubject;
              return previous;
            });
  }

  /**
   * The register door's check, asked before a client is commissioned: 404 for no such runner, 403
   * when the caller's {@code sub} is not this runner's registration token subject (also the answer
   * to no subject at all), 409 when the runner is already registered.
   *
   * <p>The subject stays on the row after registration, which is what makes the 409 reachable: a
   * JWT the edge minted for the token outlives the token's deletion by up to its own lifetime.
   */
  public WorkspaceRunner requireRegistrable(UUID id, String callerSubject) {
    WorkspaceRunner runner = get(id);
    if (callerSubject == null
        || callerSubject.isBlank()
        || !callerSubject.equals(runner.registrationTokenSubject)) {
      throw new ForbiddenException("This registration token is not this runner's");
    }
    if (runner.registered()) {
      throw registeredAlready(runner);
    }
    return runner;
  }

  /**
   * Records the client the register door commissioned and what the runner said about itself, and
   * <b>quarantines the runner</b> ({@link #AWAITING_FIRST_HEALTH_CHECK}) until its first health check
   * passes.
   *
   * <p>The registration token id is cleared here: the token is spent, and the caller deletes it at
   * qits-idp with the id it read in {@link #requireRegistrable}. A token whose delete failed is then
   * referenced by no row, and the commission reconcile reaps it. The subject stays (see {@link
   * #requireRegistrable}).
   *
   * @throws ConflictException when the runner registered in between; the caller then gives its own
   *     freshly commissioned client back
   */
  public WorkspaceRunner markRegistered(UUID id, String clientId, JsonNode capabilities) {
    Objects.requireNonNull(clientId, "clientId");
    String said = capabilitiesOf(null, capabilities);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkspaceRunner runner = found(id);
              if (runner.registered()) {
                throw registeredAlready(runner);
              }
              Instant now = Instant.now();
              runner.clientId = clientId;
              runner.registrationTokenId = null;
              runner.capabilities = said;
              runner.registeredAt = now;
              runner.lastSeenAt = now;
              runner.quarantinedAt = now;
              runner.quarantineReason = AWAITING_FIRST_HEALTH_CHECK;
              return runner;
            });
  }

  /** Stamps the runner as heard from now. A runner that no longer exists is not an error here. */
  public void touchSeen(UUID id) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              WorkspaceRunner runner = runners.findById(id);
              if (runner != null) {
                runner.lastSeenAt = Instant.now();
              }
            });
  }

  /**
   * Merges what the runner said about itself into its capabilities: every key it sent replaces the
   * stored one, every other key keeps its last known value (see {@link
   * WorkspaceRunnerCapabilities#merge}). This is where {@code version}, {@code dotClaudeVolume} and
   * {@code login{claude,kimi,checkedAt}} land, from {@code hello}, {@code inventory} and {@code
   * loginState}. It stamps the runner as heard from.
   *
   * @return the row as it now is, or null for a runner deleted meanwhile
   * @throws BadRequestException when the report is not a JSON object or is too large to keep
   */
  public WorkspaceRunner recordCapabilities(UUID id, JsonNode said) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkspaceRunner runner = runners.findById(id, LockModeType.PESSIMISTIC_WRITE);
              if (runner == null) {
                return null;
              }
              runner.capabilities = capabilitiesOf(runner.capabilities, said);
              runner.lastSeenAt = Instant.now();
              return runner;
            });
  }

  /**
   * Takes a runner out of service with {@code reason}, and answers the row when THIS call did: null
   * when it was already out (its reason stands) or is gone. Its {@code slots} is untouched.
   */
  public WorkspaceRunner quarantine(UUID id, String reason) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkspaceRunner runner = runners.findById(id, LockModeType.PESSIMISTIC_WRITE);
              if (runner == null || runner.quarantined()) {
                return null;
              }
              runner.quarantinedAt = Instant.now();
              runner.quarantineReason = reason;
              return runner;
            });
  }

  /**
   * Puts a runner back into service: an admin's greenlight, or a health check that passed. A runner
   * already in service is answered as it is.
   *
   * @throws NotFoundException for no such runner
   */
  public WorkspaceRunner greenlight(UUID id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkspaceRunner runner = runners.findById(id, LockModeType.PESSIMISTIC_WRITE);
              if (runner == null) {
                throw notFound(id);
              }
              runner.quarantinedAt = null;
              runner.quarantineReason = null;
              return runner;
            });
  }

  /**
   * Records a settled health check ({@code last_health_check_*}). What the result does to the
   * runner's standing is the caller's next call: {@link #greenlight} on a pass, {@link #quarantine}
   * on a failure. So the check is recorded first, whatever follows.
   *
   * @return the row as it now is, or null for a runner deleted meanwhile
   */
  public WorkspaceRunner recordHealthCheck(UUID id, boolean ok, Instant at) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkspaceRunner runner = runners.findById(id, LockModeType.PESSIMISTIC_WRITE);
              if (runner == null) {
                return null;
              }
              runner.lastHealthCheckAt = at == null ? Instant.now() : at;
              runner.lastHealthCheckOk = ok;
              return runner;
            });
  }

  /**
   * Deletes the row and answers what it held, so the caller can give the runner's client and token
   * back at qits-idp and tell a connected runner it was deleted. 409 {@code
   * RUNNER_OWNS_WORKSPACES}, naming the row ids, while the runner owns an ACTIVE workspace.
   *
   * <p>The row goes <b>first</b> and the credentials after, as in qits-ci: the row is what every
   * door and the socket check a runner against, so once it is gone the credentials open nothing
   * here, and a qits-idp that could not be reached leaves leftovers the commission reconciler reaps
   * by that same absence.
   */
  public WorkspaceRunner delete(UUID id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkspaceRunner runner = found(id);
              if (workspaces.countActiveOnRunner(id) > 0) {
                List<Long> owned = workspaces.findActiveIdsOnRunner(id);
                throw new RunnerOwnsWorkspacesException(
                    RunnerOwnsWorkspacesException.CODE
                        + ": runner "
                        + runner.name
                        + " owns "
                        + owned.size()
                        + " active workspace(s) "
                        + owned
                        + "; resolve them before deleting it",
                    owned);
              }
              runners.delete(runner);
              return runner;
            });
  }

  /** The runner as an operator reads it. */
  public WorkspaceRunnerDto view(WorkspaceRunner runner) {
    return mapper.toDto(runner);
  }

  /** Every runner as an operator reads it, by name. */
  public List<WorkspaceRunnerDto> views() {
    return list().stream().map(mapper::toDto).toList();
  }

  private WorkspaceRunner found(UUID id) {
    WorkspaceRunner runner = runners.findById(id);
    if (runner == null) {
      throw notFound(id);
    }
    return runner;
  }

  private static NotFoundException notFound(UUID id) {
    return new NotFoundException("No workspace runner " + id);
  }

  private static ConflictException registeredAlready(WorkspaceRunner runner) {
    return new ConflictException("Runner " + runner.name + " is already registered");
  }

  /** {@link WorkspaceRunnerCapabilities#merge}, its refusal turned into a 400. */
  private static String capabilitiesOf(String stored, JsonNode said) {
    try {
      return WorkspaceRunnerCapabilities.merge(stored, said);
    } catch (IllegalArgumentException refused) {
      throw new BadRequestException(refused.getMessage());
    }
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text;
  }
}
