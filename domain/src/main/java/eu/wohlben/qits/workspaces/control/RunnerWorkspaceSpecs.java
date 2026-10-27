package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Composes the {@link RunnerLaunchSpec} a TARGET workspace's lease carries (qits-851, epic
 * qits-1481).
 *
 * <p><b>The same row facts as the DIRECT spec, through the same code.</b> The identity and
 * behaviour environment and the cache/home environment are {@link
 * WorkspaceContainerFactory#identityEnv} and {@link WorkspaceContainerFactory#homeEnv}, the methods
 * {@link WorkspaceContainerFactory#forWorkspace} itself writes them with, so the daemon in a slot is
 * told what the daemon on the platform host is told and the two cannot drift. {@code TZ} and the
 * commit identity are the factory's readings too.
 *
 * <p><b>Every address comes from the {@link WorkspaceAddressPlane}</b> (qits-625, qits-799): the
 * dial-home socket, the git base and the central MCP server are public edge names, {@code
 * <app>.qits.<domain>}, and {@code QITS_DOMAIN} is the plane's. A target has no qits-net and no
 * internal DNS, so none of the DIRECT spec's wire aliases appear. The four addresses are written by
 * {@link WorkspaceContainerFactory#addressEnv}, the same method a DIRECT admin row that holds a
 * workspace token is addressed with (qits-1084).
 *
 * <p><b>The credential is the lease's workspace token</b> (qits-625, qits-802, epic qits-1481):
 * {@code QITS_TOKEN} and {@code QITS_TOKEN_SUBJECT}, minted immediately before the lease is asked
 * for and read off the row, with the image's git credential helper ({@code GIT_CONFIG_GLOBAL})
 * answering for the plane's githost only ({@code QITS_GIT_AUTH_HOST}) — {@link
 * WorkspaceContainerFactory#tokenEnv}, and nothing else.
 *
 * <p><b>The daemon API handshake and its path base are the DIRECT spec's</b>, value for value:
 * {@code QITS_WORKSPACE_DAEMON_API_TOKEN} and {@code QITS_WORKSPACE_DAEMON_API_BASE_PATH}.
 *
 * <p><b>The slot policy's keys are never in it</b> ({@link #POLICY_ENV}): qits-system-runner sets
 * {@code CLAUDE_CONFIG_DIR} itself (to {@code /claude-home/.claude}, where it mounts the target's
 * credential volume), {@code HOME} is the image's, and {@code BUILDKIT_HOST} is CI's; qits-system
 * refuses a lease naming one. {@code homeEnv} writes {@code CLAUDE_CONFIG_DIR} for the DIRECT spec,
 * so it is taken out here.
 *
 * <p><b>The user is {@value #USER}</b>, the workspace image's own, and the limits are the factory's
 * configured {@code qits.workspace.*} ones for every lease.
 *
 * <p><b>The image is the public reference</b>: {@link WorkspaceContainerFactory#image} — the
 * version a DIRECT launch uses, the pin unless an operator overrode it — with its registry host
 * moved to {@code registry.qits.<domain>} by {@link WorkspaceAddressPlane#imageReference}, which is
 * the prefix a WORKSPACE slot admits.
 */
@ApplicationScoped
public class RunnerWorkspaceSpecs {

  /** The environment keys a slot type's policy owns in qits-system-runner. */
  public static final Set<String> POLICY_ENV = Set.of("HOME", "CLAUDE_CONFIG_DIR", "BUILDKIT_HOST");

  /** The workspace image's own user: a lease never runs as root. */
  public static final String USER = "1000";

  @Inject WorkspaceContainerFactory factory;

  /**
   * The lease spec for {@code row}, every address from {@code plane} ({@link
   * WorkspaceAddressPlanes#plane} builds the configured one, and refuses a deployment with no public
   * domain before anything is composed), the credential the row's token.
   */
  public RunnerLaunchSpec compose(Workspace row, WorkspaceAddressPlane plane) {
    if (plane == null) {
      throw new IllegalStateException("A lease spec needs the address plane");
    }
    String image = plane.imageReference(factory.image());

    Map<String, String> env = new LinkedHashMap<>();
    env.put("TZ", factory.containerTimezone());
    // The addresses, in the DIRECT spec's order, every one a public edge name off the plane — the
    // method a token-holding DIRECT row's spec writes them with too (qits-1084).
    WorkspaceContainerFactory.addressEnv(plane, row.id, env::put);
    // The path base the DIRECT spec writes here, the same value: the daemon is told which leading
    // part of a proxied path is its own address.
    env.put("QITS_WORKSPACE_DAEMON_API_BASE_PATH", ContainerProxyPath.base(row.id));
    factory.identityEnv(row.repositoryId, row.workspaceId, env::put, (key, value) -> {});
    // The host->daemon handshake constant, as the DIRECT spec writes it: the bearer the daemon's
    // HTTP API requires, which only ever travels the tunnel. Without it the API does not bind.
    env.put("QITS_WORKSPACE_DAEMON_API_TOKEN", factory.daemonApiToken());
    // The credential: the lease's workspace token, and the git helper told the one host it may
    // answer for. A lease is only ever asked for with one, so both or neither.
    if (row.commissionedToken != null && !row.commissionedToken.isBlank()) {
      WorkspaceContainerFactory.tokenEnv(
          row.commissionedToken, row.commissionedTokenSubject, plane, env::put);
    }
    env.putAll(factory.gitIdentityEnv());
    // The slot mounts all four (work volume, credential volume, m2, pnpm), whatever this
    // deployment's shared volumes are.
    factory.homeEnv(true, true, true, env::put);
    // The plane's domain, which is the one the addresses above were composed from; written in the
    // place homeEnv gives it, or appended when the factory read none.
    env.put("QITS_DOMAIN", plane.domain());
    env.keySet().removeAll(POLICY_ENV);
    // Unmodifiable views, never Map.copyOf: the copy's iteration order is salted per JVM, and env
    // order is part of a spec.
    return new RunnerLaunchSpec(image, Collections.unmodifiableMap(env), USER, factory.limits());
  }
}
