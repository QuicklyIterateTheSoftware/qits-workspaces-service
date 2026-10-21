package eu.wohlben.qits.workspaces.testing.contracts;

import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Request;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>One (trigger, call) of a consumer contract</b> (qits-1149): the provider state and operation
 * whose recording answers it, what this service sends, the answer's body paths its code reads, and
 * the call itself — this service's real client against a pact mock server.
 *
 * <p><b>A pending row</b> names a (state, operation) the pinned golden masters do not record yet. It
 * is written in full, kept out of the pact file and not run; {@link ContractTable#pending} lists it
 * with {@link ContractTable#needs}, and {@code ConsumerPactFilesTest} fails the day the provider
 * records it, so it is switched on rather than forgotten.
 *
 * @param consumes the JSON paths the call reads ({@code $.clientId}, {@code $[*].contextId}); empty
 *     for a call read by its status alone
 * <p><b>A message row</b> (qits-950, {@link #message}) is a frame the provider SENDS this service:
 * its {@code operationId} is the recording's {@code frame:<type>}, it sends nothing and consumes no
 * paths, and instead of a {@link Call} against a mock server it {@link Receive}s the pact's message
 * — this service's real handling fed the frame, asserting what it did.
 *
 * @param pending whether the provider has yet to record the row's state and operation
 * @param receive a message row's handling; null for an HTTP row, whose {@code call} it replaces
 */
public record ContractRow(
    Trigger trigger,
    String state,
    String operationId,
    Request request,
    List<String> consumes,
    boolean pending,
    Call call,
    Receive receive) {

  /** The consumer's code, pointed at the mock server's base url, with the state's params. */
  @FunctionalInterface
  public interface Call {
    void run(String baseUrl, Map<String, String> params) throws Exception;
  }

  /** This service handling the frame of a {@link PactMessage}, and asserting the effect. */
  @FunctionalInterface
  public interface Receive {
    void run(PactMessage message) throws Exception;
  }

  public ContractRow {
    Objects.requireNonNull(trigger, "trigger: every row names the entry point that makes the call");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(operationId, "operationId");
    request = request == null ? Request.NONE : request;
    consumes = List.copyOf(consumes);
    if ((call == null) == (receive == null)) {
      throw new IllegalArgumentException(
          operationId + ": a row either calls (HTTP) or receives (a message), exactly one");
    }
  }

  /** A row the pinned golden masters answer. */
  public static ContractRow of(
      Trigger trigger,
      String state,
      String operationId,
      Request request,
      List<String> consumes,
      Call call) {
    return new ContractRow(trigger, state, operationId, request, consumes, false, call, null);
  }

  /**
   * A frame the provider sends in {@code state}, recorded as {@code frame:<frame>}, caused on its
   * side by {@code trigger} ({@link Trigger#sent}), and what this service does with it.
   */
  public static ContractRow message(Trigger trigger, String state, String frame, Receive receive) {
    return new ContractRow(
        trigger,
        state,
        GoldenMasters.FRAME_PREFIX + frame,
        Request.NONE,
        List.of(),
        false,
        null,
        Objects.requireNonNull(receive, "receive"));
  }

  /** Whether this row is a message the provider sends, rather than a call this service makes. */
  public boolean isMessage() {
    return receive != null;
  }

  /** The same row, waiting for its provider to record {@link #state} for {@link #operationId}. */
  public ContractRow waiting() {
    return new ContractRow(trigger, state, operationId, request, consumes, true, call, receive);
  }

  public String description() {
    return GoldenMasters.description(operationId, trigger);
  }
}
