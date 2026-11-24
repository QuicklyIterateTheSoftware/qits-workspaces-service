package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * Reads a work item from qits-projects (qits-2596): what a project agent's wait names in {@code
 * work}, checked to be an EPIC or a TICKET of the caller's own project before the wait is declared.
 *
 * <p>A port for the reason {@link WaitSubjectResolver} is one: the transport is qits-projects'
 * {@code getWork} and lives in {@code service} ({@code wiring/HttpWorkItemLookup}, the same client a
 * wait's work-item lookup uses). Injected as {@code Instance<>}.
 */
public interface WorkItemLookup {

  /**
   * The four fields a project agent's wait reads of its item.
   *
   * @param id the item's uuid
   * @param qualifiedId e.g. {@code qits-123}
   * @param archetype {@code EPIC}, {@code TICKET}, {@code FEATURE}, {@code TASK}, …
   * @param projectId the project's uuid
   */
  record WorkItem(String id, String qualifiedId, String archetype, String projectId) {}

  /**
   * The item {@code ref} names — its uuid or its qualified id.
   *
   * @return empty when qits-projects knows no such item (a 404)
   * @throws RuntimeException when qits-projects could not be asked, or answered anything else
   */
  Optional<WorkItem> find(String ref);
}
