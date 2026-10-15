package eu.wohlben.qits.workspaces.contracts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** What {@link StateParamRequestBody} swaps in a pact's request body, and what it leaves alone. */
class StateParamRequestBodyTest {

  private static final Map<String, String> PINNED =
      Map.of("qualifiedId", "contract-00000001-1", "projectId", "00000000-0000-4000-8000-000000000001");

  private static final Map<String, String> RETURNED =
      Map.of("qualifiedId", "contract-5f3a9c1e-1", "projectId", "7d1c0b6e-4f5a-4c43-9d0e-2a8b6f1e3c90");

  private static String rewritten(String body) throws Exception {
    var out = StateParamRequestBody.rewrite(body.getBytes(UTF_8), PINNED, RETURNED);
    return out == null ? null : new String(out.body(), UTF_8);
  }

  @Test
  void aMemberNameThatIsAPinnedParamBecomesTheReturnedOne() throws Exception {
    assertEquals(
        "{\"contract-5f3a9c1e-1\":{\"title\":\"contract-00000001-10\"}}",
        rewritten("{\"contract-00000001-1\":{\"title\":\"contract-00000001-10\"}}"));
  }

  @Test
  void aValueThatIsAPinnedParamBecomesTheReturnedOne() throws Exception {
    assertEquals(
        "{\"project\":\"7d1c0b6e-4f5a-4c43-9d0e-2a8b6f1e3c90\",\"n\":[1]}",
        rewritten("{\"project\":\"00000000-0000-4000-8000-000000000001\",\"n\":[1]}"));
  }

  @Test
  void theGoldenMastersPlaceholderBecomesTheReturnedValueAsKeyOrValue() throws Exception {
    assertEquals(
        "{\"contract-5f3a9c1e-1\":{\"project\":\"7d1c0b6e-4f5a-4c43-9d0e-2a8b6f1e3c90\"}}",
        rewritten("{\"{qualifiedId}\":{\"project\":\"{projectId}\"}}"));
  }

  @Test
  void aPlaceholderIsSwappedEvenWhereTheStateReturnedThePinnedValue() throws Exception {
    // qits-landing-app's createRunner: the state's runner name is the pinned "localhost" both ways,
    // and "{runnerName}" alone is not a runner name the provider accepts.
    var out =
        StateParamRequestBody.rewrite(
            "{\"name\":\"{runnerName}\",\"slots\":1}".getBytes(UTF_8),
            Map.of("runnerName", "localhost"),
            Map.of("runnerName", "localhost"));
    assertEquals("{\"name\":\"localhost\",\"slots\":1}", new String(out.body(), UTF_8));
  }

  @Test
  void onlyAWholeStringIsSwappedAndAnUnknownPlaceholderStays() throws Exception {
    assertNull(rewritten("{\"body\":\"see contract-00000001-1\",\"x\":\"{nobody}\"}"));
  }

  @Test
  void aBodyThatIsNotJsonIsLeftAlone() throws Exception {
    assertNull(rewritten("contract-00000001-1"));
  }

  @Test
  void aPinnedValueTwoParamsShareAndTheStateSplitIsAmbiguous() {
    assertThrows(
        IllegalStateException.class,
        () ->
            StateParamRequestBody.rewrite(
                "{}".getBytes(UTF_8),
                Map.of("a", "same", "b", "same"),
                Map.of("a", "one", "b", "two")));
  }
}
