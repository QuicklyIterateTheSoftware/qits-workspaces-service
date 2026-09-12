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
 * wired implementation that has no issuer configured {@link #commission} answers empty. Either way no
 * credential is minted and {@link WorkspaceContainerFactory} injects no environment — which is what
 * every workspace container did before this port existed.
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
   * The claim name the issuer scopes a credential by. Spelled here rather than inline for the reason
   * {@code QitsClaims} spells it on the enforcement side: a typo in a claim name reads as "no
   * claim", which is a credential that quietly keeps the wider grant.
   */
  String PROJECT_CLAIM = "project";

  /** One live commission as the owner's reconcile reads it back. Never carries a secret. */
  record Commission(String clientId, String contextKind, String contextId) {}

  /**
   * Commission a credential for the workspace row {@code rowId}, or empty when this deployment has
   * no issuer to ask (see the class javadoc — that is a configuration, not a failure).
   *
   * <p>The secret is returned <b>once</b>: the issuer stores a hash, so a caller that loses it has
   * to decommission and commission again.
   *
   * <p><b>{@code projectId} is what the credential is ABOUT, and it is not the same fact as {@code
   * rowId}.</b> The row id says which context this credential belongs to — it is the {@code
   * contextId} a reconcile compares against live workspaces — while the project is the scope every
   * resource service judges it on: a workspace belongs to a repository, a repository belongs to a
   * project, and a credential handed to a container in one project has no business acting in
   * another. The issuer turns it into a {@code project} claim on every token the credential mints,
   * which is exactly what qits-ci's manual trigger reads to decide which repositories a caller may
   * have evaluated.
   *
   * <p><b>Null is accepted and means unscoped</b>, which is what every workspace credential was
   * before this argument existed. It is the answer when the repository registry could not name the
   * project, and that must cost a scope rather than a launch: a registry blinking is a moment, and
   * refusing to start a workspace over it would trade a narrower credential for no workspace at all.
   *
   * @throws RuntimeException when an issuer is configured and the call did not succeed
   */
  default Optional<WorkspaceCredential> commission(Long rowId, String projectId) {
    return commission(rowId, projectId, null);
  }

  /**
   * The same commission, also stating the Git refs the credential may push ({@code gitRefs}, contract
   * C2). The issuer stamps the list onto every token as {@code git_refs}, and the git host enforces
   * it.
   *
   * <p><b>Null states nothing</b> — no {@code gitRefs} member, the commission as it was before C2.
   * An empty list states "may push nothing".
   *
   * <p><b>An issuer without C2 may refuse a stated list with 400.</b> An implementation then
   * commissions again without the list, warns once, and returns that credential: an older idp must
   * cost the scope, not the launch.
   */
  Optional<WorkspaceCredential> commission(Long rowId, String projectId, List<String> gitRefs);

  /**
   * Replace the Git refs a live commission states — the narrowing (contract C5), sent as {@code PUT
   * /idp/api/clients/{clientId}/git-refs}. The next token of that client carries the new list.
   *
   * <p>Does nothing when this deployment has no issuer. Unlike {@link #decommission}, a failure
   * <b>throws</b>: the caller keeps the narrowing pending and the reconcile sends it again.
   *
   * @throws RuntimeException when an issuer is configured and the update did not land
   */
  void updateGitRefs(String clientId, List<String> gitRefs);

  /**
   * Give a credential back — the container it belonged to is gone.
   *
   * <p>Best-effort by contract: every caller runs after something irreversible has already happened,
   * so an implementation reports a failure and never throws. A credential a failure leaves behind is
   * the reconcile's to reap, which is why that exists.
   */
  void decommission(String clientId);

  /**
   * Every commission this service currently owns, for the reconcile. Empty when nothing could be
   * asked — a read failure must not be read as "nothing is out there", since that answer would
   * decommission nothing rather than everything.
   */
  List<Commission> list();
}
