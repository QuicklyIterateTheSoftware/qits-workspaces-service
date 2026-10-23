package eu.wohlben.qits.workspaces.targethost;

import eu.wohlben.qits.workspaces.control.CredentialCommissioner;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The retirement's boot one-shot (epic qits-1481, qits-1532): gives back every idp client and token
 * of the retired workspaces runner's kinds, {@code workspaces-runner} and {@code
 * workspaces-runner-registration}. Without its client the old runner stops dialling — the toolkit's
 * {@code ControlSocket} gives up on a sustained {@code invalid_client} — and its registration tokens
 * can install nothing any more.
 *
 * <p><b>Idempotent, and at every boot.</b> It deletes only what qits-idp lists for this service
 * under those two kinds, so a second run finds nothing. A listing that could not be read answers
 * empty ({@code CredentialCommissioner.list}/{@code listTokens} say so at WARN), and a delete that
 * failed is a WARN too: either way the next boot asks again. It never fails a startup. The kinds
 * themselves go from qits-idp in a later release, once this has run live — qits-idp keeps refusing
 * an unknown kind, so removing them first would make these deletes fail.
 */
@ApplicationScoped
public class LegacyRunnerClients {

  private static final Logger LOG = Logger.getLogger(LegacyRunnerClients.class);

  /** The retired workspaces runner's own client. */
  static final String RUNNER_KIND = "workspaces-runner";

  /** Its one-use registration token. */
  static final String REGISTRATION_KIND = "workspaces-runner-registration";

  static final Set<String> RETIRED_KINDS = Set.of(RUNNER_KIND, REGISTRATION_KIND);

  @Inject Instance<CredentialCommissioner> commissioner;

  void onStart(@Observes StartupEvent start) {
    giveBack();
  }

  /** One pass; answers how many credentials it gave back. Never throws. */
  int giveBack() {
    if (!commissioner.isResolvable()) {
      return 0;
    }
    CredentialCommissioner idp = commissioner.get();
    int given = 0;
    try {
      List<CredentialCommissioner.Commission> clients =
          idp.list().stream().filter(c -> RETIRED_KINDS.contains(c.contextKind())).toList();
      for (CredentialCommissioner.Commission client : clients) {
        if (client.clientId() == null) {
          continue;
        }
        LOG.infof(
            "Giving back retired runner client %s (%s, context %s)",
            client.clientId(), client.contextKind(), client.contextId());
        idp.decommission(client.clientId());
        given++;
      }
    } catch (RuntimeException e) {
      LOG.warnf("Could not give back the retired runner clients; the next boot will: %s", e.toString());
    }
    try {
      List<CredentialCommissioner.TokenCommission> tokens =
          idp.listTokens().stream().filter(t -> RETIRED_KINDS.contains(t.contextKind())).toList();
      for (CredentialCommissioner.TokenCommission token : tokens) {
        if (token.tokenId() == null) {
          continue;
        }
        LOG.infof(
            "Deleting retired runner token %s (%s, context %s)",
            token.tokenId(), token.contextKind(), token.contextId());
        idp.deleteToken(token.tokenId());
        given++;
      }
    } catch (RuntimeException e) {
      LOG.warnf("Could not delete the retired runner tokens; the next boot will: %s", e.toString());
    }
    return given;
  }
}
