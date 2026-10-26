package eu.wohlben.qits.workspaces;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import eu.wohlben.qits.archrules.DeskAdminParityRules;
import java.util.Set;

/**
 * {@code qits:desk-admin} is admitted wherever {@code qits:admin-agent} is (qits-1621), except on
 * the routes whose only purpose is a ref write.
 *
 * <p>Refused: {@code BranchController}'s two doors. Merge pushes a merge commit and cleanup deletes
 * a branch on the git host; both are pushes and nothing else, and the desk-admin never pushes. The
 * class admits {@code qits:admin} and {@code qits:admin-agent} only, so the desk's {@code
 * qits:agent} does not reach it either.
 *
 * <p>Not visible to the rule, and therefore pinned by tests instead: every {@code
 * DeskBinding.refuse(...)} and branch refusal, which still binds a desk-admin because it still
 * holds {@code qits:desk} ({@code DeskAdminAccessTest}); {@code ContainerProxyRoute}'s
 * any-workspace roles, which stay without it (a shell beside the agents' push tokens); and {@code
 * AgentWaitController}'s project-bound desk-admin arm ({@code DeskAdminAccessTest}).
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.workspaces",
    importOptions = ImportOption.DoNotIncludeTests.class)
class DeskAdminParityTest {

  static final Set<String> REFUSED =
      Set.of("BranchController.mergeBranch", "BranchController.cleanupBranch");

  @ArchTest
  static final ArchRule PARITY = DeskAdminParityRules.everyAdminAgentRouteAdmitsDeskAdmin(REFUSED);

  @ArchTest
  static final ArchRule REFUSALS = DeskAdminParityRules.refusedRoutesExistAndRefuse(REFUSED);
}
