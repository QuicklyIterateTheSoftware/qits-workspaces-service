package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceImage;
import eu.wohlben.qits.workspaceeditor.WorkspaceEditorImage;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Says out loud, once at boot, that a retired configuration key is set and is no longer read.
 *
 * <h2>What is retired, and why the entries are still there</h2>
 *
 * <p>Until 2026-09-16 the workspace and editor image versions came from {@code
 * qits.workspace.image-version} and {@code qits.editor.image-version}, written into this
 * deployment's configuration by qits-configuration's release listener on every image release. Both
 * halves of that are gone: the listener no longer authors those rows, and {@link
 * WorkspaceContainerFactory} takes each version from the dependency this reactor pins, whose own
 * version <em>is</em> the image tag.
 *
 * <p><b>But the entries already written are still in every deployment's environment.</b> Nothing on
 * this platform deletes a configuration entry — qits-configuration's own service says so, "an orphan
 * is reported and never cleaned up" — and this service holds no credential that could. What makes
 * them stop deciding is that the override moved to {@code …-version-override}: a different name, so
 * the residue is read by nobody.
 *
 * <h2>Why a warning rather than nothing</h2>
 *
 * <p>Inert and invisible is the wrong pair. A value sitting in the environment that <em>looks</em>
 * like it decides which image a workspace starts, and does not, is exactly the sort of thing that
 * costs somebody an afternoon — and this process is the only reader placed to notice it. So it names
 * the key, says what replaced it, and says it can be deleted: residue becomes a work item somebody
 * with {@code qits:admin} closes whenever, instead of a prerequisite for this change to work.
 *
 * <p><b>A WARN and never a refusal.</b> The entry is harmless by construction. Refusing to start
 * over a stale key would turn a tidy-up into an outage, and this service starting is worth more than
 * this service being fastidious.
 *
 * <h2>Why it is its own bean</h2>
 *
 * <p>It lived on {@link WorkspaceContainerFactory} for one commit, which was a mistake worth
 * recording: observing {@link StartupEvent} forces the observing bean to be created at boot, and
 * that factory carries required config with no defaults ({@code qits.projects.url}, the oidc-client
 * block). Every {@code @QuarkusTest} in this module that had never needed the factory suddenly had
 * to satisfy all of it, and the domain suite went red on config rather than on behaviour. A bean
 * whose every property is {@code Optional} can be created anywhere, which is what this one is.
 */
@ApplicationScoped
public class RetiredImageVersionKeys {

  private static final Logger LOG = Logger.getLogger(RetiredImageVersionKeys.class);

  /** What the release listener used to write for the workspace image. */
  @ConfigProperty(name = "qits.workspace.image-version")
  Optional<String> retiredWorkspaceKey;

  /** …and for the editor image. */
  @ConfigProperty(name = "qits.editor.image-version")
  Optional<String> retiredEditorKey;

  void onStart(@Observes StartupEvent startup) {
    warnIfSet("qits.workspace.image-version", retiredWorkspaceKey, WorkspaceImage.VERSION);
    warnIfSet("qits.editor.image-version", retiredEditorKey, WorkspaceEditorImage.VERSION);
  }

  /**
   * Blank counts as unset, for the reason every override here does: SmallRye maps an environment
   * variable onto the property, and a deployment that renders {@code KEY=} produces a present, empty
   * value rather than an absent one. Warning about that would be warning about a template with
   * nothing in it.
   */
  private static void warnIfSet(String key, Optional<String> value, String pinned) {
    value
        .filter(set -> !set.isBlank())
        .ifPresent(
            set ->
                LOG.warnf(
                    "%s is set to '%s' and is NO LONGER READ: this service takes the image version"
                        + " from the dependency it pins (%s). Set %s-override to pin a different"
                        + " image deliberately; otherwise this entry can be deleted.",
                    key, set, pinned, key));
  }
}
