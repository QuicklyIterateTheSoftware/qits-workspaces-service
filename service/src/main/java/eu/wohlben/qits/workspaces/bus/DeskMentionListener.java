package eu.wohlben.qits.workspaces.bus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.workspaces.control.AgentAttentions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>An {@code @desk} comment on a work item</b> (epic qits-1681, "Attention"): qits-projects'
 * {@link EntityCommented} whose {@code mentions} name {@code desk} records, on the item's ACTIVE
 * agent, when the desk was asked ({@code desk_asked_at = commentedAt}), and evaluates the agent's
 * attention again — a {@code BLOCKED_NO_DESK} ends with the first such comment after the block
 * began. {@link AgentAttentions#onDeskMention} holds the semantics.
 *
 * <p><b>Durable</b>, as {@link AgentWaitListener} is, under a consumer id of its own: a missed
 * comment would leave a blocked agent reading "no @desk comment since" for good. The payload is
 * bound to a transcribed record of the three fields read (the {@code targethost/TargetLeaseChanged}
 * pattern), registered for the native image in {@link EventWireReflection}. A payload that will not
 * bind is logged and settled: it can never bind on a second delivery either. A row that cannot be
 * written throws, and the event stays owed.
 */
@ApplicationScoped
public class DeskMentionListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(DeskMentionListener.class);

  /**
   * The storage key of this consumer's claims and watermark. Never change it, and never reuse it:
   * a new value is a new consumer starting at the head of the log.
   */
  static final String CONSUMER_ID = "qits-workspaces.desk-mentions";

  /** The mention that asks the project's front desk. */
  static final String DESK = "desk";

  @Inject AgentAttentions attentions;

  @Inject ObjectMapper json;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(EntityCommented.class.getSimpleName());
  }

  @Override
  public void onFrame(EventFrame frame) {
    EntityCommented comment;
    try {
      comment =
          json.readerFor(EntityCommented.class)
              .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .readValue(frame.payload());
    } catch (Exception unreadable) {
      LOG.warnf("EntityCommented %s does not bind; settled: %s", frame.id(), unreadable.getMessage());
      return;
    }
    if (comment == null || comment.mentions() == null || !mentionsDesk(comment)) {
      return;
    }
    attentions.onDeskMention(
        comment.entityId(),
        comment.commentedAt() != null ? comment.commentedAt() : frame.occurredAt());
  }

  private static boolean mentionsDesk(EntityCommented comment) {
    return comment.mentions().stream()
        .anyMatch(m -> m != null && DESK.equals(m.trim().toLowerCase(Locale.ROOT)));
  }
}
