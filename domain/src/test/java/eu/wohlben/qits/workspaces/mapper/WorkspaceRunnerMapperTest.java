package eu.wohlben.qits.workspaces.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link WorkspaceRunnerMapper}: the columns copied, and the capabilities read and never corrected. */
class WorkspaceRunnerMapperTest {

  private final WorkspaceRunnerMapper mapper = new WorkspaceRunnerMapper();

  private static WorkspaceRunner runner() {
    WorkspaceRunner runner = new WorkspaceRunner();
    runner.id = UUID.fromString("3f2b8f0e-0000-4000-8000-000000000001");
    runner.name = "node-1";
    runner.description = "the CI VM";
    runner.slots = 2;
    runner.createdAt = Instant.parse("2026-10-05T08:00:00Z");
    return runner;
  }

  @Test
  public void aRegisteredHealthyRunnerReadsEveryFact() {
    WorkspaceRunner runner = runner();
    runner.clientId = "client-1";
    runner.registeredAt = Instant.parse("2026-10-05T08:01:00Z");
    runner.lastSeenAt = Instant.parse("2026-10-05T09:00:00Z");
    runner.lastHealthCheckAt = Instant.parse("2026-10-05T08:02:00Z");
    runner.lastHealthCheckOk = true;
    runner.capabilities =
        "{\"version\":\"2026.1005.1\",\"arch\":\"amd64\","
            + "\"dotClaudeVolume\":\"qits-workspaces-runner-dot-claude-3f2b8f0e\","
            + "\"login\":{\"claude\":\"PRESENT\",\"kimi\":\"ABSENT\","
            + "\"checkedAt\":\"2026-10-05T08:30:00Z\"}}";

    WorkspaceRunnerDto dto = mapper.toDto(runner);

    assertEquals(runner.id, dto.id());
    assertEquals("node-1", dto.name());
    assertEquals("the CI VM", dto.description());
    assertEquals(2, dto.slots());
    assertEquals("2026.1005.1", dto.version());
    assertEquals("amd64", dto.arch());
    assertEquals("qits-workspaces-runner-dot-claude-3f2b8f0e", dto.dotClaudeVolume());
    assertEquals(
        new WorkspaceRunnerDto.Login("PRESENT", "ABSENT", Instant.parse("2026-10-05T08:30:00Z")),
        dto.login());
    assertTrue(dto.registered());
    assertEquals(runner.registeredAt, dto.registeredAt());
    assertFalse(dto.quarantined());
    assertTrue(dto.eligible());
    assertEquals(runner.lastSeenAt, dto.lastSeenAt());
    assertEquals(runner.lastHealthCheckAt, dto.lastHealthCheckAt());
    assertEquals(Boolean.TRUE, dto.lastHealthCheckOk());
    assertEquals(runner.createdAt, dto.createdAt());
  }

  @Test
  public void aFreshRunnerSaysNothingAndIsNotEligible() {
    WorkspaceRunnerDto dto = mapper.toDto(runner());

    assertNull(dto.version());
    assertNull(dto.dotClaudeVolume());
    assertNull(dto.login());
    assertFalse(dto.registered());
    assertFalse(dto.eligible());
    assertNull(dto.lastHealthCheckOk());
  }

  @Test
  public void aQuarantinedOrDrainedRunnerIsNotEligible() {
    WorkspaceRunner quarantined = runner();
    quarantined.clientId = "client-1";
    quarantined.quarantinedAt = Instant.parse("2026-10-05T08:01:00Z");
    quarantined.quarantineReason = "awaiting first health check";
    WorkspaceRunnerDto dto = mapper.toDto(quarantined);
    assertTrue(dto.quarantined());
    assertEquals("awaiting first health check", dto.quarantineReason());
    assertFalse(dto.eligible());

    WorkspaceRunner drained = runner();
    drained.clientId = "client-1";
    drained.slots = 0;
    assertFalse(mapper.toDto(drained).eligible());
  }

  @Test
  public void whatTheRunnerSaidInAnotherShapeReadsAsNothing() {
    WorkspaceRunner runner = runner();
    runner.capabilities = "{\"version\":7,\"login\":\"PRESENT\"}";
    WorkspaceRunnerDto dto = mapper.toDto(runner);
    assertNull(dto.version());
    assertNull(dto.login());

    runner.capabilities = "not json";
    assertNull(mapper.toDto(runner).version(), "an unreadable column costs the listing nothing");
  }

  /** What the service knows live lands beside the row; the row alone reads as not connected. */
  @Test
  public void theLiveFactsAreLaidBesideTheRow() {
    WorkspaceRunner runner = runner();
    Instant since = Instant.parse("2026-10-05T09:00:00Z");

    WorkspaceRunnerDto live =
        mapper.toDto(
            runner,
            new WorkspaceRunnerMapper.Live(
                true,
                since,
                "2026.1005.54252",
                2,
                3,
                1,
                "docker run … claude",
                "docker run … kimi login",
                false));

    assertTrue(live.connected());
    assertEquals(since, live.connectedSince());
    assertEquals("2026.1005.54252", live.pinnedVersion());
    assertEquals(2, live.running());
    assertEquals(3, live.owned());
    assertEquals(1, live.queued());
    assertEquals("docker run … claude", live.loginCommand());
    assertEquals("docker run … kimi login", live.kimiLoginCommand());
    assertFalse(live.loginCommandPending());

    WorkspaceRunnerDto bare = mapper.toDto(runner);
    assertFalse(bare.connected());
    assertNull(bare.connectedSince());
    assertNull(bare.pinnedVersion());
    assertEquals(0, bare.owned());
    assertNull(bare.loginCommand());
    assertFalse(bare.loginCommandPending());
  }

  /** The gate itself is {@code RunnerLoginCommand}'s; this is only the DTO carrying the flag. */
  @Test
  public void aPendingLiveReportsItOnTheDto() {
    WorkspaceRunnerDto dto =
        mapper.toDto(
            runner(),
            new WorkspaceRunnerMapper.Live(false, null, "2026.1005.54252", 0, 0, 0, null, null, true));

    assertNull(dto.loginCommand());
    assertNull(dto.kimiLoginCommand());
    assertTrue(dto.loginCommandPending());
  }

  /** qits-850: the listing carries each check's verdict and never its data; the full read does. */
  @Test
  public void theHealthReportIsListedWithoutDataAndReadInFull() {
    WorkspaceRunner runner = runner();
    runner.capabilities =
        "{\"health\":{\"at\":\"2026-10-05T08:02:00Z\",\"ok\":true,\"detail\":\"all passed\","
            + "\"requestId\":\"r-9\",\"checks\":[{\"name\":\"docker\",\"ok\":true,"
            + "\"detail\":\"27.1\",\"data\":{\"version\":\"27.1\"}},\"not a check\"]}}";

    WorkspaceRunnerDto.Health listed = mapper.toDto(runner).health();
    var full = mapper.toHealthDto(runner);

    assertEquals(
        new WorkspaceRunnerDto.Health(
            Instant.parse("2026-10-05T08:02:00Z"),
            true,
            "all passed",
            java.util.List.of(new WorkspaceRunnerDto.Check("docker", true, "27.1"))),
        listed);
    assertEquals("r-9", full.requestId());
    assertEquals("27.1", full.checks().get(0).data().path("version").asText());
    assertNull(mapper.toDto(runner()).health(), "no check has settled");
    assertNull(mapper.toHealthDto(runner()));
  }
}
