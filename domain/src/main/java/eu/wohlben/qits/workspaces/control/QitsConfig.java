package eu.wohlben.qits.workspaces.control;

import java.util.List;
import java.util.Map;

/**
 * The parsed, framework-free representation of a workspace checkout's committed qits config file
 * ({@code .config/qits/repository.yml}, or the legacy root-level {@code .qits-config.yml} as
 * fallback). The file is <strong>authoritative</strong>: it is read in-container by the
 * workspace-daemon and surfaced to the host over the control socket as the Part-2 wire schema
 * ({@link WorkspaceConfigView} wraps it; {@code WorkspaceDaemonRegistry} Jackson-deserializes it).
 * There is no host-side DB config store and no reconciler — declared actions and bootstrap steps
 * live only in the file.
 *
 * <p>Every declared entry carries an explicit, deterministic string {@code id:} (defaulting to its
 * {@code name} when absent) that identifies it across the wire; a duplicate id is a user error,
 * allowed to collide.
 *
 * <p>The {@code targets} list registers this record <em>and every nested one</em> for reflection,
 * because {@code WorkspaceDaemonRegistry} deserializes it with an injected {@code ObjectMapper} and
 * nothing on a JAX-RS signature ever mentions it — so native-image has no reason to keep the
 * members, and the daemon's first {@code configView} frame would come back as {@link #EMPTY} with a
 * "doesn't map to a QitsConfig" warning that looks like a daemon bug. {@code @RegisterForReflection}
 * does not descend into nested types, so a record added below has to be added here too. See {@link
 * WorkspaceMetadata} for the same defect caught the harder way.
 *
 * <p><b>The enums are targets too.</b> Jackson resolves an enum's constants reflectively, so an
 * enum-typed field added to any record below must be registered as well, or the binary's
 * deserialization throws — caught, and degraded to {@link #EMPTY}, while the JVM suite stays green.
 * None is bound today; {@code NativeImageContractTest} walks the tree so the next one is caught.
 *
 * <p><b>Unknown properties are ignored, on purpose.</b> A daemon built before the workspace
 * services concept was removed (qits-947) still sends the {@code services:} (and legacy {@code
 * daemons:}) key in its config view. The record no longer has a component for it, and the
 * annotation below — not whatever the injected mapper's defaults happen to be — is what keeps such
 * a config from degrading to {@link #EMPTY} and losing its bootstrap chain with it.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
@io.quarkus.runtime.annotations.RegisterForReflection(
    targets = {
      QitsConfig.class,
      QitsConfig.RepositorySection.class,
      QitsConfig.FrameworkDecl.class,
      QitsConfig.ActionDecl.class,
      QitsConfig.BootstrapDecl.class
    })
public record QitsConfig(
    RepositorySection repository,
    List<FrameworkDecl> frameworks,
    List<ActionDecl> actions,
    List<BootstrapDecl> bootstrap) {

  /** An absent/empty file — the no-op that keeps a config-free workspace on the old path. */
  public static final QitsConfig EMPTY = new QitsConfig(null, List.of(), List.of(), List.of());

  /** Normalize the collections to non-null so callers never null-check. */
  public QitsConfig {
    frameworks = frameworks == null ? List.of() : List.copyOf(frameworks);
    actions = actions == null ? List.of() : List.copyOf(actions);
    bootstrap = bootstrap == null ? List.of() : List.copyOf(bootstrap);
  }

  public boolean isEmpty() {
    return repository == null
        && frameworks.isEmpty()
        && actions.isEmpty()
        && bootstrap.isEmpty();
  }

  /**
   * The {@code repository:} section: fields the file may own on the repository itself.
   *
   * <p>{@code archetype} is a plain String here, not the repositories context's {@code
   * RepositoryArchetype} enum: this context only carries the value through from the daemon's {@code
   * ConfigView} — it never branches on it, and the enum's skeleton-directory behaviour and Flyway
   * check-constraint belong to whoever owns repositories. An unrecognized value therefore
   * round-trips instead of failing deserialization.
   */
  public record RepositorySection(String mainBranch, String archetype) {}

  /** One {@code frameworks[]} entry — a detection override/hint, consumed live, never stored. */
  public record FrameworkDecl(String kind, String root) {}

  /** One {@code actions[]} entry — a config-declared workspace action. */
  public record ActionDecl(
      String id,
      String name,
      String description,
      String execute,
      String check,
      boolean interactive,
      Map<String, String> environment) {
    /** {@code id} defaults to {@code name} when absent/blank. */
    public ActionDecl {
      id = id == null || id.isBlank() ? name : id;
    }
  }

  /**
   * One {@code bootstrap[]} entry — a config-declared bootstrap step; list position is the
   * execution order.
   */
  public record BootstrapDecl(
      String id,
      String name,
      String description,
      String execute,
      String check,
      Map<String, String> environment) {
    /** {@code id} defaults to {@code name} when absent/blank. */
    public BootstrapDecl {
      id = id == null || id.isBlank() ? name : id;
    }
  }
}
