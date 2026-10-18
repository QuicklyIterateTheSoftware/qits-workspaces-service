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
 * @param pending whether the provider has yet to record the row's state and operation
 */
public record ContractRow(
    Trigger trigger,
    String state,
    String operationId,
    Request request,
    List<String> consumes,
    boolean pending,
    Call call) {

  /** The consumer's code, pointed at the mock server's base url, with the state's params. */
  @FunctionalInterface
  public interface Call {
    void run(String baseUrl, Map<String, String> params) throws Exception;
  }

  public ContractRow {
    Objects.requireNonNull(trigger, "trigger: every row names the entry point that makes the call");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(operationId, "operationId");
    request = request == null ? Request.NONE : request;
    consumes = List.copyOf(consumes);
    Objects.requireNonNull(call, "call");
  }

  /** A row the pinned golden masters answer. */
  public static ContractRow of(
      Trigger trigger,
      String state,
      String operationId,
      Request request,
      List<String> consumes,
      Call call) {
    return new ContractRow(trigger, state, operationId, request, consumes, false, call);
  }

  /** The same row, waiting for its provider to record {@link #state} for {@link #operationId}. */
  public ContractRow waiting() {
    return new ContractRow(trigger, state, operationId, request, consumes, true, call);
  }

  public String description() {
    return GoldenMasters.description(operationId, trigger);
  }
}
