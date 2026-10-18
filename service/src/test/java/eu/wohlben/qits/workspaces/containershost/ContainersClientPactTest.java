package eu.wohlben.qits.workspaces.containershost;

import eu.wohlben.qits.containers.client.contracts.ContainersClientPact;
import org.junit.jupiter.api.Test;

/**
 * <b>What qits-containers-client asks qits-containers on this service's behalf</b> (qits-1163): the
 * rows of {@code eu.wohlben.qits:qits-containers-client-contracts}, selected by the kit's scan of
 * this module's compiled classes ({@code target/classes}) and driven through the real {@code
 * ContainersClient}, committed as {@code pacts/qits-workspaces-service_qits-containers-service.json}
 * with {@code qits-via} naming the client and its version. Replaces the hand-written {@code
 * ContainersContract} rows.
 *
 * <p>Only {@link WorkspaceContainers} calls the client. It deletes a container with {@code
 * volumes=false} on every path (the per-workspace volume goes by name, through {@code deleteVolume}),
 * so nothing a class file cannot show needs naming by hand: no {@code uses(...)}.
 */
class ContainersClientPactTest {

  static final ContainersClientPact.Builder CONTAINERS =
      ContainersClientPact.forConsumer("qits-workspaces-service");

  @Test
  void rows() {
    CONTAINERS.runRecorded();
  }

  @Test
  void pactFile() {
    CONTAINERS.pact().compareOrWritePactFile();
  }
}
