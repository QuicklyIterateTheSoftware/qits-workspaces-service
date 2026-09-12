package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.TechnicalProcess;
import eu.wohlben.qits.workspaces.control.TechnicalProcessRegistry;
import eu.wohlben.qits.workspaces.dto.TechnicalProcessFrame;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * The payload-bearing SSE stream of one {@link TechnicalProcess}: on every (re)connect the process
 * replays all buffered segments and lines, then streams live, then emits the terminal {@code done}
 * frame and <em>completes</em> (a subscriber of an already-terminal process gets the full replay
 * plus an immediate {@code done}). An unknown — or already-evicted — id is a 404; {@code
 * EventSource} treats a non-200 as fatal, so an expired stream doesn't retry-loop. A ~25s {@code
 * ping} heartbeat keeps idle connections alive through the dev proxies, riding the same emitter so
 * it stops with the stream (mirroring {@code WorkspaceEventsController}'s heartbeat — but this is
 * deliberately <em>not</em> that channel: hints stay payload-free there, the log payload rides only
 * here).
 *
 * <p>Hidden from OpenAPI like the other stream endpoints: the frontend consumes it with a raw
 * {@code EventSource}, not the generated client; {@link TechnicalProcessFrame}'s constants are the
 * wire vocabulary.
 */
@Path("/technical-processes/{id}/events")
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class TechnicalProcessEventsController {

  @Inject TechnicalProcessRegistry registry;

  /** Heartbeat period; test-tunable so a suite doesn't wait 25s to observe a ping. */
  @ConfigProperty(name = "qits.process.heartbeat-ms", defaultValue = "25000")
  long heartbeatMillis;

  private final ScheduledExecutorService pinger =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "technical-process-heartbeat");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    pinger.shutdownNow();
  }

  // A read, so an agent may make it too (phase 4: agents keep every read, lose writes).
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @GET
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.APPLICATION_JSON)
  @Operation(hidden = true)
  public Multi<TechnicalProcessFrame> events(@PathParam("id") String id) {
    TechnicalProcess process =
        registry.find(id).orElseThrow(() -> new NotFoundException("Unknown technical process"));
    return Multi.createFrom()
        .emitter(emitter -> subscribe(process, emitter), BackPressureStrategy.BUFFER);
  }

  /** Adapt the domain listener contract to a Mutiny emitter; heartbeat and detach ride along. */
  private void subscribe(
      TechnicalProcess process, MultiEmitter<? super TechnicalProcessFrame> emitter) {
    TechnicalProcess.Listener listener =
        new TechnicalProcess.Listener() {
          @Override
          public void onFrame(TechnicalProcessFrame frame) {
            emitter.emit(frame);
          }

          @Override
          public void onDone() {
            emitter.complete();
          }

          @Override
          public boolean isOpen() {
            return !emitter.isCancelled();
          }
        };
    ScheduledFuture<?> heartbeat =
        pinger.scheduleAtFixedRate(
            () -> emitter.emit(TechnicalProcessFrame.ping(-1)),
            heartbeatMillis,
            heartbeatMillis,
            TimeUnit.MILLISECONDS);
    emitter.onTermination(
        () -> {
          heartbeat.cancel(false);
          process.detach(listener);
        });
    process.attach(listener);
  }
}
