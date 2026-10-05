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
 * <p><b>Every address comes from the {@link WorkspaceAddressPlane}</b> (qits-625, qits-799): the
 * dial-home socket, the git base and the three MCP servers are public edge names, {@code
 * <app>.qits.<domain>}, and {@code QITS_DOMAIN} is the plane's. A runner's node has no qits-net and
 * no internal DNS, so a RUNNER spec has no network and no extra host — the record has no field for
 * either — and none of the DIRECT spec's wire aliases.
 *
 * <p><b>What it still leaves out</b>: the credential (qits-802 adds the per-workspace token where
 * {@link #compose} says so), the host-side placement fields (no docker socket — admin rows are never
 * RUNNER — and no user), and the proxy paths and the daemon API token.
 *
 * <p><b>The image is the public reference</b>: {@link WorkspaceContainerFactory#image} — the
 * version a DIRECT launch uses, the pin unless an operator overrode it — with its registry host
 * moved to {@code registry.qits.<domain>} by {@link WorkspaceAddressPlane#imageReference}, because a
 * runner pulls through the edge with its own client.
 */
@ApplicationScoped
public class RunnerWorkspaceSpecs {

  /** The label namespace a runner owns; the server never writes a key under it. */
  public static final String RUNNER_LABEL_NAMESPACE = "qits.workspaces.runner.";

  @Inject WorkspaceContainerFactory factory;

  /**
   * The launch spec for {@code row}, every address from {@code plane} ({@link
   * WorkspaceAddressPlanes#plane} builds the configured one, and refuses a deployment with no public
   * domain before anything is composed).
   */
  public RunnerLaunchSpec compose(Workspace row, WorkspaceAddressPlane plane) {
    if (plane == null) {
      throw new IllegalStateException("A runner launch spec needs the address plane");
    }
    String image = plane.imageReference(factory.image());

    Map<String, String> env = new LinkedHashMap<>();
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("qits.repository", row.repositoryId);
    labels.put("qits.workspace", row.workspaceId);
    labels.put("qits.branch", row.branch == null ? "" : row.branch);
    labels.put("qits.parent", row.parent == null ? "" : row.parent);
    env.put("TZ", factory.containerTimezone());
    // The addresses, in the DIRECT spec's order, every one a public edge name off the plane.
    env.put("QITS_WORKSPACE_DAEMON_URL", plane.daemonUrl(row.id));
    env.put("QITS_REPOSITORY_MCP_URL", plane.repositoryMcpUrl());
    env.put("QITS_OBSERVABILITY_MCP_URL", plane.observabilityMcpUrl());
    env.put("QITS_PLATFORM_MCP_URL", plane.platformMcpUrl());
    env.put("QITS_WORKSPACE_DAEMON_GIT_BASE_URL", plane.gitBaseUrl());
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
    // THE CREDENTIAL BLOCK GOES HERE (qits-802): QITS_TOKEN, QITS_TOKEN_SUBJECT,
    // GIT_CONFIG_GLOBAL and QITS_GIT_AUTH_HOST = plane.gitAuthHost(), read off the row — where the
    // DIRECT spec writes its commissioned pair, between the identity and the commit identity.
    env.putAll(factory.gitIdentityEnv());
    // A runner mounts all four on its node, whatever this deployment's shared volumes are: the
    // agent home and the caches are node volumes there, never the platform host's.
    factory.homeEnv(true, true, true, env::put);
    // The plane's domain, which is the one the addresses above were composed from; written in the
    // place homeEnv gives it, or appended when the factory read none.
    env.put("QITS_DOMAIN", plane.domain());
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
