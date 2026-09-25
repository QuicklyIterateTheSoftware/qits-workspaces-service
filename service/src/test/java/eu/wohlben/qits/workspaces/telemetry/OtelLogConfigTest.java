package eu.wohlben.qits.workspaces.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * A drift guard over the six keys that decide whether this service's application logs leave the
 * process at all, and where they go.
 *
 * <p><b>The behavioural proof is not here and deliberately is not.</b> qits-events owns it: {@code
 * OtelLogBridgeTest} decodes a real {@code ExportLogsServiceRequest} off an offline OTLP stub and
 * asserts identity, severity, body, exception fields and trace correlation, and {@code
 * PackagedLogBridgeIT} repeats the claim against the packaged artifact where the handler's
 * initialisation is a different question. This repo runs the SAME Quarkus extension on the SAME four
 * keys and the same HTTP/protobuf exporter, so the evidence carries — and copying that suite ten
 * times would be ten copies of one measurement, nine of them able to rot unnoticed.
 *
 * <p>What does NOT carry is the configuration itself, because it is per-repository and can be
 * changed here by anyone. Three of the four log keys are Quarkus' own defaults and Quarkus still
 * labels the logging integration preview, so a version bump that flipped one, or an edit that
 * dropped a line, would stop this service's logging with a green build and nothing in any log to say
 * so. That is what this test exists to make loud. It asserts values, not merely presence: a key
 * silently reverting to {@code ALL} or to a second, separately-configured exporter is the failure
 * mode, and only the value can see it.
 *
 * <p>The endpoint and protocol are pinned beside the log keys because the four above describe a pipe
 * with no destination on their own. The endpoint is asserted RESOLVED — the shipped file writes it
 * as {@code ${qits.observability.url}/observability/api/otel}, and what has to be right is the
 * address the SDK ends up with, which is also what proves the expression still expands. The SDK
 * appends {@code /v1/logs} to it; that suffix is the exporter's and is not configured here.
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
  void theExporterStillPointsAtTheReceiver() {
    // http/protobuf, not the gRPC default — qits-observability's ingest is an HTTP resource.
    assertEquals("http/protobuf", value("quarkus.otel.exporter.otlp.protocol"));

    // Resolved, not the raw expression: this is the base the SDK appends /v1/logs to.
    assertEquals(
        "http://dev-qits-observability:8080/observability/api/otel",
        value("quarkus.otel.exporter.otlp.endpoint"));
  }

  private String value(String key) {
    return config
        .getOptionalValue(key, String.class)
        .orElseThrow(() -> new AssertionError(key + " is not set — log export is off"));
  }
}
