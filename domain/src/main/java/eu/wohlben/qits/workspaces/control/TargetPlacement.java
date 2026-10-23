package eu.wohlben.qits.workspaces.control;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The domain's view of qits-system's leases (epic qits-1481): how {@link WorkspaceService} asks for
 * a WORKSPACE slot for a TARGET row, ends it and purges its work volume, and how {@link
 * TargetLeaseStates} learns what became of it. Implemented in {@code service} over qits-system's
 * HTTP doors ({@code targethost/TargetLeasePlacement}); this module holds no HTTP client.
 *
 * <p>Injected as {@code Instance<TargetPlacement>}. <b>Absent is a supported configuration with one
 * reading: no target can be had.</b> A start answers 409 {@code TARGET_UNAVAILABLE} and leaves the
 * row as it was; a stop or a delete of a row with no lease still writes the row.
 *
 * <p>Nothing here is called inside a transaction: every method is an HTTP call.
 */
public interface TargetPlacement {

  /** How long a start or a delete waits for a previous lease to finish ending. */
  Duration ENDING_DEADLINE = Duration.ofSeconds(60);

  /** The slot type every lease of this service is. */
  String SLOT_TYPE = "WORKSPACE";

  /** {@code ws-<rowId>}: the work key, and so the name of the row's work volume on its target. */
  static String workKey(long rowId) {
    return "ws-" + rowId;
  }

  /** The lease's owner ref: the row id, as qits-system echoes it back. */
  static String ownerRef(long rowId) {
    return Long.toString(rowId);
  }

  /** A lease's life, as qits-system spells it. {@link #UNKNOWN} for a word this build does not know. */
  enum LeaseState {
    QUEUED,
    GRANTED,
    RUNNING,
    EXITED,
    FAILED,
    ENDING,
    ENDED,
    UNKNOWN;

    public static LeaseState parse(String value) {
      if (value == null) {
        return UNKNOWN;
      }
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException unknown) {
        return UNKNOWN;
      }
    }

    /** Every state but ENDED holds the owner ref: qits-system refuses a second lease for it. */
    public boolean live() {
      return this != ENDED;
    }
  }

  /** A retired runner's container and volume a lease takes over (legacy adoption). */
  record Adopt(String container, String volume) {}

  /**
   * What a start asks for.
   *
   * @param rowId the workspace row: the lease's {@code ownerRef} and its work key {@code ws-<rowId>}
   * @param projectId the row's project, or null when it is not known or not a UUID
   * @param spec image, environment, user and limits ({@link RunnerWorkspaceSpecs#compose})
   * @param adopt the retired runner's container and volume, or null
   * @param pinTargetId the one target to consider, or null for any
   */
  record LeaseRequest(
      long rowId, UUID projectId, RunnerLaunchSpec spec, Adopt adopt, UUID pinTargetId) {}

  /**
   * One lease as qits-system answers it, narrowed to what this service reads.
   *
   * @param ownerRef the row id it was asked for, as text
   * @param targetId null while QUEUED
   * @param exitCode set for EXITED
   * @param detail why a QUEUED lease waits, or why it failed or exited
   */
  record Lease(
      UUID id,
      String ownerRef,
      LeaseState state,
      UUID targetId,
      Integer exitCode,
      String detail,
      Instant requestedAt) {

    /** The row this lease was asked for, or null when its owner ref is not one of ours. */
    public Long rowId() {
      try {
        return ownerRef == null ? null : Long.valueOf(ownerRef.trim());
      } catch (NumberFormatException notARow) {
        return null;
      }
    }
  }

  /**
   * One target, as the listing shows it. {@code engineId} and {@code hostname} are null when the
   * listing does not carry them.
   */
  record Target(UUID id, String name, String engineId, String hostname) {}

  /**
   * qits-system refused or did not answer a lease call.
   *
   * @param status the HTTP status, or -1 when nothing answered
   * @param code the refusal's code ({@code IMAGE_NOT_ALLOWED}, {@code LEASE_EXISTS}, …), or null
   */
  final class LeaseRefused extends RuntimeException {
    private final int status;
    private final String code;

    public LeaseRefused(int status, String code, String message, Throwable cause) {
      super(message, cause);
      this.status = status;
      this.code = code;
    }

    public int status() {
      return status;
    }

    public String code() {
      return code;
    }

    /** A refusal about the request itself (4xx): asking again will not change it. */
    public boolean permanent() {
      return status >= 400 && status < 500 && status != 409;
    }
  }

  /** {@code POST /system/api/leases}: the lease, QUEUED (or further, if it moved at once). */
  Lease create(LeaseRequest request);

  /**
   * {@code DELETE /system/api/leases/{id}}: the lease, ENDING or ENDED; empty when qits-system has
   * no such lease, which is the same outcome.
   */
  Optional<Lease> end(UUID leaseId);

  /** {@code GET /system/api/leases/{id}}; empty when there is no such lease. */
  Optional<Lease> get(UUID leaseId);

  /** {@code GET /system/api/leases}: this service's own leases, oldest first. Throws when unreadable. */
  List<Lease> list();

  /**
   * {@code DELETE /system/api/work-volumes/{workKey}}: true when the volume is marked for purging,
   * false when there is none.
   *
   * @throws LeaseRefused 409 {@code WORK_VOLUME_IN_USE} while a lease that has not ENDED uses it
   */
  boolean purgeWorkVolume(String workKey);

  /** {@code GET /system/api/targets}. Throws when unreadable. */
  List<Target> targets();
}
