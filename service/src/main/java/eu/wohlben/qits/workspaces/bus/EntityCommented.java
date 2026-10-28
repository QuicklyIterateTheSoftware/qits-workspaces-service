package eu.wohlben.qits.workspaces.bus;

import java.time.Instant;
import java.util.List;

/**
 * qits-projects' {@code EntityCommented}, transcribed rather than depended on, and only the fields
 * {@link DeskMentionListener} reads (epic qits-1681): its simple name is the event's name on the
 * bus, its components are payload keys, and every other key of the payload ({@code commentId},
 * {@code projectId}, {@code archetype}, {@code number}, {@code title}, {@code author}, {@code
 * authorAgentOf}, {@code changedBy}) is ignored. {@code mentions} is lower case, without the
 * {@code @}.
 */
public record EntityCommented(String entityId, List<String> mentions, Instant commentedAt) {}
