package eu.wohlben.qits.workspaces;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import eu.wohlben.qits.archrules.TestProfileBudgetRules;

/**
 * <b>At most one unmarked {@code QuarkusTestProfile} in this module.</b>
 *
 * <p>The rule exists because of what happened here. A {@code @TestProfile} is a separate Quarkus
 * application, not a configuration overlay — Quarkus compares the profile CLASS and not the map it
 * returns — so every distinct one is a restart inside the surefire fork, and each restart abandons a
 * classloader whose ~125 MB of metaspace nothing short of a full GC gives back. This module grew to
 * fourteen, one temp-directory override at a time, and the fork peaked at 3.41 GiB against qits-ci's
 * hard 4 g step limit. Four consecutive release requests were killed at exit 137 with every test
 * passing and no failing test to read, which is the worst shape a build failure comes in. That is
 * bug e6f0bdfa; {@code <reuseForks>false</reuseForks>} in this module's pom is what made the suite
 * finish, and it costs about nine minutes a run.
 *
 * <p>So the budget is not a style preference, it is the thing that keeps that fix from being paid
 * for twice. A profile that genuinely cannot be merged declares {@code
 * NecessaryTestProfileDuplication} and its own javadoc says why the two configurations cannot be one
 * — an argument a reviewer can disagree with, not a restatement of the config map. "It needs
 * different config" is not a reason; the rule's own javadoc rules it out by name.
 *
 * <p>{@code OnlyIncludeTests} is load-bearing: profiles live in test sources, so the default import
 * would find none of them and this class would pass by seeing nothing.
 *
 * <p>It sits beside {@code domain}'s {@code ArchRulesTest} in shape but not in module, because the
 * profiles are here. The other rule set this module consumes, {@code DatasourceBaselineRules}, is
 * asserted by a plain {@code @QuarkusTest} calling {@code assertBaseline()} — a different mechanism
 * for a rule that needs a running application, and not a precedent for this one.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.workspaces",
    importOptions = ImportOption.OnlyIncludeTests.class)
class TestProfileBudgetTest {

  @ArchTest static final ArchTests PROFILES = ArchTests.in(TestProfileBudgetRules.class);
}
