package eu.wohlben.qits.workspaces.api;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetSocket;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * The handoff at the end of a proxied upgrade: the daemon has answered 101, and its socket has to
 * be married to the browser's before either side loses a byte. Out of {@link DaemonForwarder} so a
 * plain Vert.x test can drive it with a late browser, which is the only way to open the window
 * this class closes without loading the CPU (qits-1762). {@link DaemonForwarder} is shared by
 * {@link ContainerProxyRoute} and {@link AgentProxyRoute}, so both routes' upgrades go through
 * this one handoff.
 *
 * <p><b>Two windows, one per side, both measured under CPU load and both read out of vertx-core
 * 4.5.26.</b> Neither socket buffers what arrives before somebody sets its handler:
 *
 * <ul>
 *   <li><b>The daemon side.</b> {@code HttpClientResponse.netSocket()} returns an {@code
 *       HttpNetSocket} that subscribes to the response stream at once, and its {@code handleData},
 *       {@code handleEnd} and {@code handleException} drop the event while the user handler is
 *       null. {@code pause()} holds data and a clean end in the stream's {@code InboundBuffer} — but
 *       <em>not a close</em>: the upgraded connection's TCP close reaches the socket as {@code
 *       handleException(HttpClosedException)}, which calls the end and close handlers right there,
 *       around the queue. A daemon that sent its close frame and hung up before {@code Handoff.pipe} ran
 *       left the browser open until its own 10 s closing timeout. So the daemon's close, end and
 *       exception handlers are registered here, at {@code netSocket()} time, and only
 *       <em>record</em> that the daemon is gone until there is a browser to close.
 *   <li><b>The browser side.</b> {@code Http1xServerResponse.netSocket} writes the 101 at once,
 *       swaps the pipeline to a {@code NetSocketImpl} in {@code context.execute(…)} and only then
 *       completes the promise — and a {@code NetSocketImpl} drops data while its handler is null.
 *       Run from another event loop, that is a string of separate tasks on the browser's loop, and
 *       the browser's first frame, sent the moment it reads the 101, can be read in between. So the
 *       take-over runs on the inbound request's own context: written 101, swapped pipeline,
 *       completed promise and installed handler are then one task on that loop, and the browser's
 *       reply cannot be read until it is over.
 * </ul>
 */
final class UpgradePipe {

  private static final Logger LOG = Logger.getLogger(UpgradePipe.class);

  private UpgradePipe() {}

  /**
   * Hold the daemon, take the browser over on its own context, then pipe the two.
   *
   * <p>Must be called on the daemon response's context, straight after {@code netSocket()} — the
   * pause and the handlers have to be in place before the next event on that stream. {@code
   * takeOver} is called on {@code browserContext} (synchronously when that is where we already
   * are), and its future's success should arrive in that same task, as {@code toNetSocket()}'s does
   * on its own loop; the pipe is installed in the listener. A failed take-over closes the daemon.
   */
  static void marry(
      NetSocket daemon, Context browserContext, Supplier<Future<NetSocket>> takeOver) {
    Handoff handoff = new Handoff(daemon, Vertx.currentContext());
    daemon.pause();
    // Registered now, not in pipe: a close is not queued by pause(), so a handler installed later
    // would never hear of it. These stay for the socket's whole life; see Handoff#daemonGone.
    daemon.closeHandler(closed -> handoff.daemonGone());
    daemon.endHandler(end -> handoff.daemonGone());
    daemon.exceptionHandler(failure -> handoff.daemonGone());

    Runnable start =
        () ->
            takeOver
                .get()
                .onFailure(
                    failure -> {
                      LOG.debugf(
                          "could not take over the browser socket: %s", String.valueOf(failure));
                      daemon.close();
                    })
                .onSuccess(handoff::pipe);
    if (browserContext == null || Vertx.currentContext() == browserContext) {
      start.run();
    } else {
      browserContext.runOnContext(v -> start.run());
    }
  }

  /** One upgrade's state across the two loops that touch it. */
  private static final class Handoff {

    private final NetSocket daemon;

    /** Where the daemon's stream queue drains; a close scheduled here lands after the drain. */
    private final Context daemonContext;

    /** Null until {@link #pipe} has resumed the daemon. Guarded by {@code this}. */
    private NetSocket browser;

    /** The daemon closed, ended or failed. Guarded by {@code this}. */
    private boolean gone;

    Handoff(NetSocket daemon, Context daemonContext) {
      this.daemon = daemon;
      this.daemonContext = daemonContext;
    }

    /**
     * The daemon is gone: close the browser if it is piped, otherwise leave the word for {@link
     * #pipe}. Runs on the daemon's loop, possibly before, during or after {@code pipe} on the
     * browser's — hence the lock, and hence one handler for both phases rather than a pair swapped
     * mid-flight. Once is enough; a close arrives as exception, end and close in a row.
     */
    void daemonGone() {
      NetSocket target;
      synchronized (this) {
        if (gone) {
          return;
        }
        gone = true;
        target = browser;
      }
      if (target != null) {
        closeAfterDrain(target);
      }
    }

    /**
     * Pump bytes both ways with a bound on each queue — the mirror of {@code
     * DaemonStreamRoute.pipe}, and it has to be, because it is the same pipe one hop closer to the
     * browser.
     *
     * <p><b>Raw bytes, not frames.</b> Neither end is decoded: what the browser sends is what the
     * daemon receives, frame boundaries, fragmentation, ping/pong and close codes included. That is
     * the property that lets the terminal's own close semantics (a bare 1000 meaning "detached, the
     * process survives") arrive intact, and it is why this is a {@code NetSocket} pipe rather than a
     * WebSocket client that would re-frame everything it forwarded.
     *
     * <p>The backpressure is the point. A write into a full queue is buffered on the heap without
     * complaint, so the reader on the other side has to be paused until it drains. Every close, end
     * and exception path closes the other side, so neither socket outlives its peer — the
     * browser's here, the daemon's in {@link UpgradePipe#marry}, registered at {@code netSocket()} time
     * because a close is not queued by {@code pause()}. A daemon that went before this ran gets
     * its browser closed only after the bytes the pause held have been written to it, so its close
     * frame reaches the browser ahead of the TCP close.
     */
    void pipe(NetSocket browser) {
      browser.handler(
          buffer -> {
            daemon.write(buffer);
            if (daemon.writeQueueFull()) {
              browser.pause();
              daemon.drainHandler(drained -> browser.resume());
            }
          });
      daemon.handler(
          buffer -> {
            browser.write(buffer);
            if (browser.writeQueueFull()) {
              daemon.pause();
              browser.drainHandler(drained -> daemon.resume());
            }
          });
      browser.endHandler(end -> daemon.close());
      browser.closeHandler(closed -> daemon.close());
      browser.exceptionHandler(failure -> daemon.close());
      // Schedules the drain of whatever the pause held on the daemon's context — before any close
      // scheduled there below, so the held bytes are written to the browser first.
      daemon.resume();
      boolean alreadyGone;
      synchronized (this) {
        this.browser = browser;
        alreadyGone = gone;
      }
      if (alreadyGone) {
        closeAfterDrain(browser);
      }
    }

    /**
     * Close the browser behind the daemon's queue. {@code resume()} drains that queue in a task on
     * the daemon's context, and a task scheduled there afterwards runs after it, so every held
     * byte has been handed to {@code browser.write} — and {@code close()} goes out behind the
     * writes it follows.
     */
    private void closeAfterDrain(NetSocket target) {
      if (daemonContext == null) {
        target.close();
      } else {
        daemonContext.runOnContext(v -> target.close());
      }
    }
  }
}
