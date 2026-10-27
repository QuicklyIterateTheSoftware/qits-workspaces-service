package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for {@link CredentialCommissioner} that mints tokens in memory and records every call.
 *
 * <p><b>Its issuer question starts UNWIRED, and that is the point.</b> A bean in {@code src/test} is
 * a bean for every {@code @QuarkusTest} in the module, and the suites pin no public domain — so a
 * double whose {@link #isWired} answered true by default would have every DIRECT (admin or editor)
 * provision in the suite refused for want of an edge plane, and quietly change what dozens of
 * unrelated tests are about. Unwired, such a provision launches with no credential, exactly as on a
 * deployment with no issuer. {@link #wire()} turns the question on for the test that is about
 * provisioning a DIRECT row's credential, and {@link #reset()} turns it back off. The client listing
 * answers only while wired, too.
 *
 * <p>The idp clients it holds are only ever {@link #plant planted}: nothing commissions a client any
 * more, and a test of the reconcile or of the retired runner's give-back puts them in place.
 *
 * <p>It wins over {@code wiring/IdpCredentialCommissioner}, which is a {@code @DefaultBean} for
 * exactly that reason — the suite must reach no real qits-idp, and two unqualified beans of one type
 * are an ambiguous dependency that fails the whole build at {@code ArcProcessor#validate}. Duplicated
 * from {@code domain}'s copy because the two modules deliberately share no test classpath.
 */
@ApplicationScoped
public class FakeCredentialCommissioner implements CredentialCommissioner {

  private final Map<String, String> live = new LinkedHashMap<>();
  private final List<String> decommissioned = new CopyOnWriteArrayList<>();

  private volatile boolean wired;

  /** Behave as a deployment with an issuer configured. */
  public void wire() {
    wired = true;
  }

  /** Back to the shipped posture: no issuer, so nothing is commissioned. */
  public void reset() {
    wired = false;
    live.clear();
    decommissioned.clear();
    tokensWired = true;
    tokenFailure = null;
    tokensMinted.clear();
    agentTokensMinted.clear();
    tokensDeleted.clear();
    synchronized (liveTokens) {
      liveTokens.clear();
    }
  }

  // --- the workspace token (qits-625) ------------------------------------------------------------
  //
  // WIRED BY DEFAULT, unlike the issuer question above, and for the opposite reason: a RUNNER start
  // refuses to queue a row without a token, so a double that minted nothing by default would fail
  // every RUNNER start in the suite. A DIRECT (admin or editor) row is minted one only when an edge
  // plane resolves (qits-1084), and the suites pin no public domain, so minting changes nothing
  // about a DIRECT container unless a test installs a plane. unwireTokens() is the shipped posture
  // with no issuer.

  private volatile boolean tokensWired = true;
  private volatile RuntimeException tokenFailure;
  private final AtomicInteger tokenSerial = new AtomicInteger();

  /** One token this fake minted: the row it was for and what the mint stated. */
  public record MintedToken(
      long rowId,
      WorkspaceToken token,
      String projectId,
      List<String> gitRefs,
      String contextKind) {}

  private final List<MintedToken> tokensMinted = new CopyOnWriteArrayList<>();
  private final List<String> tokensDeleted = new CopyOnWriteArrayList<>();

  /** Live tokens by id: kind, context and creation instant, as a listing would read them. */
  private final Map<String, TokenCommission> liveTokens = new LinkedHashMap<>();

  /** Behave as a deployment with no issuer: every token mint answers empty. */
  public void unwireTokens() {
    tokensWired = false;
  }

  /** Make every following token mint fail the way an unreachable issuer does. */
  public void failTokens(String why) {
    tokenFailure = new IllegalStateException(why);
  }

  /** Every token minted, in order. */
  public List<MintedToken> tokensMinted() {
    return List.copyOf(tokensMinted);
  }

  /** Every token id deleted, in order. */
  public List<String> tokensDeleted() {
    return List.copyOf(tokensDeleted);
  }

  /** Put a token in place this service never stored on a row — an orphan, for the reconcile. */
  public void plantToken(String tokenId, String contextKind, String contextId, Instant createdAt) {
    synchronized (liveTokens) {
      liveTokens.put(tokenId, new TokenCommission(tokenId, contextKind, contextId, createdAt));
    }
  }

  /** The token ids this fake still holds. */
  public List<String> liveTokenIds() {
    synchronized (liveTokens) {
      return new ArrayList<>(liveTokens.keySet());
    }
  }

  /** The agent tokens minted (qits-1152), in order. */
  public record MintedAgentToken(
      String agentId, WorkspaceToken token, String projectId, List<String> gitRefs, String contextKind) {}

  private final List<MintedAgentToken> agentTokensMinted = new java.util.concurrent.CopyOnWriteArrayList<>();

  public List<MintedAgentToken> agentTokensMinted() {
    return List.copyOf(agentTokensMinted);
  }

  @Override
  public Optional<WorkspaceToken> commissionAgentToken(
      String agentId, String projectId, List<String> statedGitRefs, boolean admin) {
    if (!tokensWired) {
      return Optional.empty();
    }
    if (tokenFailure != null) {
      throw tokenFailure;
    }
    int serial = tokenSerial.incrementAndGet();
    WorkspaceToken token =
        new WorkspaceToken(
            "tok-id-agent-" + serial, "qits_tok_fakeagent" + serial, "tok-agent-" + agentId);
    agentTokensMinted.add(
        new MintedAgentToken(
            agentId,
            token,
            projectId,
            statedGitRefs == null ? null : List.copyOf(statedGitRefs),
            CredentialCommissioner.contextKindFor(admin)));
    synchronized (liveTokens) {
      liveTokens.put(
          token.tokenId(),
          new TokenCommission(
              token.tokenId(),
              CredentialCommissioner.contextKindFor(admin),
              CredentialCommissioner.AGENT_CONTEXT_PREFIX + agentId,
              Instant.now()));
    }
    return Optional.of(token);
  }

  @Override
  public Optional<WorkspaceToken> commissionToken(
      long rowId, String projectId, List<String> statedGitRefs, boolean admin) {
    if (!tokensWired) {
      return Optional.empty();
    }
    if (tokenFailure != null) {
      throw tokenFailure;
    }
    int serial = tokenSerial.incrementAndGet();
    WorkspaceToken token =
        new WorkspaceToken(
            "tok-id-" + rowId + "-" + serial,
            "qits_tok_fake" + rowId + "x" + serial,
            "tok-workspace-" + rowId + "-" + serial);
    tokensMinted.add(
        new MintedToken(
            rowId,
            token,
            projectId,
            statedGitRefs == null ? null : List.copyOf(statedGitRefs),
            CredentialCommissioner.contextKindFor(admin)));
    synchronized (liveTokens) {
      liveTokens.put(
          token.tokenId(),
          new TokenCommission(
              token.tokenId(),
              CredentialCommissioner.contextKindFor(admin),
              Long.toString(rowId),
              Instant.now()));
    }
    return Optional.of(token);
  }

  @Override
  public void deleteToken(String tokenId) {
    tokensDeleted.add(tokenId);
    synchronized (liveTokens) {
      liveTokens.remove(tokenId);
    }
  }

  @Override
  public List<TokenCommission> listTokens() {
    if (!tokensWired) {
      return List.of();
    }
    synchronized (liveTokens) {
      return new ArrayList<>(liveTokens.values());
    }
  }

  /**
   * Whether an issuer is configured: {@link #wire()} was called and {@link #unwireTokens()} was not.
   * Answered from the two flags alone, as the shipped implementation answers from its config alone.
   */
  @Override
  public boolean isWired() {
    return wired && tokensWired;
  }

  /** Every client id handed back, in order. */
  public List<String> decommissioned() {
    return List.copyOf(decommissioned);
  }

  /** The client ids this fake still holds — what a reconcile would read. */
  public List<String> liveClientIds() {
    synchronized (live) {
      return new ArrayList<>(live.keySet());
    }
  }

  /** Put an idp client in place this service never asked for — an orphan, for the reconcile. */
  public void plant(String clientId, String contextKind, String contextId) {
    synchronized (live) {
      live.put(clientId, contextKind + ":" + contextId);
    }
  }

  @Override
  public void decommission(String clientId) {
    decommissioned.add(clientId);
    synchronized (live) {
      live.remove(clientId);
    }
  }

  @Override
  public List<Commission> list() {
    if (!wired) {
      return List.of();
    }
    synchronized (live) {
      List<Commission> all = new ArrayList<>();
      for (Map.Entry<String, String> client : live.entrySet()) {
        String[] context = client.getValue().split(":", 2);
        all.add(new Commission(client.getKey(), context[0], context.length > 1 ? context[1] : ""));
      }
      return all;
    }
  }
}
