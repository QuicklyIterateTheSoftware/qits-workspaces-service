package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.dto.CaptureContent;
import eu.wohlben.qits.workspaces.error.InternalServerErrorException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * The capture ingest's semantics (qits-1152, D25): <b>a capture files a ticket and stops</b>. No
 * workspace, no branch and no agent; a person decides whether to dispatch one for it.
 *
 * <p>The ticket lands in the project of the repository the capturing page names, titled after the
 * page, with the rendered capture ({@link CaptureGoalRenderer}) as its impetus.
 */
@ApplicationScoped
public class CaptureService {

  @Inject RepositoryLookup repositories;

  @Inject CaptureGoalRenderer goalRenderer;

  /** Absent: no ticket can be filed, and a capture is a 500 saying so. */
  @Inject Instance<WorkFiler> filer;

  /**
   * @throws NotFoundException the payload names no repository, or one the registry does not know
   */
  public WorkFiler.Filed capture(String repoId, CaptureContent content, Instant receivedAt) {
    if (repoId == null || repoId.isBlank()) {
      throw new NotFoundException("Capture payload names no repository");
    }
    RepositoryLookup.RepositoryView repo = repositories.require(repoId);
    if (repo.projectId() == null || repo.projectId().isBlank()) {
      throw new NotFoundException("Repository " + repoId + " belongs to no project");
    }
    if (!filer.isResolvable()) {
      throw new InternalServerErrorException("No ticket filer is configured");
    }
    return filer.get().file(repo.projectId(), titleOf(content), goalRenderer.render(content, receivedAt));
  }

  static String titleOf(CaptureContent content) {
    String where = null;
    if (content != null && content.page() != null) {
      where = firstPresent(content.page().title(), content.page().routePattern(), content.page().appPath());
    }
    String title = "Captured: " + (where == null ? "a page" : where);
    return title.length() > 200 ? title.substring(0, 200) : title;
  }

  private static String firstPresent(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }
}
