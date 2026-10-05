package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.workspaces.control.WorkspaceContainerFactory;
import io.quarkus.arc.Arc;
import java.util.Optional;

/**
 * A {@link WorkspaceRunnerAddresses} at a public domain of a suite's choosing, for {@code
 * QuarkusMock.installMockForType}: the suites ship {@code qits.workspace.domain} empty, so no
 * ambient {@code QITS_DOMAIN} decides their answers, and a runner needs one. qits-ci's {@code
 * RunnerAddressesFixture} is the shape. The workspace image's version stays the running
 * application's own, read off its {@link WorkspaceContainerFactory}.
 */
public final class WorkspaceRunnerAddressesFixture {

  /** The domain every runner suite addresses its runners by. */
  public static final String DOMAIN = "runners.example.test";

  private WorkspaceRunnerAddressesFixture() {}

  /** Addresses under {@code domain}; null or undotted is the unconfigured deployment. */
  public static WorkspaceRunnerAddresses withDomain(String domain) {
    WorkspaceRunnerAddresses addresses = new WorkspaceRunnerAddresses();
    addresses.domain = Optional.ofNullable(domain);
    addresses.containerFactory = Arc.container().instance(WorkspaceContainerFactory.class).get();
    return addresses;
  }
}
