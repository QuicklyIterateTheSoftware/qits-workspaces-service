package eu.wohlben.qits.workspaces.transcripthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import eu.wohlben.qits.workspaces.dto.ArchivedSessionDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The union {@link AgentTranscripts} answers (qits-1752): shipped ∪ mounted, deduplicated by session
 * id with the shipped copy winning, oldest first. Plain JUnit over the merge itself; the beans
 * around it only supply the two lists.
 */
class AgentTranscriptsTest {

  private static ArchivedSessionDto session(String id, String startedAt, int messages) {
    Instant at = Instant.parse(startedAt);
    return new ArchivedSessionDto(id, at, at.plusSeconds(60), messages, List.of());
  }

  @Test
  void theUnionIsDeduplicatedBySessionIdWithTheShippedCopyWinning() {
    ArchivedSessionDto shippedCopy = session("s-both", "2026-10-30T10:00:00Z", 7);
    ArchivedSessionDto mountedCopy = session("s-both", "2026-10-30T10:00:00Z", 3);
    ArchivedSessionDto onlyShipped = session("s-shipped", "2026-10-30T12:00:00Z", 2);
    ArchivedSessionDto onlyMounted = session("s-mounted", "2026-10-29T08:00:00Z", 1);

    List<ArchivedSessionDto> merged =
        AgentTranscripts.merge(
            List.of(onlyShipped, shippedCopy), List.of(mountedCopy, onlyMounted));

    assertEquals(
        List.of("s-mounted", "s-both", "s-shipped"),
        merged.stream().map(ArchivedSessionDto::sessionId).toList());
    assertSame(shippedCopy, merged.get(1), "the mounted copy of a shipped session won");
  }

  @Test
  void eitherSideAloneIsTheAnswer() {
    ArchivedSessionDto one = session("s-1", "2026-10-30T10:00:00Z", 1);

    assertEquals(List.of(one), AgentTranscripts.merge(List.of(one), List.of()));
    assertEquals(List.of(one), AgentTranscripts.merge(List.of(), List.of(one)));
    assertEquals(List.of(), AgentTranscripts.merge(List.of(), List.of()));
  }
}
