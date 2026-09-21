package eu.wohlben.qits.workspaces.dto;

/**
 * A subagent the session spawned, with the labels the harness recorded beside its sidechain.
 *
 * <p>{@code agentType} and {@code description} are agent-written free text, clamped to 255 and 1024
 * characters respectively — the same clamp the harness library applies when it reads the same meta
 * file — because they are rendered, not interpreted.
 */
public record ArchivedSubagentDto(
    String agentId, String agentType, String description, int messageCount) {}
