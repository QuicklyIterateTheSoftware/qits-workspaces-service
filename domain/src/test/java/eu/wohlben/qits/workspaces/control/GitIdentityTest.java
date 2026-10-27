package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The two delivery forms of the configured identity. Plain JUnit (same package) sets the
 * {@code @ConfigProperty} fields directly; default resolution ({@code qits}/{@code qits@local}
 * through SmallRye) is covered end-to-end by {@code SeedServiceTest}'s attribution assertion in the
 * cli module.
 */
class GitIdentityTest {

  private GitIdentity identity() {
    GitIdentity identity = new GitIdentity();
    identity.name = "qits-bot";
    identity.email = "qits-bot@example.com";
    return identity;
  }

  @Test
  void envMapCarriesAuthorAndCommitterHalves() {
    Map<String, String> env = identity().envMap();

    assertEquals(
        Map.of(
            "GIT_AUTHOR_NAME", "qits-bot",
            "GIT_AUTHOR_EMAIL", "qits-bot@example.com",
            "GIT_COMMITTER_NAME", "qits-bot",
            "GIT_COMMITTER_EMAIL", "qits-bot@example.com"),
        env);
    // Deterministic iteration order, so the container spec's env is stable.
    assertEquals(
        List.of("GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL"),
        List.copyOf(env.keySet()));
  }

  @Test
  void inlineArgsRenderTheHostGitOverrides() {
    assertEquals(
        List.of("-c", "user.email=qits-bot@example.com", "-c", "user.name=qits-bot"),
        identity().inlineArgs());
  }

  @Test
  void anAgentCommitsAsItselfUnderThePlatformDomain() {
    assertEquals(
        Map.of(
            "GIT_AUTHOR_NAME", "agent-a1",
            "GIT_AUTHOR_EMAIL", "agent-a1@qits.wohlben.eu",
            "GIT_COMMITTER_NAME", "agent-a1",
            "GIT_COMMITTER_EMAIL", "agent-a1@qits.wohlben.eu"),
        identity().forAgent("a1", "qits.wohlben.eu"));
  }

  @Test
  void anAgentWithNoPublicDomainTakesThePlatformAddressDomain() {
    assertEquals("agent-a1@example.com", identity().forAgent("a1", null).get("GIT_AUTHOR_EMAIL"));
    assertEquals("agent-a1@example.com", identity().forAgent("a1", " ").get("GIT_COMMITTER_EMAIL"));
  }
}
