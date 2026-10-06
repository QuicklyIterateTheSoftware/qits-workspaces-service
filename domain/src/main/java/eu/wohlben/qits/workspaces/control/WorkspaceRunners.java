package eu.wohlben.qits.workspaces.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto;
import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerHealthDto;
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
 *
 * <p><b>The workspace memory limits are the operator's, per runner</b> (qits-951), as qits-ci's
 * step memory limit is: two docker sizes on the row, validated at create and patch ({@link
 * #MEMORY_LIMIT}, {@link #requireMemoryPair}), and read by {@code RunnerWorkspaceSpecs} when a
 * runner takes a workspace. Unset is the platform's own {@code qits.workspace.memory-limit} and
 * {@code memory-swap-limit}.
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

  /**
   * A runner's workspace memory limit, and its swap limit: qits-ci's {@code STEP_MEMORY_LIMIT}
   * grammar — digits and an optional {@code b}, {@code k}, {@code m} or {@code g} — the docker size
   * a runner passes on as {@code --memory}/{@code --memory-swap} (qits-951). Checked here, at the
   * door, so a typo is a 400 to the operator rather than a launch the runner's docker refuses.
   */
  public static final Pattern MEMORY_LIMIT = Pattern.compile("[0-9]{1,15}[bkmgBKMG]?");

  /**
   * The smallest memory limit a runner's workspaces are given, in bytes: docker's own floor for
   * {@code --memory} (6 MiB). It is also why {@code 0}, which some docker versions read as "no limit
   * at all", is not a value here.
   */
  public static final long MEMORY_LIMIT_MIN_BYTES = 6L * 1024 * 1024;

  /** The one swap limit that is not a size: docker's {@code --memory-swap -1}, unlimited swap. */
  public static final String UNLIMITED_SWAP = "-1";

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
   * 400 unless {@code limit} is a workspace memory limit a runner can apply — see {@link
   * #MEMORY_LIMIT} and {@link #MEMORY_LIMIT_MIN_BYTES}. Null and blank pass: both mean the platform
   * default, and which of "leave it" or "clear it" they are is the caller's.
   */
  public static void requireWorkspaceMemoryLimit(String limit) {
    requireMemorySize("workspaceMemoryLimit", limit, false);
  }

  /**
   * 400 unless {@code limit} is a workspace swap limit: a {@link #MEMORY_LIMIT} size, or {@link
   * #UNLIMITED_SWAP}. Null and blank pass, as for {@link #requireWorkspaceMemoryLimit}. Whether it
   * fits the memory limit beside it is {@link #requireMemoryPair}'s, on the row's resulting values.
   */
  public static void requireWorkspaceMemorySwapLimit(String limit) {
    requireMemorySize("workspaceMemorySwapLimit", limit, true);
  }

  /**
   * 400 unless the two limits a row would hold go together. Docker's {@code --memory-swap} is the
   * TOTAL of memory and swap, so a set swap below the memory is refused, and a set swap with no
   * memory of the row's own is refused too: it would be paired with the platform's default memory,
   * a value this row does not state and an operator can change under it. {@link #UNLIMITED_SWAP}
   * fits any memory, the default included. Both arguments are stored values: trimmed, null for
   * unset, each already valid on its own.
   */
  public static void requireMemoryPair(String memory, String swap) {
    if (swap == null || UNLIMITED_SWAP.equals(swap)) {
      return;
    }
    if (memory == null) {
      throw new BadRequestException(
          "workspaceMemorySwapLimit needs workspaceMemoryLimit: the swap limit is memory plus swap,"
              + " so it is set only beside a memory limit of the runner's own (or -1 for unlimited"
              + " swap)");
    }
    if (bytesOf(swap) < bytesOf(memory)) {
      throw new BadRequestException(
          "workspaceMemorySwapLimit is memory plus swap, so it cannot be below"
              + " workspaceMemoryLimit ("
              + swap
              + " < "
              + memory
              + ")");
    }
  }

  private static void requireMemorySize(String field, String limit, boolean unlimitedAllowed) {
    if (limit == null || limit.isBlank()) {
      return;
    }
    String value = limit.strip();
    if (unlimitedAllowed && UNLIMITED_SWAP.equals(value)) {
      return;
    }
    if (!MEMORY_LIMIT.matcher(value).matches()) {
      throw new BadRequestException(
          field
              + " is a docker size: digits and an optional unit b, k, m or g (e.g. 12g, 12288m)"
              + (unlimitedAllowed ? ", -1 for unlimited swap," : ",")
              + " or blank for the platform default");
    }
    long bytes = bytesOf(value);
    if (bytes < 0) {
      throw new BadRequestException(field + " " + value + " is larger than any machine");
    }
    if (bytes < MEMORY_LIMIT_MIN_BYTES) {
      throw new BadRequestException(
          field + " is at least 6m — docker refuses a smaller --memory");
    }
  }

  /**
   * A {@link #MEMORY_LIMIT} size in bytes, or -1 when it overflows a long. Only ever asked about a
   * value the grammar has already matched.
   */
  static long bytesOf(String size) {
    char unit = Character.toLowerCase(size.charAt(size.length() - 1));
    long factor =
        switch (unit) {
          case 'k' -> 1024L;
          case 'm' -> 1024L * 1024;
          case 'g' -> 1024L * 1024 * 1024;
          default -> 1L;
        };
    String digits = Character.isDigit(unit) ? size : size.substring(0, size.length() - 1);
    long amount = Long.parseLong(digits);
    if (amount > Long.MAX_VALUE / factor) {
      return -1;
    }
    return amount * factor;
  }

  /** The value a memory limit is stored as: trimmed, and null for null or blank. */
  static String memoryLimitOf(String limit) {
    return limit == null || limit.isBlank() ? null : limit.strip();
  }

  /**
   * Everything a create can be refused for, asked <b>before</b> a registration token is
   * commissioned: a malformed name, slots or description (400), and a taken name (409).
   */
  public void requireCreatable(String name, String description, Integer slots) {
    requireCreatable(name, description, slots, null, null);
  }

  /**
   * {@link #requireCreatable(String, String, Integer)}, and the two workspace memory limits with it
   * (400 for a malformed one, or a pair that does not fit — {@link #requireMemoryPair}). Null or
   * blank is unset: the platform default.
   */
  public void requireCreatable(
      String name,
      String description,
      Integer slots,
      String workspaceMemoryLimit,
      String workspaceMemorySwapLimit) {
    requireName(name);
    requireSlots(slots);
    requireDescription(description);
    requireWorkspaceMemoryLimit(workspaceMemoryLimit);
    requireWorkspaceMemorySwapLimit(workspaceMemorySwapLimit);
    requireMemoryPair(
        memoryLimitOf(workspaceMemoryLimit), memoryLimitOf(workspaceMemorySwapLimit));
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
    return create(
        id, name, description, slots, null, null, registrationTokenId, registrationTokenSubject);
  }

  /**
   * {@link #create(UUID, String, String, Integer, String, String)}, with the runner's two workspace
   * memory limits: null or blank leaves each unset, the platform default.
   *
   * @throws ConflictException when the name was taken in between
   */
  public WorkspaceRunner create(
      UUID id,
      String name,
      String description,
      Integer slots,
      String workspaceMemoryLimit,
      String workspaceMemorySwapLimit,
      String registrationTokenId,
      String registrationTokenSubject) {
    requireCreatable(name, description, slots, workspaceMemoryLimit, workspaceMemorySwapLimit);
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                WorkspaceRunner runner = new WorkspaceRunner();
                runner.id = Objects.requireNonNull(id, "id");
                runner.name = name;
                runner.description = blankToNull(description);
                runner.slots = slots == null ? DEFAULT_SLOTS : slots;
                runner.workspaceMemoryLimit = memoryLimitOf(workspaceMemoryLimit);
                runner.workspaceMemorySwapLimit = memoryLimitOf(workspaceMemorySwapLimit);
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
    return patch(id, slots, description, null, null);
  }

  /**
   * {@link #patch(UUID, Integer, String)}, and the two workspace memory limits with it, each as
   * qits-ci patches a step memory limit: null leaves it, blank clears it back to the platform
   * default, anything else must be a {@link #MEMORY_LIMIT} (or, for the swap, {@link
   * #UNLIMITED_SWAP}). The pair is checked as the row would hold it after the change ({@link
   * #requireMemoryPair}), so clearing the memory under a set swap is refused as setting a swap below
   * a stored memory is, and nothing is written. Nothing is pushed to a connected runner: the limits
   * travel in each {@code take}, read off the row when the runner takes a workspace, so a change
   * reaches its next launch and never a container already running.
   */
  public WorkspaceRunner patch(
      UUID id,
      Integer slots,
      String description,
      String workspaceMemoryLimit,
      String workspaceMemorySwapLimit) {
    requireSlots(slots);
    requireDescription(description);
    requireWorkspaceMemoryLimit(workspaceMemoryLimit);
    requireWorkspaceMemorySwapLimit(workspaceMemorySwapLimit);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkspaceRunner runner = found(id);
              String memory =
                  workspaceMemoryLimit == null
                      ? runner.workspaceMemoryLimit
                      : memoryLimitOf(workspaceMemoryLimit);
              String swap =
                  workspaceMemorySwapLimit == null
                      ? runner.workspaceMemorySwapLimit
                      : memoryLimitOf(workspaceMemorySwapLimit);
              // Before any field moves: a refusal throws out of the transaction, which rolls back.
              requireMemoryPair(memory, swap);
              if (slots != null) {
                runner.slots = slots;
              }
              if (description != null) {
                runner.description = blankToNull(description);
              }
              runner.workspaceMemoryLimit = memory;
              runner.workspaceMemorySwapLimit = swap;
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
   * What {@link #quarantineFor} did: the row as it now is (null for a runner that is gone), whether
   * this call took it out of service, and whether its reason changed.
   */
  public record Quarantine(WorkspaceRunner runner, boolean began, boolean reasonChanged) {}

  /**
   * Keeps a runner out of service for {@code reason}: a runner in service is quarantined now, and
   * one already out keeps its {@code quarantined_at} — so a health check's back-off schedule, which
   * counts from it, carries on — and takes {@code reason} as the newest word on why (qits-850).
   */
  public Quarantine quarantineFor(UUID id, String reason) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkspaceRunner runner = runners.findById(id, LockModeType.PESSIMISTIC_WRITE);
              if (runner == null) {
                return new Quarantine(null, false, false);
              }
              boolean began = !runner.quarantined();
              boolean changed = !Objects.equals(runner.quarantineReason, reason);
              if (began) {
                runner.quarantinedAt = Instant.now();
              }
              runner.quarantineReason = reason;
              return new Quarantine(runner, began, changed);
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
    return recordHealthCheck(id, ok, at, null);
  }

  /**
   * {@link #recordHealthCheck(UUID, boolean, Instant)}, keeping {@code report} — the check's whole
   * answer, {@code {at, ok, detail, requestId, checks}} — as the capabilities' {@link
   * WorkspaceRunnerCapabilities#HEALTH} key (qits-850). A null report leaves the last one stored.
   */
  public WorkspaceRunner recordHealthCheck(UUID id, boolean ok, Instant at, ObjectNode report) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkspaceRunner runner = runners.findById(id, LockModeType.PESSIMISTIC_WRITE);
              if (runner == null) {
                return null;
              }
              runner.lastHealthCheckAt = at == null ? Instant.now() : at;
              runner.lastHealthCheckOk = ok;
              if (report != null) {
                runner.capabilities =
                    WorkspaceRunnerCapabilities.withHealth(runner.capabilities, report);
              }
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
                List<RunnerOwnsWorkspacesException.OwnedWorkspace> owned =
                    workspaces.findActiveOnRunner(id).stream()
                        .map(
                            row ->
                                new RunnerOwnsWorkspacesException.OwnedWorkspace(
                                    row.id, row.repositoryId, row.branch))
                        .toList();
                throw new RunnerOwnsWorkspacesException(
                    RunnerOwnsWorkspacesException.CODE
                        + ": runner "
                        + runner.name
                        + " owns "
                        + owned.size()
                        + " active workspace(s) "
                        + owned.stream().map(RunnerOwnsWorkspacesException.OwnedWorkspace::id).toList()
                        + "; resolve them before deleting it",
                    owned);
              }
              runners.delete(runner);
              return runner;
            });
  }

  /**
   * What is on a runner right now, counted from the rows: its live containers (RUNNING +
   * PROVISIONING), its ACTIVE workspaces, and its QUEUED sticky ones.
   */
  public record Counts(int running, int owned, int queued) {}

  /** {@link Counts} for {@code id}, in one transaction. */
  public Counts counts(UUID id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                new Counts(
                    (int) workspaces.countLiveOnRunner(id),
                    (int) workspaces.countActiveOnRunner(id),
                    (int) workspaces.countQueuedOnRunner(id)));
  }

  /** The runner as an operator reads it. */
  public WorkspaceRunnerDto view(WorkspaceRunner runner) {
    return mapper.toDto(runner);
  }

  /** The runner as an operator reads it, with what the service knows live about it. */
  public WorkspaceRunnerDto view(WorkspaceRunner runner, WorkspaceRunnerMapper.Live live) {
    return mapper.toDto(runner, live);
  }

  /**
   * The runner's newest health check in full, every check's data included; null when none has
   * settled yet (qits-850).
   *
   * @throws NotFoundException for no such runner
   */
  public WorkspaceRunnerHealthDto health(UUID id) {
    return mapper.toHealthDto(get(id));
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
