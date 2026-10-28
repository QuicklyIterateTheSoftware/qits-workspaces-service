package eu.wohlben.qits.workspaces.control;

import java.util.List;
import java.util.Optional;

/**
 * Where a per-workspace credential comes from and goes back to: qits-idp's commission API.
 *
 * <p>A workspace container is a dynamic context, so it gets a credential of its own rather than a
 * share of a durable one. This port is the seam that reaches the issuer — one outbound HTTP
 * collaborator, declared here and implemented in {@code service/…/wiring} exactly as {@link
 * RepositoryLookup}, {@link GitHostAddress} and {@link PushCausation} are, so {@code domain} names
 * what it needs and a deployment decides where that answer comes from.
 *
 * <p><b>The credential mirrors the CONTAINER lifecycle, not the row's.</b> It is commissioned when a
 * container is provisioned and decommissioned when one is torn down — a delete-container leaves the
 * workspace ACTIVE and takes the credential with the container, and the next ensure commissions a
 * fresh one. That is the whole model: no TTL, no refresh, and no secret outliving the thing it
 * authenticates.
 *
 * <p><b>Absent is a supported configuration</b>, in both spellings, and the two are deliberately the
 * same behaviour: with no implementation installed the {@code Instance} is unresolvable, and with a
 * wired implementation that has no issuer configured {@link #isWired} answers false and {@link
 * #commissionToken} answers empty. Either way no credential is minted and {@link
 * WorkspaceContainerFactory} injects no credential environment.
 *
 * <p><b>An implementation that IS wired must throw rather than answer empty when a call fails.</b>
 * The distinction is {@link RepositoryLookup}'s and is load-bearing for the same reason: "there is no
 * issuer here" and "the issuer could not be asked" are different facts, and folding them together
 * would launch a workspace with no identity every time qits-idp blinked. A commissioning failure
 * fails the provision loudly; a workspace is never launched half-credentialed.
 */
public interface CredentialCommissioner {

  /**
   * The context kind every commission this service makes carries. qits-idp records {@code (owner,
   * contextKind, contextId)} per row, so this constant plus the workspace's row id is how a
   * reconcile tells this service's own workspace credentials from anything else it might one day
   * commission.
   */
  String CONTEXT_KIND = "workspace";

  /**
   * The context kind of an ADMIN workspace's credential (qits-628 follow-up). qits-idp issues this
   * kind {@code qits:agent} and {@code qits:admin-agent}, the role every door that admits {@code
   * qits:admin} also admits by name: an admin workspace's container holds the host's docker socket,
   * so its agent is trusted as far as an admin is. Every other workspace row — a regular one, the
   * editor — stays {@link #CONTEXT_KIND}.
   *
   * <p><b>Release qits-idp first.</b> A qits-idp that does not know this kind gives it no role at
   * all (its D12 rule: an unknown kind is harmless, not refused), so an admin workspace's agent
   * would lose even {@code qits:agent} until it does.
   */
  String ADMIN_CONTEXT_KIND = "workspace-admin";

  /**
   * The kind of the commission for a workspace row: {@link #ADMIN_CONTEXT_KIND} for an admin row,
   * {@link #CONTEXT_KIND} for every other.
   */
  static String contextKindFor(boolean admin) {
    return admin ? ADMIN_CONTEXT_KIND : CONTEXT_KIND;
  }

  /**
   * Whether a listed commission's kind is one of this service's WORKSPACE credentials, either kind.
   * The reconcile judges both by the same rule — an admin credential is reaped when orphaned and
   * never while an ACTIVE row claims it, exactly as a regular one.
   */
  static boolean isWorkspaceKind(String contextKind) {
    return CONTEXT_KIND.equals(contextKind) || ADMIN_CONTEXT_KIND.equals(contextKind);
  }

  /**
   * The claim name the issuer scopes a credential by. Spelled here rather than inline for the reason
   * {@code QitsClaims} spells it on the enforcement side: a typo in a claim name reads as "no
   * claim", which is a credential that quietly keeps the wider grant.
   */
  String PROJECT_CLAIM = "project";

  /**
   * The claim that names the agent a token belongs to (qits-1213): the agent's UUID. Only an agent
   * token states it, never a slot's or any other workspace credential.
   */
  String AGENT_CLAIM = "agent";

  /** One live idp client as the owner's reconcile reads it back. Never carries a secret. */
  record Commission(String clientId, String contextKind, String contextId) {}

  /**
   * One live token as the owner's reconcile reads it back (qits-625). Never carries a value. {@code
   * createdAt} is null when qits-idp did not say.
   */
  record TokenCommission(
      String tokenId, String contextKind, String contextId, java.time.Instant createdAt) {}

  /**
   * Whether this deployment has an issuer to ask at all — the question {@link #commissionToken}
   * otherwise answers by coming back empty, for the one caller that must know before it asks.
   *
   * <p>That caller is a DIRECT provision on a deployment with no edge plane (no public {@code
   * QITS_DOMAIN}): a {@code qits_tok_} only works through the edge, so such a row cannot be minted
   * one, and with an issuer wired the provision is refused rather than launched with an identity
   * nobody could present. With no issuer there is nothing to refuse — the container launches with no
   * credential, the shipped posture — and telling the two apart must not cost a mint. An
   * implementation answers from its configuration alone and never calls out.
   */
  boolean isWired();

  /**
   * Give an idp client back. This service commissions no client any more — every container it
   * starts holds a {@link #commissionToken workspace token} — so the clients this answers for are
   * the ones qits-idp still lists for it: what {@code targethost/LegacyRunnerClients} gives back
   * for the retired runner, once, at boot.
   *
   * <p>Best-effort by contract: an implementation reports a failure and never throws. A client a
   * failure leaves behind is given back at the next boot.
   */
  void decommission(String clientId);

  /**
   * Every idp client this service currently owns, for {@code targethost/LegacyRunnerClients}.
   * Empty when nothing could be asked — a read failure must not be read as "nothing is out there", since that answer would
   * decommission nothing rather than everything.
   */
  List<Commission> list();

  // --- the workspace token (qits-625, qits-802, qits-1084) ---------------------------------------

  /**
   * Mint the {@code qits_tok_} for the workspace row {@code rowId}, or empty when this deployment has
   * no issuer to ask (see the class javadoc — that is a configuration, not a failure). The
   * credential of every container this service starts: every TARGET row's, and a DIRECT admin or
   * editor row's whenever an edge plane resolves (qits-1084) — with none, that provision is refused
   * before it gets here ({@link #isWired}). The value is answered once: qits-idp stores a hash.
   *
   * <p><b>{@code projectId} is what the token is ABOUT, and it is not the same fact as {@code
   * rowId}.</b> The row id is the {@code contextId} a reconcile compares against live workspaces,
   * while the project is the scope every resource service judges the token on: the issuer turns it
   * into a {@code project} claim. <b>Null is accepted and means unscoped</b> — the answer when the
   * repository registry could not name the project, which must cost a scope rather than a launch.
   *
   * <p><b>{@code gitRefs}</b> are the Git refs the token may push (contract C2): null states nothing,
   * an empty list states "may push nothing", and a refused list fails closed — the implementation
   * mints again with an empty list and logs an ERROR, and never mints again without the list.
   *
   * <p><b>{@code admin} picks the kind</b> ({@link #contextKindFor}): {@link #ADMIN_CONTEXT_KIND}
   * for an admin row, {@link #CONTEXT_KIND} for every other. It is the only thing the flag changes,
   * and it never reaches the container's spec. A TARGET row is never admin, so a TARGET mint always
   * passes false.
   *
   * @throws RuntimeException when an issuer is configured and the call did not succeed
   */
  Optional<WorkspaceToken> commissionToken(
      long rowId, String projectId, List<String> gitRefs, boolean admin);

  /** The token of a non-admin workspace row: kind {@link #CONTEXT_KIND}. */
  default Optional<WorkspaceToken> commissionToken(
      long rowId, String projectId, List<String> gitRefs) {
    return commissionToken(rowId, projectId, gitRefs, false);
  }

  /**
   * Delete a workspace token: the container it belonged to is gone. Best-effort by contract, as
   * {@link #decommission} is, for the same reason; what a failure leaves is the reconcile's.
   */
  /**
   * An <b>agent's</b> credential (qits-1152, D3, D18): a token of the workspace kind for {@code
   * admin}, context id {@link #AGENT_CONTEXT_PREFIX}{@code <agentId>}, scoped to the project and
   * stating the agent's own Git refs. Its claims also state {@link #AGENT_CLAIM} = {@code agentId}
   * (qits-1213). Empty when no issuer is wired.
   */
  default Optional<WorkspaceToken> commissionAgentToken(
      String agentId, String projectId, List<String> gitRefs, boolean admin) {
    return Optional.empty();
  }

  /** What an agent token's context id starts with; a workspace token's is the bare row id. */
  String AGENT_CONTEXT_PREFIX = "agent-";

  void deleteToken(String tokenId);

  /**
   * Every token this service currently owns, of every kind, for the reconcile. Empty when nothing
   * could be asked, for {@link #list}'s reason.
   */
  List<TokenCommission> listTokens();
}
