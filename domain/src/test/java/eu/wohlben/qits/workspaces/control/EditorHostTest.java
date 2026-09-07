package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The editor origin, read. Plain JUnit and no Quarkus: this is a header becoming a label, and the
 * lookup that label then drives is {@link EditorProxyTargetsTest}'s.
 *
 * <p>What is worth pinning is the refusals rather than the happy path. The value comes off a request
 * — the edge writes {@code X-Forwarded-Host} only when the client did not — so every shape that is
 * not an editor origin has to reach the same "no" without a query behind it.
 */
class EditorHostTest {

  @Test
  void theLabelBetweenTheFirstTwoDotsIsTheProject() {
    assertEquals(
        Optional.of("qits"), EditorHost.projectLabel("editor.qits.dev.example.eu"));
  }

  @Test
  void aFourLabelEditorHostNamesItsProjectAtPositionOne() {
    // The shape a deployed platform serves once the edge's default-environment fallthrough is gone:
    // `editor.<project>.<env>.<domain>`. The env label at position 2 is present and is deliberately
    // not read — the edge routed this request to this environment already, and this parser's answer
    // is the same whether the name says `dev` or says nothing.
    assertEquals(Optional.of("qits"), EditorHost.projectLabel("editor.qits.dev.wohlben.dev"));
    assertEquals(Optional.of("qits"), EditorHost.projectLabel("editor.qits.prod.wohlben.dev"));
  }

  @Test
  void aPortIsToleratedOnAFourLabelHostToo() {
    // A four-label name is where a port suffix actually shows up: a local platform serves the edge
    // on 8080, so this is the address a browser sends verbatim.
    assertEquals(
        Optional.of("qits"), EditorHost.projectLabel("editor.qits.dev.wohlben.dev:8080"));
    assertEquals(Optional.of("qits"), EditorHost.projectLabel("Editor.QITS.Dev.Localhost.:8080"));
  }

  @Test
  void theThreeLabelShortFormStillParses() {
    // Two reasons the minimum stays three rather than moving to four. `editor.qits.localhost` is a
    // real local address, and the short `editor.<project>.<domain>` form is still in flight while
    // the edge's fallthrough is removed — a parser that demanded four would refuse both, and the
    // edge is what decides which names it is willing to route here.
    assertEquals(Optional.of("qits"), EditorHost.projectLabel("editor.qits.localhost"));
    assertEquals(Optional.of("qits"), EditorHost.projectLabel("editor.qits.wohlben.dev"));
  }

  @Test
  void theFirstEntryWins() {
    // X-Forwarded-Host is a LIST, and only the client-facing hop's value describes the name a
    // browser asked for. A second hop appending its own must not repoint the lookup.
    assertEquals(
        Optional.of("qits"),
        EditorHost.projectLabel("editor.qits.dev.example.eu, editor.other.internal"));
  }

  @Test
  void aPortATrailingDotAndLetterCaseAreAllTolerated() {
    // A Host name is case-insensitive and may carry a port and a root dot; every comparison and
    // every lookup after this point is against something lowercase, so normalising is not optional.
    assertEquals(Optional.of("qits"), EditorHost.projectLabel("  Editor.QITS.Dev.Example.EU.:8080 "));
  }

  @Test
  void everythingThatIsNotAnEditorOriginIsNothing() {
    // One answer for all of them, on purpose: the caller turns every one into a 404 without
    // connecting anywhere, so telling them apart would be a distinction only an attacker could use.
    assertTrue(EditorHost.projectLabel(null).isEmpty(), "no header at all");
    assertTrue(EditorHost.projectLabel("").isEmpty(), "a blank header");
    assertTrue(
        EditorHost.projectLabel("workspaces.qits.dev.example.eu").isEmpty(), "another app");
    assertTrue(EditorHost.projectLabel("editor.qits").isEmpty(), "nowhere to be served");
    assertTrue(EditorHost.projectLabel("editor..dev.example.eu").isEmpty(), "no label");
    assertTrue(
        EditorHost.projectLabel("editor.-qits.dev.example.eu").isEmpty(), "not a project slug");
    assertTrue(
        EditorHost.projectLabel("editor.qits_qits.dev.example.eu").isEmpty(),
        "underscores are not slug characters");
  }

  @Test
  void aWrapperIsNamedAfterItsProjectTwice() {
    // qits-projects' own ProjectService.wrapperName. It is derived here because there is nothing to
    // look a project slug up by — see EditorProxyTargets.
    assertEquals("qits-qits", EditorHost.wrapperRepositoryName("qits"));
  }
}
