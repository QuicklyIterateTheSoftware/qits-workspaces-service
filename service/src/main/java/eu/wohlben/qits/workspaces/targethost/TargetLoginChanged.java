package eu.wohlben.qits.workspaces.targethost;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * qits-system's {@code TargetLoginChanged} (qits-2088), transcribed rather than depended on, as
 * {@link TargetLeaseChanged} is: the record is spelled exactly as qits-system's ({@code
 * components/qits-system/qits-system-service/targets/src/main/java/eu/wohlben/qits/system/targets/events/TargetLoginChanged.java}),
 * its simple name is the event's name on the bus and its components are the payload's keys. {@code
 * TargetLoginChangedContractTest} holds the two together.
 *
 * <p>Published when either harness's sign-in on a target ({@code claude}, {@code kimi}: {@code
 * PRESENT}, {@code ABSENT}, {@code UNKNOWN}) is not what qits-system held before. {@code checkedAt}
 * is the runner's probe instant, ISO-8601, as a string. {@code targetName}, not {@code name}: a
 * component named {@code name} would shadow {@link QitsEvent#name()}.
 */
public record TargetLoginChanged(
    UUID eventId,
    String targetId,
    String targetName,
    String claude,
    String kimi,
    String checkedAt,
    Instant occurredAt)
    implements QitsEvent {}
