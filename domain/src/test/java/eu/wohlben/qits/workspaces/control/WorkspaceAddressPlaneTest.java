package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The RUNNER address plane (qits-799): every member from the domain alone, and what it refuses. */
class WorkspaceAddressPlaneTest {

  private static final List<String> SPELLINGS =
      List.of("registry.dev.localhost:8080", "REGISTRY.other.localhost:9090");

  private static WorkspaceAddressPlane plane() {
    return WorkspaceAddressPlane.of("wohlben.eu", SPELLINGS);
  }

  @Test
  void everyAddressIsAPublicEdgeName() {
    WorkspaceAddressPlane plane = plane();

    assertEquals("wohlben.eu", plane.domain());
    assertEquals("wss://workspaces.qits.wohlben.eu/workspaces/daemon/42", plane.daemonUrl(42L));
    assertEquals("https://githost.qits.wohlben.eu/git", plane.gitBaseUrl());
    assertEquals("githost.qits.wohlben.eu", plane.gitAuthHost());
    assertEquals("https://projects.qits.wohlben.eu/projects/mcp", plane.repositoryMcpUrl());
    assertEquals(
        "https://observability.qits.wohlben.eu/observability/mcp", plane.observabilityMcpUrl());
    assertEquals("https://mcp.qits.wohlben.eu/mcp", plane.platformMcpUrl());
  }

  @Test
  void theDomainIsFoldedLikeTheRunnerAddressesFoldIt() {
    WorkspaceAddressPlane plane = WorkspaceAddressPlane.of("  .WohlBen.EU. ", List.of());

    assertEquals("wohlben.eu", plane.domain());
    assertEquals("githost.qits.wohlben.eu", plane.gitAuthHost());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "localhost", "dev.localhost", "nodot", "LOCALHOST"})
  void refusesANameNoRunnerReachesThePlatformBy(String domain) {
    EdgePlaneUnconfigured refused =
        assertThrows(
            EdgePlaneUnconfigured.class, () -> WorkspaceAddressPlane.of(domain, SPELLINGS));

    assertEquals(
        "EDGE_PLANE_UNCONFIGURED: QITS_DOMAIN '" + domain.trim() + "' is not a public domain",
        refused.getMessage());
  }

  @Test
  void refusesNoDomainAtAll() {
    EdgePlaneUnconfigured refused =
        assertThrows(EdgePlaneUnconfigured.class, () -> WorkspaceAddressPlane.of(null, SPELLINGS));

    assertEquals(
        "EDGE_PLANE_UNCONFIGURED: QITS_DOMAIN '' is not a public domain", refused.getMessage());
  }

  @Test
  void anImageOnThePlatformsRegistryMovesToThePublicHostAndKeepsTagAndDigest() {
    WorkspaceAddressPlane plane = plane();

    assertEquals(
        "registry.qits.wohlben.eu/qits/workspace:2026.1001.120000",
        plane.imageReference("registry.dev.localhost:8080/qits/workspace:2026.1001.120000"));
    assertEquals(
        "registry.qits.wohlben.eu/qits/workspace@sha256:0123abcd",
        plane.imageReference("registry.dev.localhost:8080/qits/workspace@sha256:0123abcd"));
    assertEquals(
        "registry.qits.wohlben.eu/qits/workspace:2026.1001.120000@sha256:0123abcd",
        plane.imageReference(
            "Registry.Other.Localhost:9090/qits/workspace:2026.1001.120000@sha256:0123abcd"));
  }

  @Test
  void anImageOnSomebodyElsesRegistryIsPulledAsNamed() {
    WorkspaceAddressPlane plane = plane();

    assertEquals("docker.io/alpine:3", plane.imageReference("docker.io/alpine:3"));
    assertEquals("alpine:3", plane.imageReference("alpine:3"));
    assertEquals(
        "registry.qits.wohlben.eu/qits/workspace:1",
        plane.imageReference("registry.qits.wohlben.eu/qits/workspace:1"));
  }

  /** The configured plane: the two image repositories' hosts and the local machine spelling. */
  @Test
  void theConfiguredPlaneKnowsBothImageRepositoriesAndTheLocalSpelling() {
    WorkspaceAddressPlanes planes = new WorkspaceAddressPlanes();
    planes.imageRepo = Optional.of("registry.a.example:1/qits/workspace");
    planes.editorImageRepo = Optional.of("registry.b.example:2/qits/workspace-editor");
    planes.environment = "dev";
    planes.domain = Optional.of("wohlben.eu");

    WorkspaceAddressPlane plane = planes.plane();

    assertEquals(
        Arrays.asList(
            "registry.a.example:1", "registry.b.example:2", "registry.dev.localhost:8080"),
        plane.registrySpellings());

    planes.domain = Optional.empty();
    assertThrows(EdgePlaneUnconfigured.class, planes::plane);
  }
}
