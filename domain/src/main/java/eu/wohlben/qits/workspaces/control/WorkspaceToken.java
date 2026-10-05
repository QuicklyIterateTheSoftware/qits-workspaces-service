package eu.wohlben.qits.workspaces.control;

/**
 * The credential a RUNNER-placed workspace container holds toward the platform (qits-625,
 * qits-802): one opaque {@code qits_tok_} of the {@code workspace} kind, minted by this service at
 * qits-idp and deleted with the container it was minted for. The DIRECT counterpart is {@link
 * WorkspaceCredential}'s client pair; a row holds one or the other, never both.
 *
 * <p>It rides into the container as {@code QITS_TOKEN} and {@code QITS_TOKEN_SUBJECT} ({@link
 * RunnerWorkspaceSpecs}), and the edge spends it on every hop. Nothing in this service presents it.
 *
 * @param tokenId qits-idp's id for it: what it is deleted by
 * @param token the value, answered once by qits-idp
 * @param subject the {@code sub} the edge stamps on the JWT it mints for the token ({@code
 *     tok-workspace-…}), which the daemon socket binds a caller to
 */
public record WorkspaceToken(String tokenId, String token, String subject) {

  @Override
  public String toString() {
    // A record names every component, and this one's second is a credential.
    return "WorkspaceToken[tokenId=" + tokenId + ", subject=" + subject + "]";
  }
}
