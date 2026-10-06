package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
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
 * <p><b>The credential is the row's workspace token</b> (qits-625, qits-802): {@code QITS_TOKEN} and
 * {@code QITS_TOKEN_SUBJECT}, with the image's git credential helper ({@code GIT_CONFIG_GLOBAL})
 * answering for the plane's githost only ({@code QITS_GIT_AUTH_HOST}). It is read off the row, so a
 * start re-presents the same spec. None of the DIRECT pair block is written — no {@code
 * QITS_COMMISSIONED_CLIENT_*}, no token url and no audience — because nothing in a RUNNER container
 * mints: the edge spends the token on every hop.
 *
 * <p><b>The daemon API handshake and its two path bases are the DIRECT spec's</b>, value for value:
 * {@code QITS_WORKSPACE_DAEMON_API_TOKEN} (without it the daemon's API never binds, and the
 * terminal and file editor that reach it over the tunnel have nothing to talk to), {@code
 * QITS_WORKSPACE_DAEMON_API_BASE_PATH} and {@code QITS_WORKSPACE_DAEMON_SERVICE_PROXY_BASE}. All
 * three are host-to-daemon values — a constant and two paths of this service's own routes — and
 * name no address, so the plane has nothing to say about them.
 *
 * <p><b>The memory limits are the taking runner's</b> (qits-951): its row's {@code
 * workspaceMemoryLimit}/{@code workspaceMemorySwapLimit} where set, the factory's configured
 * defaults where not — {@link #limitsFor} is the whole rule. The caller reads the row when the
 * runner takes the workspace, as qits-ci reads a step memory limit when the step starts, so an
 * operator's edit reaches the next launch and never a running container. The other limits (pids,
 * cpus, oom score) are the factory's for every placement.
 *
 * <p><b>What it still leaves out</b>: the host-side placement fields (no docker socket — admin rows
 * are never RUNNER — and no user).
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
   * {@link #compose(Workspace, WorkspaceAddressPlane, WorkspaceRunner)} under the platform's own
   * limits, as for a runner that set none.
   */
  public RunnerLaunchSpec compose(Workspace row, WorkspaceAddressPlane plane) {
    return compose(row, plane, null);
  }

  /**
   * The launch spec for {@code row}, every address from {@code plane} ({@link
   * WorkspaceAddressPlanes#plane} builds the configured one, and refuses a deployment with no public
   * domain before anything is composed), its memory limits from {@code runner} — the row of the
   * runner taking it, read at the take — where that sets them ({@link #limitsFor}).
   */
  public RunnerLaunchSpec compose(
      Workspace row, WorkspaceAddressPlane plane, WorkspaceRunner runner) {
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
    // The two path bases the DIRECT spec writes here, the same values: the daemon is told which
    // leading part of a proxied path is its own address, and what each dev server's public base is.
    env.put("QITS_WORKSPACE_DAEMON_API_BASE_PATH", ContainerProxyPath.base(row.id));
    env.put("QITS_WORKSPACE_DAEMON_SERVICE_PROXY_BASE", ServiceProxyPath.PREFIX + row.id);
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
    // The host->daemon handshake constant, as the DIRECT spec writes it: the bearer the daemon's
    // HTTP API requires, which only ever travels the tunnel. Without it the API does not bind.
    env.put("QITS_WORKSPACE_DAEMON_API_TOKEN", factory.daemonApiToken());
    // The credential, where the DIRECT spec writes its commissioned pair: the row's workspace token
    // (qits-802), and the git helper told the one host it may answer for. Both or neither — a row
    // with no token is never queued, so a spec is only ever composed with one.
    if (row.commissionedToken != null && !row.commissionedToken.isBlank()) {
      env.put("QITS_TOKEN", row.commissionedToken);
      env.put("QITS_TOKEN_SUBJECT", row.commissionedTokenSubject);
      env.put("GIT_CONFIG_GLOBAL", WorkspaceContainerFactory.GIT_CONFIG_GLOBAL);
      env.put("QITS_GIT_AUTH_HOST", plane.gitAuthHost());
    }
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
        limitsFor(factory.limits(), runner),
        true);
  }

  /**
   * The limits a workspace on {@code runner} is launched under: {@code defaults} (the factory's
   * configured ones) with the runner row's memory limits laid over them (qits-951).
   *
   * <ul>
   *   <li>memory is the row's {@code workspaceMemoryLimit}, or the default when it sets none;
   *   <li>memory-swap is the row's {@code workspaceMemorySwapLimit}; else, when the row sets a
   *       memory, that same memory — a hard cap, because the default swap is sized for the default
   *       memory, and a row's {@code 12g} paired with the default {@code 8g} is a {@code
   *       --memory-swap} below {@code --memory}, which docker refuses; else the default.
   * </ul>
   *
   * A null runner, or one that sets neither, is the defaults unchanged.
   */
  static RunnerLaunchSpec.Limits limitsFor(
      RunnerLaunchSpec.Limits defaults, WorkspaceRunner runner) {
    if (runner == null
        || (runner.workspaceMemoryLimit == null && runner.workspaceMemorySwapLimit == null)) {
      return defaults;
    }
    String memory =
        runner.workspaceMemoryLimit != null ? runner.workspaceMemoryLimit : defaults.memory();
    String swap;
    if (runner.workspaceMemorySwapLimit != null) {
      swap = runner.workspaceMemorySwapLimit;
    } else if (runner.workspaceMemoryLimit != null) {
      swap = runner.workspaceMemoryLimit;
    } else {
      swap = defaults.memorySwap();
    }
    return new RunnerLaunchSpec.Limits(
        memory, swap, defaults.pids(), defaults.cpus(), defaults.oomScoreAdj());
  }
}
