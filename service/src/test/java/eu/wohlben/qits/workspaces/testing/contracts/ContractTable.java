package eu.wohlben.qits.workspaces.testing.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.pact.consumer.Via;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>Everything this service asks one provider</b> (qits-1149): the rows, the pact they make, the
 * run of each row against its own pact mock server, and the committed {@code
 * pacts/qits-workspaces-service_<provider>.json}. {@code ProjectsContract} is the same thing for
 * qits-projects, older than this class and kept as it is.
 *
 * <p><b>One mock server per row</b>, for {@code ProjectsConsumerPactTest}'s reason: rows that send
 * the identical request differ only in their trigger, which one mock server cannot tell apart.
 *
 * <p><b>Pending rows</b> ({@link ContractRow#waiting}) are in neither the pact nor the run; {@link
 * #needs} says what each waits for, and {@link #recordedPending} names the ones whose provider has
 * recorded the state since, so they are switched on.
 *
 * <p><b>{@link #via}</b> (qits-1163): when a library makes the calls on this service's behalf —
 * {@code qits-containers-client} — every interaction also carries {@code qits-via}, naming that
 * library's repository, artifact and version (the kit's own {@code Via}, as qits-pact-consumer's
 * {@code ConsumerPact.via} writes it). The committed file is compared with the via version masked,
 * so a client bump alone is not a contract change; the rewrite under {@code -Dgolden.update=true}
 * still writes the version.
 *
 * <p><b>Message rows</b> (qits-950, {@link ContractRow#message}): a table may hold frames its
 * provider SENDS this service — qits-workspaces-runner-daemon's — as {@code Asynchronous/Messages}
 * interactions ({@link GoldenMasters#messageInteraction}). {@link #verifyLive} hands each one's
 * {@link PactMessage} to the row's {@code receive} instead of running a mock server, and the
 * references they carry are {@code qits-call {app, frame}} and a {@code qits-trigger} on the
 * provider's side ({@link GoldenMasters.Trigger#sent}).
 */
public record ContractTable(GoldenMasters.Provider provider, List<ContractRow> rows, Via via) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public ContractTable(GoldenMasters.Provider provider, List<ContractRow> rows) {
    this(provider, rows, null);
  }

  public ContractTable {
    rows = List.copyOf(rows);
    Set<String> unique = new HashSet<>();
    for (ContractRow row : rows) {
      if (!unique.add(row.description() + "\u0000" + row.state())) {
        throw new IllegalArgumentException(
            "two rows share description and state: " + row.description() + " [" + row.state() + "]");
      }
    }
  }

  /** The same table, every interaction stamped {@code qits-via} with {@code library}. */
  public ContractTable via(Via library) {
    return new ContractTable(provider, rows, library);
  }

  /** {@code qits-workspaces-service_<provider repository>.json}. */
  public String file() {
    return GoldenMasters.CONSUMER + "_" + provider.repository() + ".json";
  }

  public List<ContractRow> live() {
    return rows.stream().filter(r -> !r.pending()).toList();
  }

  public List<ContractRow> pending() {
    return rows.stream().filter(ContractRow::pending).toList();
  }

  /** Why {@code row} waits: {@code needs provider state '<state>' for <operationId> in <provider>}. */
  public String needs(ContractRow row) {
    return "needs provider state '"
        + row.state()
        + "' for "
        + row.operationId()
        + " in "
        + provider.repository();
  }

  /** The pact of the live rows. */
  public V4Pact pact() {
    return pact(live());
  }

  /** A pact holding only {@code selected}. */
  public V4Pact pact(List<ContractRow> selected) {
    PactBuilder builder =
        new PactBuilder(GoldenMasters.CONSUMER, provider.repository(), PactSpecVersion.V4);
    for (ContractRow row : selected) {
      if (row.isMessage()) {
        GoldenMasters.messageInteraction(
            builder, provider, row.state(), row.operationId(), row.trigger());
        continue;
      }
      GoldenMasters.interaction(
          builder,
          provider,
          row.state(),
          row.operationId(),
          row.trigger(),
          row.request(),
          row.consumes(),
          via);
    }
    return builder.toPact();
  }

  /** Runs every live row against a mock server of its own; fails naming every row that failed. */
  public void verifyLive() {
    assertTrue(!live().isEmpty(), provider.repository() + ": no live row");
    List<String> failures = new ArrayList<>();
    for (ContractRow row : live()) {
      if (row.isMessage()) {
        try {
          receive(row);
        } catch (Exception | AssertionError e) {
          failures.add(row.description() + " [" + row.state() + "]: " + e);
        }
        continue;
      }
      Map<String, String> params = GoldenMasters.params(provider, row.state());
      PactVerificationResult result =
          ConsumerPactRunnerKt.runConsumerTest(
              pact(List.of(row)),
              MockProviderConfig.createDefault(PactSpecVersion.V4),
              (mockServer, context) -> {
                row.call().run(mockServer.getUrl(), params);
                return null;
              });
      if (!(result instanceof PactVerificationResult.Ok)) {
        failures.add(row.description() + " [" + row.state() + "]: " + describe(result));
      }
    }
    if (!failures.isEmpty()) {
      fail(
          failures.size()
              + " "
              + provider.repository()
              + " contract row(s) failed:\n  "
              + String.join("\n  ", failures));
    }
  }

  /** The live row {@code description} names in {@code state}; fails naming both when none does. */
  public ContractRow row(String description, String state) {
    return live().stream()
        .filter(r -> r.description().equals(description) && r.state().equals(state))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    provider.repository() + ": no live row " + description + " [" + state + "]"));
  }

  /**
   * A message row's {@code receive}, handed the pact's message for it — the interaction exactly as
   * {@link #pact(List)} writes it, with the state's params.
   */
  public void receive(ContractRow row) throws Exception {
    if (!row.isMessage()) {
      throw new IllegalArgumentException(row.description() + " is no message row");
    }
    V4Pact single = pact(List.of(row));
    V4Interaction.AsynchronousMessage message =
        (V4Interaction.AsynchronousMessage) single.getInteractions().get(0);
    row.receive().run(new PactMessage(message, GoldenMasters.params(provider, row.state())));
  }

  /** The pending rows whose (state, operation) the pinned golden masters now record. */
  public List<String> recordedPending() {
    List<String> recorded = new ArrayList<>();
    for (ContractRow row : pending()) {
      try {
        GoldenMasters.operation(provider, row.state(), row.operationId());
        recorded.add(row.description() + " [" + row.state() + "]");
      } catch (IllegalArgumentException stillMissing) {
        // Still waiting, as the row says.
      }
    }
    return recorded;
  }

  /** The committed pact file against the live rows' pact. */
  public void compareOrWritePactFile() throws IOException {
    PactFiles.compareOrWrite(file(), pact(), via == null ? s -> s : PactFiles::maskViaVersions);
  }

  /**
   * Both references on every interaction of the written pact, as the platform reads them — {@code
   * ProjectsPactFileTest.everyInteractionCarriesBothReferences}, for this provider.
   */
  public void assertEveryInteractionCarriesBothReferences() throws IOException {
    JsonNode pact = MAPPER.readTree(PactFiles.normalise(PactFiles.written(pact())));
    assertEquals(GoldenMasters.CONSUMER, pact.path("consumer").path("name").asText());
    assertEquals(provider.repository(), pact.path("provider").path("name").asText());
    JsonNode interactions = pact.path("interactions");
    assertEquals(live().size(), interactions.size(), "one interaction per live row");
    Map<String, String> keyOfKind =
        Map.of(
            "operation", "operationId", "event", "event", "schedule", "schedule", "ui", "interaction",
            "frame", "frame");
    for (JsonNode interaction : interactions) {
      String description = interaction.path("description").asText();
      // A message is the provider's frame: what it is called and what caused it are both its own.
      boolean message = "Asynchronous/Messages".equals(interaction.path("type").asText());
      JsonNode references = interaction.path("comments").path("references");
      JsonNode call = references.path("qits-call");
      assertEquals(provider.repository(), call.path("app").asText(), description);
      String callKey = message ? "frame" : "operationId";
      assertTrue(!call.path(callKey).asText().isBlank(), description + ": qits-call." + callKey);
      JsonNode trigger = references.path("qits-trigger");
      String kind = trigger.path("kind").asText();
      assertTrue(keyOfKind.containsKey(kind), description + ": unknown trigger kind '" + kind + "'");
      assertTrue(
          !trigger.path(keyOfKind.get(kind)).asText().isBlank(),
          description + ": qits-trigger." + keyOfKind.get(kind));
      assertEquals(
          message ? provider.repository() : GoldenMasters.CONSUMER,
          trigger.path("app").asText(),
          description);
      if (via != null) {
        assertEquals(MAPPER.valueToTree(via.reference()), references.path("qits-via"), description + ": qits-via");
      }
    }
  }

  private static String describe(PactVerificationResult result) {
    if (result instanceof PactVerificationResult.Error error) {
      return "error: " + error.getError();
    }
    return result.getDescription() + " — " + result;
  }
}
