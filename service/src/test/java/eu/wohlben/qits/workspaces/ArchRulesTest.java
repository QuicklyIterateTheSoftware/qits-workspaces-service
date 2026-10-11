package eu.wohlben.qits.workspaces;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import eu.wohlben.qits.archrules.HttpClientRules;

/**
 * The platform's shared ArchUnit rules over this module's classes. Today that is the raw-HttpClient
 * completeness guard: every class that calls {@code HttpClient.newBuilder()}/{@code
 * newHttpClient()} must also touch {@code QitsHttpClients} (wrap the client) or carry {@code
 * @RawHttpClient(reason = ...)}. It runs here because this is the deployable whose classpath
 * carries qits-http-core and whose {@code @RegisterRestClient} interfaces under {@code wiring/}
 * are what the server/client filters instrument. `domain`'s own {@code ArchRulesTest} runs the
 * causation-row rule for the entities that live there; this module builds no raw HttpClient today,
 * so the rule passes with nothing to repair.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.workspaces",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {

  @ArchTest static final ArchTests HTTP_CLIENTS = ArchTests.in(HttpClientRules.class);
}
