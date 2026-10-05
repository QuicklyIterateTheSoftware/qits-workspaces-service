package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composes the {@link RunnerLaunchSpec} a runner launches a RUNNER workspace from (qits-851).
 *
 * <p><b>The same row facts as the DIRECT spec, through the same code.</b> The identity and
 * behaviour environment and the cache/home environment are {@link
 * WorkspaceContainerFactory#identityEnv} and {@link WorkspaceContainerFactory#homeEnv}, the methods
 * {@link WorkspaceContainerFactory#forWorkspace} itself writes them with, so the daemon on a runner
 * is told what the daemon on the platform host is told and the two cannot drift. {@code TZ} and the
 * commit identity are the factory's readings too.
 *
 * <p><b>What it leaves out is the epic's stated limitation</b>: every address and credential a
 * container uses to reach home — the dial-home URL, the git base, the MCP addresses, the proxy
 * paths, the daemon API token, the commissioned pair — and the host-side placement fields: no
 * network, no extra host, no docker socket (admin rows are never RUNNER), no user. qits-625 adds
 * the address plane and the per-workspace token to this composition; until then the in-container
 * daemon starts with no URL and idles.
 *
 * <p><b>The image is the public reference</b>, {@code registry.qits.<domain>/qits/workspace:<v>},
 * because a runner pulls through the edge with its own client; {@code <v>} is the version a DIRECT
 * launch uses ({@link WorkspaceContainerFactory#imageVersion}: the pin, unless an operator
 * overrode it). With no {@code QITS_DOMAIN} there is no public registry to name, and composing
 * refuses rather than guessing one.
 */
@ApplicationScoped
public class RunnerWorkspaceSpecs {

  /** The path of the workspace image in the platform registry, under the public host. */
  static final String WORKSPACE_IMAGE_PATH = "qits/workspace";

  /** The label namespace a runner owns; the server never writes a key under it. */
  public static final String RUNNER_LABEL_NAMESPACE = "qits.workspaces.runner.";

  @Inject WorkspaceContainerFactory factory;

  /**
   * The launch spec for {@code row}.
   *
   * @throws IllegalStateException when the platform's public domain is not configured
   */
  public RunnerLaunchSpec compose(Workspace row) {
    String domain =
        factory
            .publicDomain()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "qits.workspace.domain (QITS_DOMAIN) is unset, so there is no public"
                            + " registry a runner could pull the workspace image from"));
    return compose(
        row, "registry.qits." + domain + "/" + WORKSPACE_IMAGE_PATH + ":" + factory.imageVersion());
  }

  /**
   * The launch spec for {@code row}, pulling {@code image}: the public reference the caller's
   * runner addresses compose ({@code WorkspaceRunnerAddresses.workspaceImage} in {@code service}),
   * so the {@code take}, the {@code estate} and the login command name one image.
   */
  public RunnerLaunchSpec compose(Workspace row, String image) {
    if (image == null || image.isBlank()) {
      throw new IllegalStateException("A runner launch spec needs the workspace image to pull");
    }

    Map<String, String> env = new LinkedHashMap<>();
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("qits.repository", row.repositoryId);
    labels.put("qits.workspace", row.workspaceId);
    labels.put("qits.branch", row.branch == null ? "" : row.branch);
    labels.put("qits.parent", row.parent == null ? "" : row.parent);
    env.put("TZ", factory.containerTimezone());
    factory.identityEnv(
        row.repositoryId,
        row.workspaceId,
        row.id,
        row.branch,
        row.parent,
        row.entityId,
        row.editor,
        env::put,
        labels::put);
    env.putAll(factory.gitIdentityEnv());
    // A runner mounts all four on its node, whatever this deployment's shared volumes are: the
    // agent home and the caches are node volumes there, never the platform host's.
    factory.homeEnv(true, true, true, env::put);
    labels.keySet().removeIf(key -> key.startsWith(RUNNER_LABEL_NAMESPACE));

    List<RunnerLaunchSpec.Mount> mounts =
        List.of(
            new RunnerLaunchSpec.Mount(RunnerLaunchSpec.Volume.WORKSPACE, "/workspace"),
            new RunnerLaunchSpec.Mount(RunnerLaunchSpec.Volume.DOT_CLAUDE, factory.claudeMount()),
            new RunnerLaunchSpec.Mount(
                RunnerLaunchSpec.Volume.M2, WorkspaceContainerFactory.MAVEN_MOUNT),
            new RunnerLaunchSpec.Mount(
                RunnerLaunchSpec.Volume.PNPM, WorkspaceContainerFactory.PNPM_MOUNT));
    // Unmodifiable views, never Map.copyOf: the copy's iteration order is salted per JVM, and env
    // order is part of a spec.
    return new RunnerLaunchSpec(
        image,
        Collections.unmodifiableMap(env),
        mounts,
        Collections.unmodifiableMap(labels),
        factory.limits(),
        true);
  }
}
