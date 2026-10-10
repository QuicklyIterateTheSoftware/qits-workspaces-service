package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.CaptureContent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CaptureGoalRendererTest {

  private static final Instant RECEIVED = Instant.parse("2026-07-14T12:00:11Z");

  private final CaptureGoalRenderer renderer = new CaptureGoalRenderer();

  private static CaptureContent fullContent() {
    return new CaptureContent(
        "2026-07-14T11:59:58Z",
        "main",
        new CaptureContent.Page(
            "http://app.example/greeting/anna", "greeting/anna", "greeting/:name", "Greeting"),
        new CaptureContent.Environment(1440, 900, 2.0, "Mozilla/5.0", "dark"),
        new CaptureContent.Dom("<html><body>hello</body></html>", false, 31),
        new CaptureContent.Selection(
            "<app-greeting><button>Go</button></app-greeting>",
            false,
            48,
            "#go",
            "button",
            "app-greeting"),
        "{\n  \"cart\" : { }\n}");
  }

  @Test
  void rendersFullTemplate() {
    String goal = renderer.render(fullContent(), RECEIVED);

    assertTrue(goal.startsWith("# Captured from the running app — 2026-07-14 12:00 UTC\n"));
    assertTrue(goal.contains("**Where**: `greeting/anna` (route `greeting/:name`)"));
    assertTrue(goal.contains("http://app.example/greeting/anna"));
    assertTrue(goal.contains("**Source workspace**: `main`"));
    assertTrue(goal.contains("**Viewport**: 1440×900 @2x, dark"));
    assertTrue(goal.contains("**Captured**: 2026-07-14T11:59:58Z"));
    assertTrue(goal.contains("## App state at capture"));
    assertTrue(goal.contains("```json\n{\n  \"cart\" : { }\n}\n```"));
    assertTrue(goal.contains("## Selected component (style-frozen)"));
    assertTrue(goal.contains("**Picked**: `button` in `app-greeting` — `#go`"));
    assertTrue(goal.contains("<app-greeting><button>Go</button></app-greeting>"));
    // The selected component renders before the whole-page DOM.
    assertTrue(
        goal.indexOf("## Selected component") < goal.indexOf("## Rendered DOM (style-frozen)"));
    assertTrue(goal.contains("## Rendered DOM (style-frozen)"));
    assertTrue(goal.contains("<details><summary>"));
    assertTrue(goal.contains("<html><body>hello</body></html>"));
    assertTrue(goal.contains("</details>"));
    assertFalse(goal.contains("truncated at"));
  }

  @Test
  void omitsSelectionSectionWhenAbsent() {
    CaptureContent content =
        new CaptureContent(
            null, null, null, null, new CaptureContent.Dom("<html/>", false, 7), null, null);
    assertFalse(renderer.render(content, RECEIVED).contains("## Selected component"));
  }

  @Test
  void selectionProvenanceOmitsComponentWhenNoAppAncestor() {
    CaptureContent content =
        new CaptureContent(
            null,
            null,
            null,
            null,
            null,
            new CaptureContent.Selection("<p>x</p>", false, 8, "#p", "p", null),
            null);
    String goal = renderer.render(content, RECEIVED);
    assertTrue(goal.contains("## Selected component (style-frozen)"));
    assertTrue(goal.contains("**Picked**: `p` — `#p`"));
    assertFalse(goal.contains(" in `"));
  }

  @Test
  void omitsStateSectionWhenAbsent() {
    CaptureContent content =
        new CaptureContent(
            null, null, null, null, new CaptureContent.Dom("<html/>", false, 7), null, null);
    String goal = renderer.render(content, RECEIVED);

    assertFalse(goal.contains("## App state at capture"));
    assertTrue(goal.contains("**Source workspace**: —"));
  }

  @Test
  void toleratesFullySparsePayload() {
    String goal =
        renderer.render(new CaptureContent(null, null, null, null, null, null, null), RECEIVED);

    assertTrue(goal.contains("# Captured from the running app — 2026-07-14 12:00 UTC"));
    assertTrue(goal.contains("*No DOM captured.*"));
    assertFalse(goal.contains("**Where**"));
  }

  @Test
  void truncatesDomAtByteCapWithMarker() {
    renderer.goalDomMaxBytes = 64;
    String dom = "<html>" + "x".repeat(500) + "</html>";
    CaptureContent content =
        new CaptureContent(
            null, null, null, null, new CaptureContent.Dom(dom, false, dom.length()), null, null);

    String goal = renderer.render(content, RECEIVED);

    assertTrue(goal.contains("truncated at 0 kB")); // 64 bytes rounds to 0 KiB in the summary
    assertTrue(
        goal.contains(
            "*Truncated at 64 bytes (DOM was 513 bytes; config `qits.capture.goal-dom-max-bytes`).*"));
    String embedded = fencedBodies(goal).get(0);
    assertTrue(embedded.getBytes(StandardCharsets.UTF_8).length <= 64);
    assertBalancedFences(goal);
    assertTrue(goal.contains("</details>"));
  }

  @Test
  void truncationNeverSplitsMultiByteCharacters() {
    String dom = "é".repeat(100); // 2 UTF-8 bytes each
    assertEquals("é".repeat(31), CaptureGoalRenderer.utf8Truncate(dom, 63));
    assertEquals("a" + "🙂".repeat(2), CaptureGoalRenderer.utf8Truncate("a" + "🙂".repeat(5), 12));
    assertEquals(dom, CaptureGoalRenderer.utf8Truncate(dom, 200));
  }

  @Test
  void fencesOutgrowBacktickRunsInBody() {
    String dom = "<code>``` ````` `</code>";
    CaptureContent content =
        new CaptureContent(
            null,
            null,
            null,
            null,
            new CaptureContent.Dom(dom, false, dom.length()),
            null,
            "{\"snippet\": \"````\"}");

    String goal = renderer.render(content, RECEIVED);

    List<String> bodies = fencedBodies(goal);
    assertEquals(2, bodies.size(), "state and DOM blocks must both survive as single fences");
    assertTrue(bodies.stream().anyMatch(b -> b.contains(dom)));
    assertBalancedFences(goal);
  }

  @Test
  void truncationInsideBacktickRunKeepsFencesBalanced() {
    // Cap lands mid-run: the fence must be computed AFTER truncation.
    String dom = "x".repeat(60) + "`".repeat(20);
    renderer.goalDomMaxBytes = 70;
    CaptureContent content =
        new CaptureContent(
            null, null, null, null, new CaptureContent.Dom(dom, false, dom.length()), null, null);

    String goal = renderer.render(content, RECEIVED);

    assertBalancedFences(goal);
    assertEquals(1, fencedBodies(goal).size());
  }

  /** Extracts fenced code-block bodies, asserting each opener has a matching same-length closer. */
  private static List<String> fencedBodies(String markdown) {
    Pattern opener = Pattern.compile("(?m)^(`{3,})(\\w*)$");
    Matcher m = opener.matcher(markdown);
    List<String> bodies = new java.util.ArrayList<>();
    int searchFrom = 0;
    while (m.find(searchFrom)) {
      String fence = m.group(1);
      int bodyStart = m.end() + 1;
      int close = markdown.indexOf("\n" + fence + "\n", bodyStart - 1);
      assertTrue(close >= 0, "unclosed fence at offset " + m.start());
      bodies.add(markdown.substring(bodyStart, close + 1).stripTrailing());
      searchFrom = close + fence.length() + 2;
    }
    return bodies;
  }

  private static void assertBalancedFences(String markdown) {
    assertFalse(fencedBodies(markdown).isEmpty(), "expected at least one fenced block");
  }
}
