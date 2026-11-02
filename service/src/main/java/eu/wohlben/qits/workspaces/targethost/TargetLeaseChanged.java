package eu.wohlben.qits.workspaces.targethost;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * qits-system's {@code TargetLeaseChanged} (epic qits-1481), transcribed rather than depended on —
 * the wire contract, not a type, crosses from another context. The record is spelled exactly as
 * qits-system's ({@code components/qits-system/qits-system-service/targets/src/main/java/eu/wohlben/qits/system/targets/events/TargetLeaseChanged.java}):
 * its simple name is the event's name on the bus, and its components are the payload's keys. A
 * change there is a change here, in the same campaign; {@code TargetLeaseChangedContractTest} holds
 * the two together.
 *
 * <p>Published on every state change of a lease, on every change of a queued lease's {@code
 * detail}, and now also when {@code oomKills} changes on a running lease (qits-1818). {@code
 * targetId} is null while QUEUED, {@code exitCode} is set for EXITED, {@code detail} names why a
 * lease waits or failed.
 *
 * <p>{@code oomKills} is the lease's container's processes the OOM killer has taken (cgroup {@code
 * memory.events oom_kill}), the last sample kept after exit; {@code oomScope} is which killer acted
 * — {@code LEASE} (the lease hit its own memory limit) or {@code NODE} (the node ran out and the
 * killer picked a process in this lease) — null exactly when {@code oomKills} is null or 0.
 */
public record TargetLeaseChanged(
    UUID eventId,
    String leaseId,
    String ownerApp,
    String ownerRef,
    String slotType,
    String targetId,
    String state,
    Integer exitCode,
    String detail,
    Long oomKills,
    String oomScope,
    Instant occurredAt)
    implements QitsEvent {}
