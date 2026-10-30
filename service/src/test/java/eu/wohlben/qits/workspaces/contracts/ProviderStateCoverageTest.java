package eu.wohlben.qits.workspaces.contracts;

import eu.wohlben.qits.archrules.ProviderStateCoverageRules;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every state recorded in golden-masters/index.json has its {@code @State} callback on {@link
 * ConsumerPactVerificationTest}, so a missing one fails here instead of in a consumer's pin
 * (qits-1754). The two legacy runner-slot names stay as unrecorded aliases: consumers still pin
 * them, and they were never recorded.
 */
class ProviderStateCoverageTest {

  @Test
  void everyRecordedStateHasItsCallback() {
    ProviderStateCoverageRules.assertCovered(
        ProviderStateCoverageRules.findGoldenIndex(),
        Set.of(
            ProviderStates.LEGACY_A_SLOT_RUNNING_ON_A_RUNNER_IN_SERVICE,
            ProviderStates.LEGACY_A_STOPPED_SLOT_ON_A_RUNNER_IN_SERVICE),
        ConsumerPactVerificationTest.class);
  }
}
