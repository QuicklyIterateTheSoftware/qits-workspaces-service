package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for {@link CredentialCommissioner} that mints pairs in memory and records every call.
 *
 * <p><b>It starts UNWIRED, and that is the point.</b> A bean in {@code src/test} is a bean for every
 * {@code @QuarkusTest} in the module, so a double that commissioned by default would put credential
 * environment into every container the suite launches and quietly change what dozens of unrelated
 * tests are about. Unwired it answers exactly as no implementation does — empty, no credential, no
 * environment — which is also the shipped posture, so the default is the case worth defaulting to.
 * {@link #wire()} turns it on for the test that is about commissioning, and {@link #reset()} turns
 * it back off.
 *
 * <p>The issued pairs are kept, so a test can ask what a workspace was given and whether a later
 * launch was given the same thing.
 */
@ApplicationScoped
public class FakeCredentialCommissioner implements CredentialCommissioner {

  private final AtomicInteger minted = new AtomicInteger();
  private final Map<String, WorkspaceCredential> live = new LinkedHashMap<>();
  private final Map<String, String> contexts = new LinkedHashMap<>();
  private final List<Long> commissionedFor = new CopyOnWriteArrayList<>();
  private final List<String> decommissioned = new CopyOnWriteArrayList<>();

  private volatile boolean wired;
  private volatile RuntimeException failure;

  /** Behave as a deployment with an issuer configured. */
  public void wire() {
    wired = true;
  }

  /** Back to the shipped posture: no issuer, so nothing is commissioned. */
  public void reset() {
    wired = false;
    failure = null;
    minted.set(0);
    live.clear();
    contexts.clear();
    commissionedFor.clear();
    decommissioned.clear();
    gitRefs.clear();
    gitRefUpdates.clear();
    failGitRefUpdates = false;
  }

  /** Make the next and every following commission fail the way an unreachable issuer does. */
  public void failCommissioning(String why) {
    failure = new IllegalStateException(why);
  }

  /** Every row id a commission was asked for, in order — including the ones that failed. */
  public List<Long> commissionedFor() {
    return List.copyOf(commissionedFor);
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

  /** Put a commission in place that this service never asked for — an orphan, for the reconcile. */
  public void plant(String clientId, String contextKind, String contextId) {
    synchronized (live) {
      live.put(clientId, new WorkspaceCredential(clientId, "secret-of-" + clientId));
      contexts.put(clientId, contextKind + ":" + contextId);
    }
  }

  /** The project each commission was scoped to, by row id. Null is the unscoped answer. */
  private final java.util.Map<Long, String> scopes = new java.util.concurrent.ConcurrentHashMap<>();

  /** What the service asked this credential to be scoped to, or null when it asked for nothing. */
  public String scopeFor(Long rowId) {
    return scopes.get(rowId);
  }

  /** The Git refs each commission stated, by row id. Absent is "stated nothing". */
  private final Map<Long, List<String>> gitRefs = new java.util.concurrent.ConcurrentHashMap<>();

  /** One Git ref update as the service sent it. */
  public record GitRefUpdate(String clientId, List<String> gitRefs) {}

  private final List<GitRefUpdate> gitRefUpdates = new CopyOnWriteArrayList<>();

  private volatile boolean failGitRefUpdates;

  /** The Git refs the last commission for {@code rowId} stated, or null when it stated none. */
  public List<String> gitRefsFor(Long rowId) {
    return gitRefs.get(rowId);
  }

  /** Every Git ref update that reached this issuer, in order — the failed ones included. */
  public List<GitRefUpdate> gitRefUpdates() {
    return List.copyOf(gitRefUpdates);
  }

  /** Make every following Git ref update fail the way an unreachable issuer does, or stop that. */
  public void failGitRefUpdates(boolean fail) {
    failGitRefUpdates = fail;
  }

  @Override
  public void updateGitRefs(String clientId, List<String> refs) {
    if (!wired) {
      return;
    }
    // Decided before the update is recorded, so a test that sees the record knows its outcome.
    boolean fail = failGitRefUpdates;
    gitRefUpdates.add(new GitRefUpdate(clientId, List.copyOf(refs)));
    if (fail) {
      throw new IllegalStateException("qits-idp is unreachable (fake)");
    }
  }

  @Override
  public Optional<WorkspaceCredential> commission(
      Long rowId, String projectId, List<String> statedGitRefs) {
    commissionedFor.add(rowId);
    if (statedGitRefs != null) {
      gitRefs.put(rowId, List.copyOf(statedGitRefs));
    } else {
      gitRefs.remove(rowId);
    }
    // Recorded before the wiring and failure arms, so a test can assert the scope a launch asked for
    // even on the paths where nothing is issued.
    if (projectId != null) {
      scopes.put(rowId, projectId);
    } else {
      scopes.remove(rowId);
    }
    if (!wired) {
      return Optional.empty();
    }
    if (failure != null) {
      throw failure;
    }
    String clientId = "ws-" + rowId + "-" + minted.incrementAndGet();
    WorkspaceCredential credential = new WorkspaceCredential(clientId, "secret-" + clientId);
    synchronized (live) {
      live.put(clientId, credential);
      contexts.put(clientId, CONTEXT_KIND + ":" + rowId);
    }
    return Optional.of(credential);
  }

  @Override
  public void decommission(String clientId) {
    decommissioned.add(clientId);
    synchronized (live) {
      live.remove(clientId);
      contexts.remove(clientId);
    }
  }

  @Override
  public List<Commission> list() {
    if (!wired) {
      return List.of();
    }
    synchronized (live) {
      List<Commission> all = new ArrayList<>();
      for (String clientId : live.keySet()) {
        String[] context = contexts.getOrDefault(clientId, CONTEXT_KIND + ":").split(":", 2);
        all.add(new Commission(clientId, context[0], context.length > 1 ? context[1] : ""));
      }
      return all;
    }
  }
}
