package eu.wohlben.qits.workspaces.control;

/**
 * Files a ticket in qits-projects (qits-1152, D25): what a capture does now instead of creating a
 * workspace. A person then decides whether to dispatch an agent for it. Shipped as {@code
 * wiring/HttpWorkFiler} → {@code POST /projects/api/work}.
 */
public interface WorkFiler {

  /** The ticket qits-projects made. */
  record Filed(String id, String qualifiedId) {}

  /**
   * @param projectId the project the captured repository belongs to
   * @param title the ticket's title
   * @param impetus why it exists: the rendered capture
   * @throws RuntimeException when qits-projects refused or could not be reached; nothing was filed
   */
  Filed file(String projectId, String title, String impetus);
}
