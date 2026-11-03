package eu.wohlben.qits.workspaces.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * A drift guard over the keys that decide whether this service's application logs, spans and
 * metrics leave the process at all, and where they go.
 *
 * <p><b>The behavioural proof is not here and deliberately is not.</b> qits-events owns it: {@code
 * OtelLogBridgeTest} decodes a real {@code ExportLogsServiceRequest} off an offline OTLP stub and
 * asserts identity, severity, body, exception fields and trace correlation, and {@code
 * PackagedLogBridgeIT} repeats the claim against the packaged artifact where the handler's
 * initialisation is a different question. This repo runs the SAME Quarkus extension on the SAME four
 * keys, so the evidence carries — and copying that suite ten times would be ten copies of one
 * measurement, nine of them able to rot unnoticed.
 *
 * <p>What does NOT carry is the configuration itself, because it is per-repository and can be
 * changed here by anyone. Three of the four log keys are Quarkus' own defaults and Quarkus still
 * labels the logging integration preview, so a version bump that flipped one, or an edit that
 * dropped a line, would stop this service's logging with a green build and nothing in any log to say
 * so. That is what this test exists to make loud. It asserts values, not merely presence: a key
 * silently reverting to {@code ALL} or to a second, separately-configured exporter is the failure
 * mode, and only the value can see it.
 *
 * <p>The exporters are pinned beside the log keys because the four above describe a pipe with no
 * destination on their own. Since epic qits-428 the exporters are qits-telemetry-core's CDI beans,
 * not Quarkus' own, so {@code cdi} is what makes the SDK take them, and the receiver's address is
 * the library's own default ({@code qits.telemetry.endpoint}), resolved rather than configured here.
 *
 * <p>Note the test asserts config values only, and does so on the running application's own {@code
 * Config}: the shipped {@code src/main/resources/application.properties} is merged into the test
 * configuration, so these are the values a deployment gets. The suite runs with {@code
 * quarkus.otel.sdk.disabled=true} under {@code %test} — nothing is exported during a build, which is
 * exactly why the configuration needs a reader of its own.
 */
@QuarkusTest
class OtelLogConfigTest {

  @Inject Config config;

  @Test
  void applicationLogsAreExportedOverOtlp() {
    // The handler that turns an org.jboss.logging.Logger call into an OTLP record, and the switch
    // that lets it exist at all. Either one false is silence.
    assertEquals("true", value("quarkus.otel.logs.enabled"));
    assertEquals("true", value("quarkus.otel.logs.handler.enabled"));

    // `cdi` routes the records at the exporter configured below, rather than at a second one
    // configured somewhere else — or at none.
    assertEquals("cdi", value("quarkus.otel.logs.exporter"));

    // The one value here that is not Quarkus' default (ALL): the deliberate outbound floor.
    assertEquals("INFO", value("quarkus.otel.logs.level"));
  }

  @Test
  void everySignalGoesThroughTheCdiExporters() {
    // qits-telemetry-core's beans are the exporters (epic qits-428): `cdi` is what makes the SDK
    // take them. Quarkus' default for all three, pinned for the reason the logs keys are.
    assertEquals("cdi", value("quarkus.otel.traces.exporter"));
    assertEquals("cdi", value("quarkus.otel.logs.exporter"));
    assertEquals("true", value("quarkus.otel.metrics.enabled"));
    assertEquals("cdi", value("quarkus.otel.metrics.exporter"));
  }

  @Test
  void theExportersPointAtTheReceiversEdgeHost() {
    // The library's default, not overridden here: the receiver at its edge host, QITS_DOMAIN
    // expanded. The exporters append /v1/<signal>.
    assertEquals(
        "https://observability.qits." + value("QITS_DOMAIN") + "/observability/api/otel",
        value("qits.telemetry.endpoint"));
    assertEquals("true", value("qits.telemetry.bearer.enabled"));
    assertEquals("qits", value("qits.telemetry.bearer.client"));
    // The wire-alias address is gone with Quarkus' own exporter, which was all that read it.
    assertTrue(
        config.getOptionalValue("qits.observability.url", String.class).isEmpty(),
        "qits.observability.url reaches no exporter and must not come back");
  }

  private String value(String key) {
    return config
        .getOptionalValue(key, String.class)
        .orElseThrow(() -> new AssertionError(key + " is not set — log export is off"));
  }
}
