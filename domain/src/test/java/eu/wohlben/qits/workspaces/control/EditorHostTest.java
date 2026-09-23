package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The editor origin, recognised. Plain JUnit and no Quarkus: this is a header becoming a yes or a
 * no, and the row that answer then reaches is {@link EditorProxyTargetsTest}'s.
 *
 * <p>What is worth pinning is the tolerances and the one refusal that is not obvious. The value comes
 * off a request — the edge writes {@code X-Forwarded-Host} only when the client did not — so the
 * shapes a browser really sends have to be accepted, and a host that merely begins with the same
 * seven letters must not be.
 *
 * <p><b>The cases that used to be here about a PROJECT label are gone rather than rewritten</b>, and
 * that is the change: there is one editor, so the name has no project in it to read, no slug grammar
 * to validate it against, and no label count to insist on. What this class asserts instead is that
 * the test is grammar-agnostic on purpose — both the old four-label origin and the shorter one it is
 * moving to answer yes — so the origin can change without this file changing with it.
 */
class EditorHostTest {

  @Test
  void aHostWhoseFirstLabelIsEditorIsTheEditors() {
    assertTrue(EditorHost.isEditorHost("editor.dev.example.eu"));
    assertTrue(EditorHost.isEditorHost("editor.example.eu"));
  }

  @Test
  void theOldPerProjectGrammarStillAnswersYes() {
    // DELIBERATELY still accepted. The origin is not changing in the same step as the routing, so
    // what is deployed today is `editor.<slug>.<env>.<domain>` and every one of them has to reach
    // the one editor — and the shorter form has to reach it the day the edge and the client move.
    // The label behind `editor` is the edge's business either way: it routed this request to this
    // environment's process already, and reading the name again here would be that decision made
    // twice, off a header a client may have written.
    assertTrue(EditorHost.isEditorHost("editor.qits.dev.example.eu"));
    assertTrue(EditorHost.isEditorHost("editor.qits.localhost"));
  }

  @Test
  void aPortATrailingDotAndLetterCaseAreAllTolerated() {
    // A Host name is case-insensitive and may carry a port and a root dot; a local platform serves
    // the edge on 8080, so this is the address a browser sends verbatim.
    assertTrue(EditorHost.isEditorHost("  Editor.QITS.Dev.Example.EU.:8080 "));
    assertTrue(EditorHost.isEditorHost("editor.dev.wohlben.dev:8080"));
  }

  @Test
  void theFirstEntryWins() {
    // X-Forwarded-Host is a LIST, and only the client-facing hop's value describes the name a
    // browser asked for. A second hop appending its own must not change which surface answers.
    assertTrue(EditorHost.isEditorHost("editor.dev.example.eu, workspaces.dev.example.eu"));
    assertFalse(EditorHost.isEditorHost("workspaces.dev.example.eu, editor.dev.example.eu"));
  }

  @Test
  void everythingThatIsNotAnEditorOriginIsNo() {
    // One answer for all of them, on purpose: the caller falls through to the surface the request
    // was always going to reach, so telling them apart would be a distinction only an attacker could
    // use.
    assertFalse(EditorHost.isEditorHost(null), "no header at all");
    assertFalse(EditorHost.isEditorHost(""), "a blank header");
    assertFalse(EditorHost.isEditorHost("workspaces.qits.dev.example.eu"), "another app");
    assertFalse(
        EditorHost.isEditorHost("editorial.example.eu"),
        "the FIRST LABEL, not a prefix — somebody else's host starts with the same seven letters");
    assertFalse(
        EditorHost.isEditorHost("qits.editor.dev.example.eu"),
        "position 0 and nowhere else: a name with `editor` behind something is not the editor's");
  }
}
