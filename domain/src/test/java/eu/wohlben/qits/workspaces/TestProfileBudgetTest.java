package eu.wohlben.qits.workspaces;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import eu.wohlben.qits.archrules.TestProfileBudgetRules;

/**
 * The module's budget of Quarkus test applications: at most one unmarked {@code QuarkusTestProfile},
 * with every further one declaring {@code NecessaryTestProfileDuplication} and saying in its own
 * javadoc why the two configurations cannot be one.
 *
 * <p>It guards a memory bound, not a style. Every distinct profile is a separate Quarkus
 * application; surefire reuses one fork per module, so each one is a restart that augments a new
 * application and abandons the previous classloader — roughly 125 MB of metaspace reclaimable only
 * by a full GC. Fourteen profiles in the sibling {@code service} module OOM-killed four CI gates at
 * exit 137 (bug e6f0bdfa, 2026-09-14); qits-ci caps a step at 4 g, and the fork's peak here was
 * measured at 3.41 GiB against it. Exit 137 is the kernel's OOM killer and reads as nothing at all
 * in a test report — no failing assertion, no stack, just a dead gate — which is exactly why the
 * bound is enforced at build time by a rule that names the offending class instead.
 *
 * <p>The failure mode it prevents is quiet and additive: one class needs one value moved, writes a
 * profile of its own, and the cost lands on the whole module's fork rather than on that class.
 * Answering the rule is nearly always the cheap direction — union the override into {@code
 * SharedTestOverridesProfile} — because most overrides are scenery. The marker is for the real case,
 * where two classes assert opposite things about the same key and no single map can serve both; the
 * javadoc it demands is what keeps that claim reviewable rather than a restatement of the map.
 *
 * <p>Separate from {@link ArchRulesTest} only because profiles live in TEST sources: one
 * {@code @AnalyzeClasses} carries one import option, and that class needs {@code DoNotIncludeTests}
 * for its rules over the entities.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.workspaces",
    importOptions = ImportOption.OnlyIncludeTests.class)
class TestProfileBudgetTest {

  @ArchTest static final ArchTests PROFILES = ArchTests.in(TestProfileBudgetRules.class);
}
