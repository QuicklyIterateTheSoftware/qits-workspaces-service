package eu.wohlben.qits.workspaces.bus;

import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.workspaces.events.AgentCleanupDeferred;
import eu.wohlben.qits.workspaces.events.AgentHarnessExited;
import eu.wohlben.qits.workspaces.events.AgentNudged;
import eu.wohlben.qits.workspaces.events.AgentObsolete;
import eu.wohlben.qits.workspaces.events.AgentPlaced;
import eu.wohlben.qits.workspaces.events.AgentRemoved;
import eu.wohlben.qits.workspaces.events.AgentRequeued;
import eu.wohlben.qits.workspaces.events.AgentResumed;
import eu.wohlben.qits.workspaces.events.AgentStartRefused;
import eu.wohlben.qits.workspaces.events.AgentStarted;
import eu.wohlben.qits.workspaces.events.AgentYielded;
import eu.wohlben.qits.workspaces.events.WorkspaceFailed;
import eu.wohlben.qits.workspaces.events.WorkspaceRemoved;
import eu.wohlben.qits.workspaces.events.WorkspaceStarted;
import eu.wohlben.qits.workspaces.targethost.TargetLeaseChanged;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What the event bus binds to and from JSON here, told to native-image (qits-1236). No code, no
 * bean: the annotation is the entire content. It went with the {@code SCMRelease} publisher on
 * 2026-09-03 and is back with the lifecycle events.
 *
 * <p><b>Why nothing registers these automatically.</b> {@code CanonicalJson} builds its own {@code
 * ObjectMapper} by hand — the canonical form is a wire contract compared byte for byte — so to the
 * build step scanning for what needs reflection, that mapper and everything it touches are
 * invisible. qits-ci measured the cost on a deployed binary: every publish died inside {@code
 * CanonicalJson} with "no serializer found", its JVM suite green and structurally unable to be
 * anything else. This suite cannot see it either; {@code EventWireReflectionTest} guards the
 * registration's completeness, and only the running binary proves it.
 *
 * <p><b>Why these types.</b> The fourteen records are what {@link EventstreamLifecycleAnnouncer}
 * publishes. {@link TargetLeaseChanged} is qits-system's, which {@code
 * targethost/TargetLeaseEvents} receives bound to that record (epic qits-1481). {@link EventEnvelope} is the PUT body. {@link EventFrame} is what arrives on {@code
 * /events/stream} for {@link AgentWaitListener}, which reads the frame's fields and hands the
 * payload on as a string — so it binds nothing else, but the frame itself is bound.
 *
 * <p><b>And the mix-in by name.</b> {@code CanonicalJson$QitsEventMixin} keeps {@code QitsEvent}'s
 * declared methods — {@code eventId} above all — out of a payload, and Jackson finds its
 * {@code @JsonIgnore}s by reflection. Left out, nothing crashes: {@code eventId} simply appears in
 * every payload, which is the worse failure. A string because the class is private.
 */
@RegisterForReflection(
    targets = {
      AgentPlaced.class,
      AgentStarted.class,
      AgentStartRefused.class,
      AgentRequeued.class,
      AgentYielded.class,
      AgentNudged.class,
      AgentResumed.class,
      AgentObsolete.class,
      AgentCleanupDeferred.class,
      AgentRemoved.class,
      AgentHarnessExited.class,
      WorkspaceStarted.class,
      WorkspaceFailed.class,
      WorkspaceRemoved.class,
      TargetLeaseChanged.class,
      EventEnvelope.class,
      EventFrame.class
    },
    classNames = "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin")
public final class EventWireReflection {

  private EventWireReflection() {}
}
